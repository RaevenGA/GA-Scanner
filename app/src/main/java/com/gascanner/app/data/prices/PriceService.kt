package com.gascanner.app.data.prices

import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.settings.PriceSourceConfig
import com.gascanner.app.data.settings.PriceSourceKind
import com.gascanner.app.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
    val sourceName: String,
    val label: String,
    val amount: Double,
    val currency: String,
    val detail: String?,
    val url: String?,
    val bestMatch: Boolean,
)

data class StoreLink(val name: String, val url: String)

data class PriceResult(
    val quotes: List<PriceQuote> = emptyList(),
    val links: List<StoreLink> = emptyList(),
    val errors: List<String> = emptyList(),
)

class PriceService(
    private val settings: SettingsRepository,
    private val tcg: TcgCsvProvider,
    private val fx: CurrencyConverter,
) {
    suspend fun pricesFor(card: CardDto, edition: EditionDto?): PriceResult {
        val s = settings.current()
        val quotes = mutableListOf<PriceQuote>()
        val links = mutableListOf<StoreLink>()
        val errors = mutableListOf<String>()

        for (source in s.priceSources.filter { it.enabled }) {
            when (source.kind) {
                PriceSourceKind.TCGPLAYER_MARKET -> try {
                    quotes += tcgQuotes(source, card, edition)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errors += "${source.name}: ${e.message ?: "unavailable"}"
                }
                PriceSourceKind.SEARCH_LINK -> fillTemplate(source.urlTemplate, card, edition)
                    ?.let { links += StoreLink(source.name, it) }
            }
        }

        val converted = if (s.displayCurrency == "ORIGINAL") quotes else quotes.map { q ->
            fx.convert(q.amount, q.currency, s.displayCurrency)
                ?.let { q.copy(amount = it, currency = s.displayCurrency) } ?: q
        }
        return PriceResult(converted, links, errors)
    }

    private suspend fun tcgQuotes(source: PriceSourceConfig, card: CardDto, edition: EditionDto?): List<PriceQuote> {
        val rows = tcg.lookup(card.name, edition?.setPrefix, edition?.set?.name, edition?.collectorNumber)
        if (rows.isEmpty()) return emptyList()
        val top = tcg.score(rows.first(), edition?.setPrefix, edition?.set?.name, edition?.collectorNumber)
        return rows.take(MAX_ROWS).mapNotNull { row ->
            val amount = row.market ?: row.mid ?: row.low ?: return@mapNotNull null
            val detail = listOfNotNull(
                row.low?.let { "low ${"%.2f".format(Locale.US, it)}" },
                row.mid?.let { "mid ${"%.2f".format(Locale.US, it)}" },
                row.number,
            ).joinToString(" · ").ifBlank { null }
            PriceQuote(
                sourceName = source.name,
                label = listOfNotNull(row.groupName, row.subType).joinToString(" · "),
                amount = amount,
                currency = "USD",
                detail = detail,
                url = row.url,
                bestMatch = top > 0 && tcg.score(row, edition?.setPrefix, edition?.set?.name, edition?.collectorNumber) == top,
            )
        }
    }

    suspend fun refreshTcgplayer() = tcg.refresh()

    companion object {
        private const val MAX_ROWS = 8

        fun fillTemplate(template: String, card: CardDto, edition: EditionDto?): String? {
            if (template.isBlank()) return null
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val set = edition?.setPrefix.orEmpty()
            val number = edition?.collectorNumber.orEmpty()
            return template
                .replace("{name}", enc(card.name))
                .replace("{set}", enc(set))
                .replace("{number}", enc(number))
                .replace("{query}", enc(listOf(card.name, set).filter { it.isNotBlank() }.joinToString(" ")))
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
