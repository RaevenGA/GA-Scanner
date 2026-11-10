package com.gascanner.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.gascanner.app.data.api.GatcgApi
import com.gascanner.app.data.cache.CacheDatabase
import com.gascanner.app.data.cache.CardRepository
import com.gascanner.app.data.csv.ImportExport
import com.gascanner.app.data.db.AppDatabase
import com.gascanner.app.data.db.CollectionRepository
import com.gascanner.app.data.db.DeckRepository
import com.gascanner.app.data.lists.ListsDatabase
import com.gascanner.app.data.lists.TradeRepository
import com.gascanner.app.data.prices.CurrencyConverter
import com.gascanner.app.data.prices.PriceService
import com.gascanner.app.data.prices.StorePriceProvider
import com.gascanner.app.data.prices.TcgCsvProvider
import com.gascanner.app.data.settings.SettingsRepository
import com.gascanner.app.i18n.Languages
import com.gascanner.app.i18n.Strings
import com.gascanner.app.scan.ArtMatcher
import com.gascanner.app.scan.CardRecognizer
import com.gascanner.app.scan.CardResolver
import com.gascanner.app.scan.ClaudeVisionRecognizer
import com.gascanner.app.scan.OnDeviceRecognizer
import com.gascanner.app.scan.ScanMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class GAScannerApp : Application(), coil.ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val c = container
        c.appScope.launch {
            // Check the card database for updates on every start (downloads it all the first time),
            // then fetch images of any new cards if offline images are on.
            // Price matching changed (0.5.1 collector rarities, 0.5.2 sealed products): forget older prices.
            runCatching {
                if (c.settings.priceCacheVersion() < 2) {
                    c.cacheDb.prices().clear()
                    c.settings.setPriceCacheVersion(2)
                }
            }
            runCatching { c.cards.sync(full = false) }
            // Foil-only printings (collector rarities, promos…) saved as non-foil, and the reverse.
            runCatching {
                val lookup: suspend (String) -> com.gascanner.app.data.api.EditionDto? = { c.cards.byEditionUuid(it)?.edition }
                c.collection.fixFinishes(lookup)
                c.trade.fixFinishes(lookup)
                c.decks.fixFinishes(lookup)
            }
            if (c.settings.current().offlineImages) c.images.downloadAll(c.cards.allImageUrls())
        }
        c.appScope.launch { c.updates.check() }
    }

    /** Card images come from the saved offline copies whenever they exist. */
    override fun newImageLoader(): coil.ImageLoader =
        coil.ImageLoader.Builder(this)
            .components { add(com.gascanner.app.data.images.OfflineImageInterceptor(container.images)) }
            .crossfade(true)
            .build()
}

const val USER_AGENT = "GAScanner/${BuildConfig.VERSION_NAME} (Android; +https://github.com/RaevenGA/GA-Scanner)"

/** Very small manual dependency container. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        // Some services (e.g. tcgcsv.com) reject requests without an app-specific User-Agent.
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") != null) req else req.newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // User data: unchanged schema from version 0.1, so existing collections are kept.
    private val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "ga_scanner.db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()

    // Sell / buy lists.
    private val listsDb: ListsDatabase = Room.databaseBuilder(context, ListsDatabase::class.java, "ga_lists.db").build()

    // Re-downloadable data; safe to rebuild.
    val cacheDb: CacheDatabase = Room.databaseBuilder(context, CacheDatabase::class.java, "ga_cache.db")
        .fallbackToDestructiveMigration()
        .build()

    val settings = SettingsRepository(context, json)
    val gatcg = GatcgApi(http, json)
    val cards = CardRepository(gatcg, cacheDb, settings, json)

    val tcgPrices = TcgCsvProvider(http, json, context.cacheDir)
    val stores = StorePriceProvider(http, json)
    val fx = CurrencyConverter(http, json)
    val prices = PriceService(settings, tcgPrices, stores, fx, cacheDb.prices()) { uuid -> cards.byEditionUuid(uuid)?.edition }

    val onDevice: OnDeviceRecognizer by lazy { OnDeviceRecognizer() }
    private val claudeRecognizer: CardRecognizer = ClaudeVisionRecognizer(http, json, settings)
    fun recognizer(mode: ScanMode): CardRecognizer = if (mode == ScanMode.CLAUDE) claudeRecognizer else onDevice

    val art = ArtMatcher(http, cacheDb.artHashes())
    val resolver = CardResolver(cards, art)

    val collection = CollectionRepository(db.collectionDao())
    val decks = DeckRepository(db.deckDao())
    val trade = TradeRepository(listsDb.trade())

    // Scanned cards waiting to be reviewed.
    private val scanDb: com.gascanner.app.data.scan.ScanDatabase =
        Room.databaseBuilder(context, com.gascanner.app.data.scan.ScanDatabase::class.java, "ga_scan.db").build()
    val tray = com.gascanner.app.data.scan.ScanTray(scanDb.items())
    val importExport = ImportExport(cards, collection, trade)
    val images = com.gascanner.app.data.images.ImageStore(context, http)
    val updates = com.gascanner.app.data.update.UpdateChecker(http, json)

    /** Current UI language, for text produced outside composables (e.g. snackbar messages). */
    val strings: StateFlow<Strings> = settings.settings
        .map { Languages.strings(it.language) }
        .stateIn(appScope, SharingStarted.Eagerly, Languages.strings("system"))
}

@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as GAScannerApp).container
