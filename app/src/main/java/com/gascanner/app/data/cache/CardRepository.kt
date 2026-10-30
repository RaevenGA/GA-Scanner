package com.gascanner.app.data.cache

import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.api.GatcgApi
import com.gascanner.app.data.api.HttpStatusException
import com.gascanner.app.data.api.SearchResponse
import com.gascanner.app.data.settings.SettingsRepository
import com.gascanner.app.util.nameSimilarity
import com.gascanner.app.util.normalizeName
import com.gascanner.app.util.normalizeNumber
import com.gascanner.app.util.normalizePrefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val done: Int, val total: Int) : SyncState
    data class Done(val cards: Int, val at: Long) : SyncState
    data class Failed(val message: String, val hasData: Boolean) : SyncState
}

data class CardHit(val card: CardDto, val edition: EditionDto?)

data class CardSearchResult(val cards: List<CardDto>, val hasMore: Boolean, val offline: Boolean)

/**
 * Card data with an offline copy of the whole Grand Archive database.
 * The first launch downloads everything; later launches only fetch cards changed since the last sync.
 * Every lookup falls back to the online API while the offline copy is still empty.
 */
class CardRepository(
    private val api: GatcgApi,
    db: CacheDatabase,
    private val settings: SettingsRepository,
    private val json: Json,
) {
    private val dao = db.cards()
    private val syncMutex = Mutex()

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync.asStateFlow()

    @Volatile private var namesCache: List<CardName>? = null

    suspend fun offlineCount(): Int = dao.count()

    fun syncOnStartup(scope: CoroutineScope) {
        scope.launch { sync(full = false) }
    }

    suspend fun sync(full: Boolean) {
        if (syncMutex.isLocked) return
        syncMutex.withLock {
            val count = dao.count()
            try {
                val last = settings.current().lastSyncAt
                if (full || count == 0 || last == 0L) fullSync() else incrementalSync(last)
                val now = System.currentTimeMillis()
                settings.setLastSync(now)
                namesCache = null
                _sync.value = SyncState.Done(dao.count(), now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _sync.value = SyncState.Failed(e.message ?: e.javaClass.simpleName, count > 0)
            }
        }
    }

    private suspend fun fullSync() {
        _sync.value = SyncState.Running(0, 0)
        val params = mapOf("sort" to listOf("name"))
        val first = retry { api.searchPage(params, 1) }
        if (first.data.isEmpty()) {
            syncByPrefixes()
            return
        }
        pageThrough(params, first)
    }

    /** Fallback if an unfiltered search returns nothing: walk the database set by set. */
    private suspend fun syncByPrefixes() {
        val prefixes = retry { api.setPrefixes() }
        if (prefixes.isEmpty()) throw IOException("Card database returned no cards")
        prefixes.forEachIndexed { i, prefix ->
            val params = mapOf("prefix" to listOf(prefix))
            val first = retry { api.searchPage(params, 1) }
            pageThrough(params, first, report = false)
            _sync.value = SyncState.Running(i + 1, prefixes.size)
        }
    }

    private suspend fun incrementalSync(lastSyncAt: Long) {
        // A two-day overlap covers clock differences and late updates.
        val since = isoDate(lastSyncAt - 2L * 24 * 60 * 60 * 1000)
        for (key in listOf("last_update", "edition_last_update")) {
            val params = mapOf(key to listOf(since), "sort" to listOf("name"))
            val first = try {
                retry { api.searchPage(params, 1) }
            } catch (e: HttpStatusException) {
                if (e.code == 400) continue else throw e
            }
            if (first.data.isNotEmpty()) pageThrough(params, first)
        }
    }

    private suspend fun pageThrough(params: Map<String, List<String>>, first: SearchResponse, report: Boolean = true) {
        store(first.data)
        var done = first.data.size
        val total = maxOf(first.totalCards, done)
        if (report) _sync.value = SyncState.Running(done, total)

        if (first.totalPages > 1) {
            for (chunk in (2..first.totalPages).chunked(PARALLEL_PAGES)) {
                val pages = coroutineScope {
                    chunk.map { p -> async { retry { api.searchPage(params, p) } } }.awaitAll()
                }
                pages.forEach { store(it.data); done += it.data.size }
                if (report) _sync.value = SyncState.Running(done, total)
            }
        } else {
            var page = first
            while (page.hasMore && page.data.isNotEmpty()) {
                page = retry { api.searchPage(params, page.page + 1) }
                store(page.data)
                done += page.data.size
                if (report) _sync.value = SyncState.Running(done, maxOf(total, done))
            }
        }
    }

    private suspend fun store(cards: List<CardDto>) {
        if (cards.isEmpty()) return
        val rows = cards.map { card ->
            CachedCard(
                uuid = card.uuid,
                slug = card.slug,
                name = card.name,
                nameNorm = normalizeName(card.name),
                json = json.encodeToString(CardDto.serializer(), card.copy(resultEditions = emptyList())),
            )
        }
        val editions = cards.flatMap { card ->
            card.allEditions.map { e ->
                CachedEdition(
                    uuid = e.uuid,
                    cardUuid = card.uuid,
                    cardSlug = card.slug,
                    setPrefix = e.setPrefix,
                    prefixNorm = normalizePrefix(e.setPrefix),
                    collectorNumber = e.collectorNumber,
                    numberNorm = normalizeNumber(e.collectorNumber),
                    imageUrl = e.imageUrl,
                )
            }
        }
        dao.replaceCards(rows, editions)
    }

    private fun decode(row: CachedCard): CardDto? =
        runCatching { json.decodeFromString(CardDto.serializer(), row.json) }.getOrNull()

    // ---------- Lookups ----------

    suspend fun search(query: String, page: Int = 1): CardSearchResult {
        val q = normalizeName(query)
        if (q.isBlank()) return CardSearchResult(emptyList(), false, true)
        if (dao.count() > 0) {
            val pattern = "%" + q.split(' ').joinToString("%") + "%"
            val rows = dao.searchByName(pattern, LOCAL_LIMIT)
            val ranked = rows.sortedWith(
                compareBy<CachedCard>(
                    { if (it.nameNorm == q) 0 else if (it.nameNorm.startsWith(q)) 1 else 2 },
                    { it.name.lowercase() },
                )
            )
            return CardSearchResult(ranked.mapNotNull(::decode), hasMore = false, offline = true)
        }
        val r = api.search(query.trim(), page)
        return CardSearchResult(r.data, hasMore = r.hasMore || r.page < r.totalPages, offline = false)
    }

    suspend fun card(slug: String): CardDto {
        dao.bySlug(slug)?.let(::decode)?.let { return it }
        val card = api.cardBySlug(slug)
        runCatching { store(listOf(card)) }
        return card
    }

    suspend fun cardsBySlug(slugs: Collection<String>): Map<String, CardDto> {
        if (slugs.isEmpty()) return emptyMap()
        return slugs.chunked(500).flatMap { dao.bySlugs(it) }
            .mapNotNull(::decode).associateBy { it.slug }
    }

    suspend fun cardByUuid(uuid: String): CardDto? = dao.byUuid(uuid)?.let(::decode)

    /** Finds the edition with this edition uuid (offline only). */
    suspend fun byEditionUuid(editionUuid: String): CardHit? {
        val e = dao.edition(editionUuid) ?: return null
        val card = cardByUuid(e.cardUuid) ?: return null
        return CardHit(card, card.allEditions.firstOrNull { it.uuid == editionUuid })
    }

    /**
     * Cards matching a footer code. If the edition marker ("1st") was not read,
     * all printings with that set code and number are returned.
     */
    suspend fun byFooter(setPrefix: String, collectorNumber: String, allowNetwork: Boolean = true): List<CardHit> {
        val p = normalizePrefix(setPrefix)
        val n = normalizeNumber(collectorNumber)
        if (p.isEmpty()) return emptyList()
        if (dao.count() > 0) {
            var rows = dao.editionsByFooter(p, n)
            if (rows.isEmpty() && !p.endsWith("1ST")) rows = dao.editionsByFooter(p + "1ST", n)
            if (rows.isEmpty() && p.endsWith("1ST")) rows = dao.editionsByFooter(p.removeSuffix("1ST"), n)
            return rows.mapNotNull { row ->
                val card = cardByUuid(row.cardUuid) ?: return@mapNotNull null
                CardHit(card, card.allEditions.firstOrNull { it.uuid == row.uuid })
            }.distinctBy { it.edition?.uuid }
        }
        if (!allowNetwork) return emptyList()
        val card = runCatching { api.cardBySetAndNumber(setPrefix, collectorNumber) }.getOrNull() ?: return emptyList()
        val edition = card.allEditions.firstOrNull {
            normalizePrefix(it.setPrefix) == p && normalizeNumber(it.collectorNumber) == n
        } ?: card.resultEditions.firstOrNull() ?: card.editions.firstOrNull()
        return listOf(CardHit(card, edition))
    }

    suspend fun allNames(): List<CardName> {
        namesCache?.let { return it }
        val names = dao.allNames()
        if (names.isNotEmpty()) namesCache = names
        return names
    }

    /** Best card-name matches for OCR text, offline. Empty when no offline data. */
    suspend fun fuzzyByName(text: String, limit: Int = 5, minScore: Double = 0.5): List<Pair<CardName, Double>> =
        withContext(Dispatchers.Default) {
            if (text.isBlank()) return@withContext emptyList()
            allNames()
                .map { it to nameSimilarity(text, it.name) }
                .filter { it.second >= minScore }
                .sortedByDescending { it.second }
                .take(limit)
        }

    private suspend fun <T> retry(block: suspend () -> T): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpStatusException) {
                if (e.code in 400..499 && e.code != 429) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
            delay(1000L * (attempt + 1))
        }
        throw last ?: IOException("Request failed")
    }

    private companion object {
        const val PARALLEL_PAGES = 3
        const val LOCAL_LIMIT = 150

        fun isoDate(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    }
}
