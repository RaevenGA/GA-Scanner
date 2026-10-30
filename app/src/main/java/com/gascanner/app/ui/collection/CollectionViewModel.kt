package com.gascanner.app.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gascanner.app.AppContainer
import com.gascanner.app.data.db.CollectionEntry
import com.gascanner.app.data.lists.TradeEntry
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.data.prices.Money
import com.gascanner.app.data.prices.PricedItem
import com.gascanner.app.data.settings.AddTarget
import com.gascanner.app.data.settings.ViewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ListTab(val target: AddTarget) { OWNED(AddTarget.COLLECTION), SELL(AddTarget.SELL), BUY(AddTarget.BUY) }

/** Values keyed by "editionUuid|foil", per copy, in [currency]. */
data class Valuation(val currency: String = "EUR", val perCopy: Map<String, Money> = emptyMap())

fun priceKey(editionUuid: String, foil: Boolean) = "$editionUuid|$foil"

@OptIn(FlowPreview::class)
class CollectionViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T) =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val tab = MutableStateFlow(ListTab.OWNED)
    val viewMode: StateFlow<ViewMode> = c.settings.settings.map { it.viewMode }.state(ViewMode.LIST)

    val owned: StateFlow<List<CollectionEntry>> = c.collection.all.state(emptyList())
    val sell: StateFlow<List<TradeEntry>> = c.trade.observe(TradeListType.SELL).state(emptyList())
    val buy: StateFlow<List<TradeEntry>> = c.trade.observe(TradeListType.BUY).state(emptyList())

    private val _valuation = MutableStateFlow(Valuation())
    val valuation: StateFlow<Valuation> = _valuation.asStateFlow()

    /** Progress text for long jobs (price update, import); null when idle. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _importFailures = MutableStateFlow<List<String>>(emptyList())
    val importFailures: StateFlow<List<String>> = _importFailures.asStateFlow()

    private val s get() = c.strings.value

    init {
        viewModelScope.launch {
            combine(owned, sell, buy, c.settings.settings) { o, sl, b, _ -> Triple(o, sl, b) }
                .debounce(300)
                .collectLatest { (o, sl, b) -> recompute(items(o, sl, b)) }
        }
    }

    private fun items(o: List<CollectionEntry>, sl: List<TradeEntry>, b: List<TradeEntry>): List<PricedItem> =
        (o.map { PricedItem(it.editionUuid, it.cardName, it.setPrefix, it.setName, it.collectorNumber, it.foil) } +
            (sl + b).map { PricedItem(it.editionUuid, it.cardName, it.setPrefix, it.setName, it.collectorNumber, it.foil) })
            .distinctBy { priceKey(it.editionUuid, it.foil) }

    private suspend fun recompute(items: List<PricedItem>) {
        if (items.isEmpty()) return
        val currency = c.prices.totalCurrency()
        val cached = c.prices.allCached()
        val out = HashMap<String, Money>()
        for (item in items) {
            val m = runCatching { c.prices.estimate(item, cached) }.getOrNull() ?: continue
            val converted = if (m.currency == currency) m else c.prices.convert(m, currency) ?: continue
            out[priceKey(item.editionUuid, item.foil)] = converted
        }
        _valuation.value = Valuation(currency, out)
    }

    fun setViewMode(mode: ViewMode) = viewModelScope.launch { c.settings.setViewMode(mode) }

    fun updatePrices() {
        if (_busy.value != null) return
        val list = when (tab.value) {
            ListTab.OWNED -> owned.value.map { PricedItem(it.editionUuid, it.cardName, it.setPrefix, it.setName, it.collectorNumber, it.foil) }
            ListTab.SELL -> sell.value.map { PricedItem(it.editionUuid, it.cardName, it.setPrefix, it.setName, it.collectorNumber, it.foil) }
            ListTab.BUY -> buy.value.map { PricedItem(it.editionUuid, it.cardName, it.setPrefix, it.setName, it.collectorNumber, it.foil) }
        }.distinctBy { priceKey(it.editionUuid, it.foil) }
        viewModelScope.launch {
            try {
                _busy.value = s.updatingPrices(0, list.size)
                c.prices.refreshDefault(list) { done, total -> _busy.value = s.updatingPrices(done, total) }
                recompute(items(owned.value, sell.value, buy.value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(s.updateFailed(e.message ?: ""))
            } finally {
                _busy.value = null
            }
        }
    }

    fun changeOwned(entry: CollectionEntry, delta: Int) = viewModelScope.launch { c.collection.changeQuantity(entry, delta) }
    fun changeTrade(entry: TradeEntry, delta: Int) = viewModelScope.launch { c.trade.changeQuantity(entry, delta) }
    fun setTradePrice(entry: TradeEntry, price: Double?, currency: String?, note: String) =
        viewModelScope.launch { c.trade.setPrice(entry, price, currency, note) }

    fun exportCsv(): String = when (tab.value) {
        ListTab.OWNED -> c.importExport.collectionCsv(owned.value)
        ListTab.SELL -> c.importExport.tradeCsv(sell.value)
        ListTab.BUY -> c.importExport.tradeCsv(buy.value)
    }

    fun exportFileName(): String = when (tab.value) {
        ListTab.OWNED -> "ga-collection.csv"
        ListTab.SELL -> "ga-want-to-sell.csv"
        ListTab.BUY -> "ga-want-to-buy.csv"
    }

    fun import(text: String, replace: Boolean) {
        val target = tab.value.target
        viewModelScope.launch {
            try {
                _busy.value = s.importing(0, 0)
                val r = c.importExport.import(text, target, replace) { d, t -> _busy.value = s.importing(d, t) }
                _importFailures.value = r.failed
                _messages.tryEmit(s.importDone(r.added, r.failed.size))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(s.updateFailed(e.message ?: ""))
            } finally {
                _busy.value = null
            }
        }
    }

    fun dismissImportFailures() {
        _importFailures.value = emptyList()
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    /** Text for Messenger / Discord / Facebook groups. */
    fun shareText(type: TradeListType): String {
        val entries = if (type == TradeListType.SELL) sell.value else buy.value
        val v = valuation.value
        var total = 0.0
        var totalKnown = true
        val lines = entries.map { e ->
            val own = e.price?.let { p -> Money(p, e.currency ?: v.currency) }
            val est = v.perCopy[priceKey(e.editionUuid, e.foil)]
            val price = when {
                own != null -> own.format()
                est != null -> "≈ " + est.format()
                else -> null
            }
            val each = if (own != null && own.currency == v.currency) own.amount else est?.amount
            if (each != null) total += each * e.quantity else totalKnown = false
            buildString {
                append("${e.quantity}× ${e.cardName} (${listOf("${e.setPrefix} ${e.collectorNumber}".trim(), if (e.foil) "foil" else "").filter { it.isNotBlank() }.joinToString(", ")})")
                if (price != null) append(" — $price")
                if (e.note.isNotBlank()) append(" — ${e.note}")
            }
        }
        return buildString {
            appendLine(if (type == TradeListType.SELL) s.shareSellHeader else s.shareBuyHeader)
            appendLine()
            lines.forEach { appendLine(it) }
            if (total > 0) {
                appendLine()
                appendLine(s.shareTotal((if (totalKnown) "" else "≥ ") + Money(total, v.currency).format()))
            }
            appendLine()
            append(s.shareFooter)
        }
    }
}
