package com.gascanner.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.room.Room
import com.gascanner.app.data.api.GatcgApi
import com.gascanner.app.data.db.AppDatabase
import com.gascanner.app.data.db.CollectionRepository
import com.gascanner.app.data.db.DeckRepository
import com.gascanner.app.data.prices.CurrencyConverter
import com.gascanner.app.data.prices.PriceService
import com.gascanner.app.data.prices.TcgCsvProvider
import com.gascanner.app.data.settings.SettingsRepository
import com.gascanner.app.scan.CardRecognizer
import com.gascanner.app.scan.ClaudeVisionRecognizer
import com.gascanner.app.scan.OnDeviceRecognizer
import com.gascanner.app.scan.ScanMode
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class GAScannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Very small manual dependency container. */
class AppContainer(context: Context) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val db: AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, "ga_scanner.db")
        .build()

    val gatcg = GatcgApi(http, json)
    val settings = SettingsRepository(context, json)
    val tcgPrices = TcgCsvProvider(http, json, context.cacheDir)
    val fx = CurrencyConverter(http, json)
    val prices = PriceService(settings, tcgPrices, fx)
    private val onDeviceRecognizer: CardRecognizer by lazy { OnDeviceRecognizer() }
    private val claudeRecognizer: CardRecognizer = ClaudeVisionRecognizer(http, json, settings)
    fun recognizer(mode: ScanMode): CardRecognizer =
        if (mode == ScanMode.CLAUDE) claudeRecognizer else onDeviceRecognizer
    val collection = CollectionRepository(db.collectionDao())
    val decks = DeckRepository(db.deckDao())
}

@Composable
fun appContainer(): AppContainer =
    (LocalContext.current.applicationContext as GAScannerApp).container
