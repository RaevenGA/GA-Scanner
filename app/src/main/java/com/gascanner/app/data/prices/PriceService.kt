package com.gascanner.app.data.prices

import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.cache.CachedPrice
import com.gascanner.app.data.cache.PriceCacheDao
import com.gascanner.app.data.settings.AppSettings
import com.gascanner.app.data.settings.PriceSourceConfig
import com.gascanner.app.data.settings.PriceSourceKind
import com.gascanner.app.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

data class PriceQuote(
    val sourceId: String,
    val sourceName: String,
    val label: String,
    val amount: Double,
    val currency: String,
    val detail: String?,
    val url: String?,
    val bestMatch: Boolean,
    val isDefault: Boolean,
    val inStock: Boolean? = null,
)

data class StoreLink(val sourceId: String, val name: String, val url: String, val isDefault: Boolean)

data class PriceResult(
    val quotes: List<PriceQuote> = emptyList(),
    val links: List<StoreLink> = emptyList(),
    val errors: List<String> = emptyList(),
)

data class Money(val amount: Double, val currency: String) {
    operator fun times(n: Int) = Money(amount * n, currency)
    fun format(): String = PriceService.format(amount, currency)
}

/** What's needed to price one owned card. */
data class PricedItem(
    val editionUuid: String,
    val cardName: String,
    val setPrefix: String,
    val setName: String,
    val collectorNumber: String,
    val foil: Boolean,
)

