package com.gascanner.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gascanner.app.scan.ScanMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
enum class PriceSourceKind {
    /** Live TCGplayer market prices, read from the free tcgcsv.com daily mirror. */
    TCGPLAYER_MARKET,

    /** A store: always a search link; optionally live prices read from the shop (see [StorePlatform]). */
    SEARCH_LINK,
}

/** How the app reads a shop's prices. AUTO tries Shopify, then WooCommerce. */
@Serializable
enum class StorePlatform { AUTO, SHOPIFY, WOOCOMMERCE, NONE }

@Serializable
data class PriceSourceConfig(
    val id: String,
    val name: String,
    val kind: PriceSourceKind,
    val enabled: Boolean = true,
    /** Search page with {name}, {set}, {number} or {query} placeholders. */
    val urlTemplate: String = "",
    val builtIn: Boolean = false,
    /** Read prices from the shop inside the app. */
    val fetchPrices: Boolean = false,
    /** Shop address, e.g. https://caduko.com. Derived from urlTemplate when blank. */
    val baseUrl: String = "",
    /** Currency the shop shows prices in (used when the shop doesn't say). */
    val currency: String = "EUR",
    val platform: StorePlatform = StorePlatform.AUTO,
) {
    val canFetch: Boolean get() = kind == PriceSourceKind.TCGPLAYER_MARKET || (fetchPrices && platform != StorePlatform.NONE)

    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank {
            Regex("^(https?://[^/]+)").find(urlTemplate)?.groupValues?.get(1).orEmpty()
        }.trimEnd('/')

    companion object {
        const val TCGPLAYER_MARKET_ID = "tcgplayer-market"

        fun defaults(): List<PriceSourceConfig> = listOf(
            PriceSourceConfig(
                id = TCGPLAYER_MARKET_ID,
                name = "TCGplayer market price",
                kind = PriceSourceKind.TCGPLAYER_MARKET,
                builtIn = true,
                currency = "USD",
            ),
            PriceSourceConfig(
                id = "caduko",
                name = "Caduko",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://caduko.com/?s={name}&post_type=product",
                baseUrl = "https://caduko.com",
                fetchPrices = true,
                currency = "EUR",
                platform = StorePlatform.WOOCOMMERCE,
                builtIn = true,
            ),
            PriceSourceConfig(
                id = "zarc",
                name = "Zarc",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://zarc.pl/search?q={name}",
                baseUrl = "https://zarc.pl",
                // The shop is behind Cloudflare bot protection, so prices can't be read by apps.
                fetchPrices = false,
                currency = "PLN",
                builtIn = true,
            ),
            PriceSourceConfig(
                id = "tcgplayer-search",
                name = "TCGplayer",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://www.tcgplayer.com/search/grand-archive/product?q={name}",
                currency = "USD",
                platform = StorePlatform.NONE,
                builtIn = true,
            ),
            PriceSourceConfig(
                id = "cardmarket-search",
                name = "Cardmarket",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://www.cardmarket.com/en/Search?searchString={name}",
                currency = "EUR",
                platform = StorePlatform.NONE,
                builtIn = true,
            ),
            PriceSourceConfig(
                id = "ebay-search",
                name = "eBay",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://www.ebay.com/sch/i.html?_nkw=grand+archive+{query}",
                enabled = false,
                currency = "USD",
                platform = StorePlatform.NONE,
                builtIn = true,
            ),
        )

        /** Keeps the user's edits, and adds built-in stores introduced by newer app versions. */
        fun mergeWithDefaults(saved: List<PriceSourceConfig>): List<PriceSourceConfig> {
            val ids = saved.map { it.id }.toSet()
            val missing = defaults().filter { it.id !in ids }
            if (missing.isEmpty()) return saved
            // New built-ins go right after the TCGplayer market source.
            val market = saved.indexOfFirst { it.id == TCGPLAYER_MARKET_ID }
            return if (market >= 0) saved.take(market + 1) + missing + saved.drop(market + 1) else missing + saved
        }
    }
}

enum class ViewMode(val columns: Int) { LIST(1), GRID2(2), GRID3(3) }

/** Where scanned cards are added. */
enum class AddTarget { COLLECTION, SELL, BUY }

