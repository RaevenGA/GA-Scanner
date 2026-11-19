package com.gascanner.app.ui.trade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gascanner.app.AppContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.db.CollectionEntry
import com.gascanner.app.data.deal.CodeLine
import com.gascanner.app.data.deal.DealCode
import com.gascanner.app.data.deal.DealItem
import com.gascanner.app.data.deal.DealSide
import com.gascanner.app.data.lists.TradeEntry
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.data.prices.Money
import com.gascanner.app.data.prices.PricedItem
import com.gascanner.app.data.settings.AddTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What "Complete trade" should do with the cards. */
data class CompleteOptions(
    val removeGivenFromCollection: Boolean = true,
    val removeGivenFromSell: Boolean = true,
    val addReceivedToCollection: Boolean = true,
    val removeReceivedFromBuy: Boolean = true,
)

/** A card that can be put into the trade from one of my lists. */
data class PickEntry(
    val key: String,
    val cardSlug: String,
    val cardName: String,
    val editionUuid: String,
    val setLine: String,
    val imageUrl: String?,
    val foil: Boolean,
    val quantity: Int,
)

class TradeViewModel(private val c: AppContainer) : ViewModel() {
    private val s get() = c.strings.value

    val items: StateFlow<List<DealItem>> = c.deal.items.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sellList: StateFlow<List<PickEntry>> = c.trade.observe(TradeListType.SELL).toPicks()
    val buyList: StateFlow<List<PickEntry>> = c.trade.observe(TradeListType.BUY).toPicks()
    val collection: StateFlow<List<PickEntry>> = c.collection.all.map { a -> a.map { it.toPick() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun kotlinx.coroutines.flow.Flow<List<TradeEntry>>.toPicks(): StateFlow<List<PickEntry>> =
        map { a -> a.map { it.toPick() } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _prices = MutableStateFlow<Map<Long, Money>>(emptyMap())
    val prices: StateFlow<Map<Long, Money>> = _prices.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    private val fetched = HashSet<String>()

    init {
        viewModelScope.launch {
            combine(c.deal.items, c.settings.settings) { list, _ -> list }.collectLatest { list ->
                _prices.value = priceMap(list)
                val missing = list.filter { it.id !in _prices.value && key(it) !in fetched }
                if (missing.isNotEmpty()) {
                    refresh(missing)
                    missing.forEach { fetched += key(it) }
                    _prices.value = priceMap(list)
                }
            }
        }
    }

    private fun key(i: DealItem) = "${i.editionUuid}|${i.foil}"
    private fun priced(i: DealItem) = PricedItem(i.editionUuid, i.cardName, i.setPrefix, i.setName, i.collectorNumber, i.foil)

    private suspend fun priceMap(list: List<DealItem>): Map<Long, Money> {
        val currency = c.prices.totalCurrency()
        val cached = c.prices.allCached()
        val out = HashMap<Long, Money>()
        for (i in list) {
            val m = runCatching { c.prices.estimate(priced(i), cached) }.getOrNull() ?: continue
            out[i.id] = if (m.currency == currency) m else c.prices.convert(m, currency) ?: continue
        }
        return out
    }

    private suspend fun refresh(list: List<DealItem>) {
        if (list.isEmpty()) return
        val items = list.map(::priced).distinctBy { "${it.editionUuid}|${it.foil}" }
        try {
            _busy.value = s.updatingPrices(0, items.size)
            c.prices.refreshDefault(items) { done, total -> _busy.value = s.updatingPrices(done, total) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            _busy.value = null
        }
    }

    fun updatePrices() {
        if (_busy.value != null) return
        viewModelScope.launch {
            val list = items.value
            refresh(list)
            list.forEach { fetched += key(it) }
            _prices.value = priceMap(list)
        }
    }

    private suspend fun resolve(editionUuid: String, cardSlug: String?): Pair<CardDto, EditionDto>? {
        runCatching { c.cards.byEditionUuid(editionUuid) }.getOrNull()?.let { hit -> hit.edition?.let { return hit.card to it } }
        val card = cardSlug?.let { runCatching { c.cards.card(it) }.getOrNull() } ?: return null
        return card.allEditions.firstOrNull { it.uuid == editionUuid }?.let { card to it }
    }

    /** One copy from one of my lists. */
    fun addPick(side: DealSide, p: PickEntry) = viewModelScope.launch {
        val (card, edition) = resolve(p.editionUuid, p.cardSlug) ?: return@launch _messages.emit(s.cardDataMissing(p.cardName))
        c.deal.add(side, card, edition, p.foil, 1)
    }

    fun addCard(side: DealSide, card: CardDto, edition: EditionDto, foil: Boolean, quantity: Int) =
        viewModelScope.launch { c.deal.add(side, card, edition, foil, quantity) }

    fun setQuantity(item: DealItem, q: Int) = viewModelScope.launch { c.deal.setQuantity(item, q) }

    fun edit(item: DealItem, card: CardDto, edition: EditionDto, foil: Boolean, quantity: Int) =
        viewModelScope.launch { c.deal.edit(item, card, edition, foil, quantity) }

    fun clear() = viewModelScope.launch { c.deal.clear() }

    /** The scanner's review screen will offer this side of the trade. */
    fun scanFor(side: DealSide) = viewModelScope.launch {
        c.settings.setScanTarget(if (side == DealSide.GIVE) AddTarget.TRADE_GIVE else AddTarget.TRADE_GET)
    }

    suspend fun search(query: String): List<CardDto> =
        runCatching { c.cards.search(query).cards }.getOrDefault(emptyList()).take(30)

    /** My side as QR text (one or more codes). */
    fun myCodes(): List<String> =
        DealCode.encode(items.value.filter { it.side == DealSide.GIVE.name }.map { CodeLine(it.editionUuid, it.foil, it.quantity) })

    /**
     * The other player's "I give" becomes my "I get". Replaces what was on my "I get" side,
     * so scanning an updated code doesn't count cards twice.
     */
    suspend fun importCode(lines: List<CodeLine>): Pair<Int, Int> {
        c.deal.clearSide(DealSide.GET)
        var added = 0
        var unknown = 0
        for (l in lines) {
            val hit = resolve(l.editionUuid, null)
            if (hit == null) {
                unknown += l.quantity
            } else {
                c.deal.add(DealSide.GET, hit.first, hit.second, l.foil, l.quantity)
                added += l.quantity
            }
        }
        return added to unknown
    }

    fun complete(o: CompleteOptions) = viewModelScope.launch {
        val give = items.value.filter { it.side == DealSide.GIVE.name }
        val get = items.value.filter { it.side == DealSide.GET.name }
        var notOwned = 0
        for (i in give) {
            if (o.removeGivenFromCollection) {
                val owned = c.collection.quantityOf(i.editionUuid, i.foil)
                val take = minOf(owned, i.quantity)
                if (take > 0) c.collection.removeOne(i.editionUuid, i.foil, take)
                notOwned += i.quantity - take
            }
            if (o.removeGivenFromSell) c.trade.removeCopies(TradeListType.SELL, i.cardSlug, i.editionUuid, i.foil, i.quantity)
        }
        var missing = 0
        for (i in get) {
            if (o.addReceivedToCollection) {
                val hit = resolve(i.editionUuid, i.cardSlug)
                if (hit == null) missing += i.quantity else c.collection.add(hit.first, hit.second, i.foil, i.quantity)
            }
            if (o.removeReceivedFromBuy) c.trade.removeCopies(TradeListType.BUY, i.cardSlug, i.editionUuid, i.foil, i.quantity)
        }
        c.deal.clear()
        _messages.emit(
            listOfNotNull(
                s.tradeCompleted(give.sumOf { it.quantity }, get.sumOf { it.quantity }),
                if (notOwned > 0) s.tradeNotOwned(notOwned) else null,
                if (missing > 0) s.notSent(missing) else null,
            ).joinToString(" ")
        )
    }

    private fun TradeEntry.toPick() = PickEntry(
        "t$id", cardSlug, cardName, editionUuid, "$setPrefix $collectorNumber".trim(), imageUrl, foil, quantity,
    )

    private fun CollectionEntry.toPick() = PickEntry(
        "c$id", cardSlug, cardName, editionUuid, "$setPrefix $collectorNumber".trim(), imageUrl, foil, quantity,
    )
}
