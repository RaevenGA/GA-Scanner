package com.gascanner.app.data.prices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * TCGplayer prices via tcgcsv.com, a free daily mirror of TCGplayer's catalog and prices
 * (TCGplayer no longer issues new API keys). The whole Grand Archive category is downloaded
 * once a day and cached on the device, so lookups after that are instant and offline.
 */
class TcgCsvProvider(
    private val client: OkHttpClient,
    private val json: Json,
    cacheDir: File,
) {
    @Serializable
    data class Row(
        val productId: Int,
        val name: String,
        val groupName: String,
        val groupAbbr: String? = null,
        val number: String? = null,
        val rarity: String? = null,
        val url: String? = null,
        val subType: String? = null,
        val market: Double? = null,
        val low: Double? = null,
        val mid: Double? = null,
    )

    @Serializable
    private data class Index(val fetchedAt: Long, val rows: List<Row>)

    @Serializable private data class Envelope<T>(val success: Boolean = true, val results: List<T> = emptyList())
    @Serializable private data class Category(val categoryId: Int, val name: String = "", val displayName: String? = null)
    @Serializable private data class Group(val groupId: Int, val name: String = "", val abbreviation: String? = null)
    @Serializable private data class Ext(val name: String = "", val displayName: String? = null, val value: String? = null)
    @Serializable private data class Product(
        val productId: Int,
        val name: String = "",
        val cleanName: String? = null,
        val url: String? = null,
        val extendedData: List<Ext> = emptyList(),
    )
    @Serializable private data class Price(
        val productId: Int,
        val lowPrice: Double? = null,
        val midPrice: Double? = null,
        val marketPrice: Double? = null,
        val subTypeName: String? = null,
    )

    private val cacheFile = File(cacheDir, "tcgcsv_grand_archive.json")
    private val mutex = Mutex()
    private var byName: Map<String, List<Row>>? = null
    private var fetchedAt: Long = 0L

    val lastUpdated: Long get() = fetchedAt

    /** Matching price rows for a card, best match first. */
    suspend fun lookup(cardName: String, setPrefix: String?, setName: String?, collectorNumber: String?): List<Row> {
        val index = ensureIndex()
        val key = normalize(cardName)
        if (key.isEmpty()) return emptyList()
        val candidates = index.entries
            .filter { (k, _) -> k == key || k.startsWith("$key ") }
            .flatMap { it.value }
        return candidates
            .map { it to score(it, setPrefix, setName, collectorNumber) }
            .sortedWith(compareByDescending<Pair<Row, Int>> { it.second }.thenByDescending { it.first.market ?: 0.0 })
            .map { it.first }
    }

    fun score(row: Row, setPrefix: String?, setName: String?, collectorNumber: String?): Int {
        var s = 0
        val prefixCore = setPrefix?.substringBefore(' ')?.lowercase().orEmpty()
        if (prefixCore.isNotEmpty() && row.groupAbbr?.lowercase()?.startsWith(prefixCore) == true) s += 2
        val normSet = normalize(setName.orEmpty())
        if (normSet.isNotEmpty() && normalize(row.groupName).contains(normSet)) s += 2
        // "1st Edition" prints are separate TCGplayer groups.
        val firstEd = setPrefix?.contains("1st", ignoreCase = true) == true
        if (firstEd == row.groupName.contains("1st", ignoreCase = true)) s += 1
        val wanted = digits(collectorNumber)
        if (wanted.isNotEmpty() && digits(row.number) == wanted) s += 3
        return s
    }

    suspend fun refresh() {
        mutex.withLock { download() }
    }

    private suspend fun ensureIndex(): Map<String, List<Row>> = mutex.withLock {
        val fresh = System.currentTimeMillis() - fetchedAt < MAX_AGE_MS
        byName?.takeIf { fresh }?.let { return@withLock it }
        loadFromDisk()?.let { if (System.currentTimeMillis() - it.fetchedAt < MAX_AGE_MS) return@withLock install(it) }
        try {
            download()
        } catch (e: IOException) {
            // Offline: fall back to any stale cache rather than showing nothing.
            loadFromDisk()?.let { install(it) } ?: throw e
        }
    }

    private suspend fun download(): Map<String, List<Row>> {
        val categories = fetch("$BASE/categories", Envelope.serializer(Category.serializer())).results
        val category = categories.firstOrNull {
            it.name.contains("Grand Archive", ignoreCase = true) ||
                it.displayName?.contains("Grand Archive", ignoreCase = true) == true
        } ?: throw IOException("Grand Archive not found on TCGplayer mirror")

        val groups = fetch("$BASE/${category.categoryId}/groups", Envelope.serializer(Group.serializer())).results
        val limiter = Semaphore(4)
        val rows = coroutineScope {
            groups.map { group ->
                async {
                    limiter.withPermit {
                        runCatching { groupRows(category.categoryId, group) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }
        if (rows.isEmpty()) throw IOException("No TCGplayer prices downloaded")
        val index = Index(System.currentTimeMillis(), rows)
        withContext(Dispatchers.IO) {
            cacheFile.writeText(json.encodeToString(Index.serializer(), index))
        }
        return install(index)
    }

    private suspend fun groupRows(categoryId: Int, group: Group): List<Row> {
        val products = fetch("$BASE/$categoryId/${group.groupId}/products", Envelope.serializer(Product.serializer())).results
        val prices = fetch("$BASE/$categoryId/${group.groupId}/prices", Envelope.serializer(Price.serializer())).results
            .groupBy { it.productId }
        return products.flatMap { product ->
            val ext = product.extendedData.associate { it.name.lowercase() to it.value }
            prices[product.productId].orEmpty().map { price ->
                Row(
                    productId = product.productId,
                    name = product.cleanName?.takeIf { it.isNotBlank() } ?: product.name,
                    groupName = group.name,
                    groupAbbr = group.abbreviation,
                    number = ext["number"],
                    rarity = ext["rarity"],
                    url = product.url,
                    subType = price.subTypeName,
                    market = price.marketPrice,
                    low = price.lowPrice,
                    mid = price.midPrice,
                )
            }
        }
    }

    private fun install(index: Index): Map<String, List<Row>> {
        val map = index.rows.groupBy { normalize(it.name) }
        byName = map
        fetchedAt = index.fetchedAt
        return map
    }

    private suspend fun loadFromDisk(): Index? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext null
        runCatching { json.decodeFromString(Index.serializer(), cacheFile.readText()) }.getOrNull()
    }

    private suspend fun <T> fetch(url: String, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TCGplayer mirror returned ${response.code}")
            json.decodeFromString(serializer, response.body?.string().orEmpty())
        }
    }

    companion object {
        private const val BASE = "https://tcgcsv.com/tcgplayer"
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

        fun normalize(s: String): String =
            s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        private fun digits(s: String?): String =
            s.orEmpty().filter { it.isDigit() }.trimStart('0')
    }
}