data class AppSettings(
    val language: String = "system",
    val scanMode: ScanMode = ScanMode.ON_DEVICE,
    val anthropicApiKey: String = "",
    val visionModel: String = DEFAULT_MODEL,
    /** "ORIGINAL" keeps each store's own currency; otherwise an ISO code such as PLN. */
    val displayCurrency: String = "ORIGINAL",
    val priceSources: List<PriceSourceConfig> = PriceSourceConfig.defaults(),
    val defaultSourceId: String = PriceSourceConfig.TCGPLAYER_MARKET_ID,
    val viewMode: ViewMode = ViewMode.LIST,
    val liveAutoAdd: Boolean = false,
    val scanTarget: AddTarget = AddTarget.COLLECTION,
    val lastSyncAt: Long = 0L,
) {
    val defaultSource: PriceSourceConfig?
        get() = priceSources.firstOrNull { it.id == defaultSourceId && it.enabled }
            ?: priceSources.firstOrNull { it.enabled && it.canFetch }

    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val CURRENCIES = listOf("ORIGINAL", "PLN", "EUR", "USD", "GBP")
        val LANGUAGES = listOf("system", "en", "pl", "de", "fr", "es")
    }
}

class SettingsRepository(private val context: Context, private val json: Json) {

    private val sourcesSerializer = ListSerializer(PriceSourceConfig.serializer())
    private val store get() = context.settingsStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            language = p[KEY_LANGUAGE] ?: "system",
            scanMode = p[KEY_SCAN_MODE]?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() } ?: ScanMode.ON_DEVICE,
            anthropicApiKey = p[KEY_API_KEY].orEmpty(),
            visionModel = p[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_MODEL,
            displayCurrency = p[KEY_CURRENCY] ?: "ORIGINAL",
            priceSources = p[KEY_SOURCES]
                ?.let { runCatching { json.decodeFromString(sourcesSerializer, it) }.getOrNull() }
                ?.let(PriceSourceConfig::mergeWithDefaults)
                ?: PriceSourceConfig.defaults(),
            defaultSourceId = p[KEY_DEFAULT_SOURCE] ?: PriceSourceConfig.TCGPLAYER_MARKET_ID,
            viewMode = p[KEY_VIEW_MODE]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.LIST,
            liveAutoAdd = p[KEY_LIVE_AUTO_ADD] ?: false,
            scanTarget = p[KEY_SCAN_TARGET]?.let { runCatching { AddTarget.valueOf(it) }.getOrNull() } ?: AddTarget.COLLECTION,
            lastSyncAt = p[KEY_LAST_SYNC] ?: 0L,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLanguage(code: String) = store.edit { it[KEY_LANGUAGE] = code }
    suspend fun setScanMode(mode: ScanMode) = store.edit { it[KEY_SCAN_MODE] = mode.name }
    suspend fun setApiKey(key: String) = store.edit { it[KEY_API_KEY] = key.trim() }
    suspend fun setModel(model: String) = store.edit { it[KEY_MODEL] = model.trim() }
    suspend fun setCurrency(code: String) = store.edit { it[KEY_CURRENCY] = code }
    suspend fun setDefaultSource(id: String) = store.edit { it[KEY_DEFAULT_SOURCE] = id }
    suspend fun setViewMode(mode: ViewMode) = store.edit { it[KEY_VIEW_MODE] = mode.name }
    suspend fun setLiveAutoAdd(on: Boolean) = store.edit { it[KEY_LIVE_AUTO_ADD] = on }
    suspend fun setScanTarget(target: AddTarget) = store.edit { it[KEY_SCAN_TARGET] = target.name }
    suspend fun setLastSync(ms: Long) = store.edit { it[KEY_LAST_SYNC] = ms }

    suspend fun setSources(sources: List<PriceSourceConfig>) = store.edit {
        it[KEY_SOURCES] = json.encodeToString(sourcesSerializer, sources)
    }

    suspend fun resetSources() = setSources(PriceSourceConfig.defaults())

    /** Format chosen for a deck's legality check. */
    fun deckFormat(deckId: Long): Flow<String> = store.data.map { it[stringPreferencesKey("deck_format_$deckId")] ?: "STANDARD" }
    suspend fun setDeckFormat(deckId: Long, format: String) = store.edit { it[stringPreferencesKey("deck_format_$deckId")] = format }

    private companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_SCAN_MODE = stringPreferencesKey("scan_mode")
        val KEY_API_KEY = stringPreferencesKey("anthropic_api_key")
        val KEY_MODEL = stringPreferencesKey("vision_model")
        val KEY_CURRENCY = stringPreferencesKey("display_currency")
        val KEY_SOURCES = stringPreferencesKey("price_sources")
        val KEY_DEFAULT_SOURCE = stringPreferencesKey("default_source")
        val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        val KEY_LIVE_AUTO_ADD = booleanPreferencesKey("live_auto_add")
        val KEY_SCAN_TARGET = stringPreferencesKey("scan_target")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_at")
    }
}