class PriceService(
    private val settings: SettingsRepository,
    private val tcg: TcgCsvProvider,
    private val stores: StorePriceProvider,
    private val fx: CurrencyConverter,
    private val cache: PriceCacheDao,
) {
    /** Sources in display order: default first, then the rest as configured. */
    private fun ordered(s: AppSettings): List<PriceSourceConfig> {
        val enabled = s.priceSources.filter { it.enabled }
        val def = s.defaultSource
        return listOfNotNull(def) + enabled.filter { it.id != def?.id }
    }

    suspend fun pricesFor(card: CardDto, edition: EditionDto?, foil: Boolean = false): PriceResult {
        val s = settings.current()
        val defId = s.defaultSource?.id
        val quotes = mutableListOf<PriceQuote>()
        val links = mutableListOf<StoreLink>()
        val errors = mutableListOf<String>()

        for (source in ordered(s)) {
            val isDefault = source.id == defId
            try {
                when (source.kind) {
                    PriceSourceKind.TCGPLAYER_MARKET -> quotes += tcgQuotes(source, card, edition, foil, isDefault)
                    PriceSourceKind.SEARCH_LINK -> try {
                        // Fetch first: it detects the shop platform, which can correct the search link.
                        if (source.fetchPrices) quotes += storeQuotes(source, card, edition, foil, isDefault)
                    } finally {
                        fillTemplate(linkTemplate(source), card, edition)?.let { links += StoreLink(source.id, source.name, it, isDefault) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += if (e is StoreUnreadableException) e.message.orEmpty() else "${source.name}: ${e.message ?: "unavailable"}"
            }
        }
        return PriceResult(convertAll(quotes, s.displayCurrency), links, errors)
    }

    /** A WooCommerce shop doesn't use Shopify's /search?q= address; switch the guessed link. */
    private fun linkTemplate(source: PriceSourceConfig): String {
        val t = source.urlTemplate
        val woo = stores.detectedPlatform(source) == com.gascanner.app.data.settings.StorePlatform.WOOCOMMERCE
        return if (woo && t.endsWith("/search?q={name}")) source.effectiveBaseUrl + "/?s={name}&post_type=product" else t
    }

    private suspend fun convertAll(quotes: List<PriceQuote>, target: String): List<PriceQuote> =
        if (target == "ORIGINAL") quotes else quotes.map { q ->
            fx.convert(q.amount, q.currency, target)?.let { q.copy(amount = it, currency = target) } ?: q
        }

    private suspend fun tcgQuotes(
        source: PriceSourceConfig, card: CardDto, edition: EditionDto?, foil: Boolean, isDefault: Boolean,
    ): List<PriceQuote> {
        val rows = tcg.lookup(card.name, edition?.setPrefix, edition?.set?.name, edition?.collectorNumber)
        if (rows.isEmpty()) return emptyList()
        fun score(r: TcgCsvProvider.Row) = tcg.score(r, edition?.setPrefix, edition?.set?.name, edition?.collectorNumber)
        val top = score(rows.first())
        val quotes = rows.take(MAX_ROWS).mapNotNull { row ->
            val amount = row.market ?: row.mid ?: row.low ?: return@mapNotNull null
            val detail = listOfNotNull(
                row.low?.let { "low ${"%.2f".format(Locale.US, it)}" },
                row.mid?.let { "mid ${"%.2f".format(Locale.US, it)}" },
                row.number,
            ).joinToString(" · ").ifBlank { null }
            PriceQuote(
                sourceId = source.id,
                sourceName = source.name,
                label = listOfNotNull(row.groupName, row.subType).joinToString(" · "),
                amount = amount,
                currency = "USD",
                detail = detail,
                url = row.url,
                bestMatch = top > 0 && score(row) == top,
                isDefault = isDefault,
            )
        }
        if (edition != null) {
            tcgBest(rows, edition.setPrefix, edition.set?.name, edition.collectorNumber, foil)?.let {
                cache.put(CachedPrice(edition.uuid, source.id, foil, it.amount, it.currency, null, System.currentTimeMillis()))
            }
        }
        return quotes
    }

    private fun tcgBest(
        rows: List<TcgCsvProvider.Row>, prefix: String?, setName: String?, number: String?, foil: Boolean,
    ): Money? {
        if (rows.isEmpty()) return null
        val top = tcg.score(rows.first(), prefix, setName, number)
        val best = rows.filter { tcg.score(it, prefix, setName, number) == top }
        val pick = best.firstOrNull { it.isFoil == foil } ?: best.first()
        val amount = pick.market ?: pick.mid ?: pick.low ?: return null
        return Money(amount, "USD")
    }

    private suspend fun storeQuotes(
        source: PriceSourceConfig, card: CardDto, edition: EditionDto?, foil: Boolean, isDefault: Boolean,
    ): List<PriceQuote> {
        val hits = stores.search(source, card.name)
            .map { it to StorePriceProvider.matchScore(it.matchText, card.name, edition?.setPrefix, edition?.collectorNumber) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<StoreHit, Int>> { it.second }.thenBy { it.first.price })
        val best = bestStoreHit(hits, foil)
        if (best != null && edition != null) {
            cache.put(CachedPrice(edition.uuid, source.id, foil, best.price, best.currency, best.url, System.currentTimeMillis()))
        }
        return hits.take(MAX_ROWS).map { (hit, score) ->
            PriceQuote(
                sourceId = source.id,
                sourceName = source.name,
                label = hit.title,
                amount = hit.price,
                currency = hit.currency,
                detail = null,
                url = hit.url,
                bestMatch = hit === best,
                isDefault = isDefault,
                inStock = hit.inStock,
            )
        }
    }

    /** Prefers the closest listing, matching foil, in stock; then the cheapest. */
    private fun bestStoreHit(hits: List<Pair<StoreHit, Int>>, foil: Boolean): StoreHit? {
        if (hits.isEmpty()) return null
        val top = hits.maxOf { it.second }
        val pool = hits.filter { it.second == top }.map { it.first }
        val foilMatch = pool.filter { StorePriceProvider.looksFoil(it.matchText) == foil }.ifEmpty { pool }
        val stocked = foilMatch.filter { it.inStock != false }.ifEmpty { foilMatch }
        return stocked.minByOrNull { it.price }
    }

    // ---------- Collection value ----------

    /** Currency totals are shown in: the display currency, or the default source's own. */
    suspend fun totalCurrency(): String {
        val s = settings.current()
        if (s.displayCurrency != "ORIGINAL") return s.displayCurrency
        return s.defaultSource?.let { if (it.kind == PriceSourceKind.TCGPLAYER_MARKET) "USD" else it.currency } ?: "EUR"
    }

    /**
     * Best known price for one copy, without network calls to shops:
     * cached price from the default source, else any cached price, else TCGplayer.
     */
    suspend fun estimate(item: PricedItem, cached: Map<String, List<CachedPrice>>? = null): Money? {
        val s = settings.current()
        val rows = cached?.get(item.editionUuid) ?: cache.forEdition(item.editionUuid)
        val sameFoil = rows.filter { it.foil == item.foil }.ifEmpty { rows }
        val defId = s.defaultSource?.id
        sameFoil.firstOrNull { it.sourceId == defId }?.let { return Money(it.amount, it.currency) }
        if (defId != PriceSourceConfig.TCGPLAYER_MARKET_ID) {
            sameFoil.maxByOrNull { it.fetchedAt }?.let { return Money(it.amount, it.currency) }
        }
        return runCatching {
            tcgBest(tcg.lookup(item.cardName, item.setPrefix, item.setName, item.collectorNumber),
                item.setPrefix, item.setName, item.collectorNumber, item.foil)
        }.getOrNull() ?: sameFoil.maxByOrNull { it.fetchedAt }?.let { Money(it.amount, it.currency) }
    }

    suspend fun allCached(): Map<String, List<CachedPrice>> = cache.all().groupBy { it.editionUuid }

    suspend fun convert(money: Money, to: String): Money? =
        fx.convert(money.amount, money.currency, to)?.let { Money(it, to) }

    /** Fetches the default shop's price for each item (slowly, to be polite to the shop). */
    suspend fun refreshDefault(items: List<PricedItem>, onProgress: (Int, Int) -> Unit) {
        val s = settings.current()
        val source = s.defaultSource ?: return
        if (source.kind == PriceSourceKind.TCGPLAYER_MARKET) {
            tcg.refresh()
            onProgress(items.size, items.size)
            return
        }
        if (!source.fetchPrices) return
        val byName = items.groupBy { it.cardName }
        var done = 0
        for ((name, group) in byName) {
            val hits = runCatching { stores.search(source, name) }.getOrElse { e ->
                if (e is CancellationException) throw e
                if (e is StoreUnreadableException) throw e
                emptyList()
            }
            for (item in group) {
                val scored = hits.map { it to StorePriceProvider.matchScore(it.matchText, name, item.setPrefix, item.collectorNumber) }
                    .filter { it.second > 0 }
                bestStoreHit(scored, item.foil)?.let {
                    cache.put(CachedPrice(item.editionUuid, source.id, item.foil, it.price, it.currency, it.url, System.currentTimeMillis()))
                }
            }
            done += group.size
            onProgress(done, items.size)
            delay(STORE_DELAY_MS)
        }
    }

    suspend fun refreshTcgplayer() = tcg.refresh()

    companion object {
        private const val MAX_ROWS = 8
        private const val STORE_DELAY_MS = 350L

        fun fillTemplate(template: String, card: CardDto, edition: EditionDto?): String? =
            fillTemplate(template, card.name, edition?.setPrefix, edition?.collectorNumber)

        fun fillTemplate(template: String, name: String, setPrefix: String?, number: String?): String? {
            if (template.isBlank()) return null
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val set = setPrefix.orEmpty()
            return template
                .replace("{name}", enc(name))
                .replace("{set}", enc(set))
                .replace("{number}", enc(number.orEmpty()))
                .replace("{query}", enc(listOf(name, set).filter { it.isNotBlank() }.joinToString(" ")))
        }

        fun format(amount: Double, currency: String): String = runCatching {
            NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
                this.currency = Currency.getInstance(currency)
            }.format(amount)
        }.getOrElse { "%.2f %s".format(amount, currency) }
    }
}

/** Exchange rates from the European Central Bank via frankfurter.app (free, no key). */
class CurrencyConverter(private val client: OkHttpClient, private val json: Json) {
    @Serializable
    private data class Rates(val base: String = "EUR", val rates: Map<String, Double> = emptyMap())

    private val mutex = Mutex()
    private var eurRates: Map<String, Double>? = null
    private var fetchedAt = 0L

    suspend fun convert(amount: Double, from: String, to: String): Double? {
        if (from.equals(to, ignoreCase = true)) return amount
        val rates = rates() ?: return null
        val fromRate = rates[from.uppercase()] ?: return null
        val toRate = rates[to.uppercase()] ?: return null
        return amount / fromRate * toRate
    }

    private suspend fun rates(): Map<String, Double>? = mutex.withLock {
        val fresh = System.currentTimeMillis() - fetchedAt < 12L * 60 * 60 * 1000
        if (eurRates != null && fresh) return@withLock eurRates
        runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url("https://api.frankfurter.app/latest").build()).execute().use {
                    if (!it.isSuccessful) error("FX ${it.code}")
                    json.decodeFromString(Rates.serializer(), it.body?.string().orEmpty())
                }
            }
        }.getOrNull()?.let { r ->
            eurRates = r.rates + ("EUR" to 1.0)
            fetchedAt = System.currentTimeMillis()
        }
        eurRates
    }
}
