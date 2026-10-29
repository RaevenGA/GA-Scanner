package com.gascanner.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

    /** A store's search page, opened in the browser. Works for any shop. */
    SEARCH_LINK,
}

@Serializable
data class PriceSourceConfig(
    val id: String,
    val name: String,
    val kind: PriceSourceKind,
    val enabled: Boolean = true,
    /** For SEARCH_LINK: URL with {name}, {set}, {number} or {query} placeholders. */
    val urlTemplate: String = "",
    val builtIn: Boolean = false,
) {
    companion object {
        fun defaults(): List<PriceSourceConfig> = listOf(
            PriceSourceConfig(
                id = "tcgplayer-market",
                name = "TCGplayer market price",
                kind = PriceSourceKind.TCGPLAYER_MARKET,
                builtIn = true,
            ),
            PriceSourceConfig(
                id = "tcgplayer-search",
                name = "TCGplayer",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://www.tcgplayer.com/search/grand-archive/product?q={name}",
                builtIn = true,
            ),
            PriceSourceConfig(
                id = "cardmarket-search",
                name = "Cardmarket",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://www.cardmarket.com/en/Search?searchString={name}",
                builtIn = true,
            ),
            PriceSourceConfig(
                id = "ebay-search",
                name = "eBay",
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = "https://www.ebay.com/sch/i.html?_nkw=grand+archive+{query}",
                enabled = false,
                builtIn = true,
            ),
        )
    }
}

data class AppSettings(
    val scanMode: ScanMode = ScanMode.ON_DEVICE,
    val anthropicApiKey: String = "",
    val visionModel: String = DEFAULT_MODEL,
    /** "ORIGINAL" keeps each store's own currency; otherwise an ISO code such as PLN. */
    val displayCurrency: String = "ORIGINAL",
    val priceSources: List<PriceSourceConfig> = PriceSourceConfig.defaults(),
) {
    companion object {
        const val DEFAULT_MODEL = "claude-sonnet-5-5"
        val CURRENCIES = listOf("ORIGINAL", "PLN", "EUR", "USD", "GBP")
    }
}

class SettingsRepository(private val context: Context, private val json: Json) {

    private val sourcesSerializer = ListSerializer(PriceSourceConfig.serializer())

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            scanMode = p[KEY_SCAN_MODE]?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() } ?: ScanMode.ON_DEVICE,
            anthropicApiKey = p[KEY_API_KEY].orEmpty(),
            visionModel = p[KEY_MODEL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_MODEL,
            displayCurrency = p[KEY_CURRENCY] ?: "ORIGINAL",
            priceSources = p[KEY_SOURCES]
                ?.let { runCatching { json.decodeFromString(sourcesSerializer, it) }.getOrNull() }
                ?: PriceSourceConfig.defaults(),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setScanMode(mode: ScanMode) = context.settingsStore.edit { it[KEY_SCAN_MODE] = mode.name }
    suspend fun setApiKey(key: String) = context.settingsStore.edit { it[KEY_API_KEY] = key.trim() }
    suspend fun setModel(model: String) = context.settingsStore.edit { it[KEY_MODEL] = model.trim() }
    suspend fun setCurrency(code: String) = context.settingsStore.edit { it[KEY_CURRENCY] = code }

    suspend fun setSources(sources: List<PriceSourceConfig>) = context.settingsStore.edit {
        it[KEY_SOURCES] = json.encodeToString(sourcesSerializer, sources)
    }

    suspend fun resetSources() = setSources(PriceSourceConfig.defaults())

    private companion object {
        val KEY_SCAN_MODE = stringPreferencesKey("scan_mode")
        val KEY_API_KEY = stringPreferencesKey("anthropic_api_key")
        val KEY_MODEL = stringPreferencesKey("vision_model")
        val KEY_CURRENCY = stringPreferencesKey("display_currency")
        val KEY_SOURCES = stringPreferencesKey("price_sources")
    }
}
