package com.gascanner.app.ui.scan

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.AppContainer
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.db.DeckWithCount
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.data.prices.Money
import com.gascanner.app.data.prices.PricedItem
import com.gascanner.app.data.scan.ScanItem
import com.gascanner.app.data.settings.AddTarget
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.i18n.Strings
import com.gascanner.app.ui.card.defaultSection
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.EditEntryDialog
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the scanned cards can go. */
data class ReviewTarget(val target: AddTarget, val deckId: Long, val deckName: String?)

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val s get() = c.strings.value

    val items: StateFlow<List<ScanItem>> = c.tray.items.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val decks: StateFlow<List<DeckWithCount>> = c.decks.decks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Defaults to the last choice; "Scan cards" in a deck sets that deck. */
    val target: StateFlow<ReviewTarget> = combine(c.settings.settings, c.decks.decks) { st, decks ->
        ReviewTarget(st.scanTarget, st.scanDeckId, decks.firstOrNull { it.deck.id == st.scanDeckId }?.deck?.name)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReviewTarget(AddTarget.COLLECTION, 0, null))

    private val _prices = MutableStateFlow<Map<Long, Money>>(emptyMap())
    val prices: StateFlow<Map<Long, Money>> = _prices.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    /** Fires after the cards were sent, to go back to the camera. */
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done: SharedFlow<Unit> = _done

    /** Lines whose price was already looked up in the default shop. */
    private val fetched = HashSet<String>()

    init {
        viewModelScope.launch {
            combine(c.tray.items, c.settings.settings) { list, _ -> list }.collectLatest { list ->
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

    private fun key(i: ScanItem) = "${i.editionUuid}|${i.foil}"
    private fun priced(i: ScanItem) = PricedItem(i.editionUuid, i.cardName, i.setPrefix, i.setName, i.collectorNumber, i.foil)

    private suspend fun priceMap(list: List<ScanItem>): Map<Long, Money> {
        val currency = c.prices.totalCurrency()
        val cached = c.prices.allCached()
        val out = HashMap<Long, Money>()
        for (i in list) {
            val m = runCatching { c.prices.estimate(priced(i), cached) }.getOrNull() ?: continue
            out[i.id] = if (m.currency == currency) m else c.prices.convert(m, currency) ?: continue
        }
        return out
    }

    private suspend fun refresh(list: List<ScanItem>) {
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

    fun setTarget(t: AddTarget) = viewModelScope.launch { c.settings.setScanTarget(t) }

    fun selectDeck(id: Long) = viewModelScope.launch {
        c.settings.setScanDeck(id)
        c.settings.setScanTarget(AddTarget.DECK)
    }

    fun createDeck(name: String) = viewModelScope.launch { selectDeck(c.decks.createDeck(name.ifBlank { s.newDeck })).join() }

    fun setQuantity(item: ScanItem, quantity: Int) = viewModelScope.launch { c.tray.setQuantity(item, quantity) }

    fun edit(item: ScanItem, card: CardDto, edition: EditionDto, foil: Boolean, quantity: Int) =
        viewModelScope.launch { c.tray.edit(item, card, edition, foil, quantity) }

    fun clear() = viewModelScope.launch { c.tray.clear() }

    private suspend fun resolve(i: ScanItem): Pair<CardDto, EditionDto>? {
        runCatching { c.cards.byEditionUuid(i.editionUuid) }.getOrNull()?.let { hit ->
            hit.edition?.let { return hit.card to it }
        }
        val card = runCatching { c.cards.card(i.cardSlug) }.getOrNull() ?: return null
        val edition = card.allEditions.firstOrNull { it.uuid == i.editionUuid } ?: return null
        return card to edition
    }

    /**
     * Sends every scanned card to the chosen place, and optionally to the collection too
     * (for the sell list and decks). The list is emptied afterwards.
     */
    fun send(alsoCollection: Boolean) = viewModelScope.launch {
        val t = target.value
        val list = items.value
        if (list.isEmpty()) return@launch
        val deckId = if (t.target == AddTarget.DECK) ensureDeck(t.deckId) else 0L
        var sent = 0
        var failed = 0
        for (i in list) {
            val (card, edition) = resolve(i) ?: run { failed += i.quantity; null } ?: continue
            when (t.target) {
                AddTarget.COLLECTION -> c.collection.add(card, edition, i.foil, i.quantity)
                AddTarget.SELL -> c.trade.add(TradeListType.SELL, card, edition, i.foil, i.quantity)
                AddTarget.BUY -> c.trade.add(TradeListType.BUY, card, edition, i.foil, i.quantity)
                AddTarget.DECK -> c.decks.addCard(deckId, defaultSection(card), card, edition, i.quantity, i.foil)
            }
            if (alsoCollection && t.target != AddTarget.COLLECTION) c.collection.add(card, edition, i.foil, i.quantity)
            c.tray.setQuantity(i, 0)
            sent += i.quantity
        }
        // Scanning for a deck ends here; the next scan defaults to the collection again.
        if (t.target == AddTarget.DECK) c.settings.setScanTarget(AddTarget.COLLECTION)
        val where = targetName(s, t.copy(deckName = decks.value.firstOrNull { it.deck.id == deckId }?.deck?.name ?: t.deckName))
        _messages.tryEmit(
            s.sentScanned(sent, where) + (if (alsoCollection && t.target != AddTarget.COLLECTION) " " + s.alsoInCollection else "") +
                (if (failed > 0) " " + s.notSent(failed) else "")
        )
        if (failed == 0) _done.tryEmit(Unit)
    }

    /** The chosen deck, or a new one if none is chosen (or it was deleted). */
    private suspend fun ensureDeck(id: Long): Long {
        if (id != 0L && c.decks.decks.first().any { it.deck.id == id }) return id
        val created = c.decks.createDeck(s.newDeck)
        c.settings.setScanDeck(created)
        return created
    }
}

fun targetName(s: Strings, t: ReviewTarget): String = when (t.target) {
    AddTarget.COLLECTION -> s.targetCollection
    AddTarget.SELL -> s.targetSell
    AddTarget.BUY -> s.targetBuy
    AddTarget.DECK -> t.deckName?.let { "${s.targetDeck}: $it" } ?: s.targetDeck
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, onOpenCard: (String, String?) -> Unit) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: ReviewViewModel = viewModel { ReviewViewModel(container) }
    val items by vm.items.collectAsStateWithLifecycle()
    val prices by vm.prices.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val target by vm.target.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ScanItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var askCollection by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.done.collect { onBack() } }

    ScreenScaffold(
        title = s.scannedTitle,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = vm::updatePrices, enabled = items.isNotEmpty()) { Icon(Icons.Default.Refresh, s.updatePrices) }
            IconButton(onClick = { confirmClear = true }, enabled = items.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, s.clear) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (items.isEmpty()) {
                Box(Modifier.weight(1f)) { CenteredMessage(s.scannedEmpty) }
                return@Column
            }
            val copies = items.sumOf { it.quantity }
            val pricedCopies = items.filter { it.id in prices }.sumOf { it.quantity }
            val total = items.mapNotNull { i -> prices[i.id]?.let { it.amount * i.quantity } }.sum()
            val currency = prices.values.firstOrNull()?.currency
            SmallNote(
                listOfNotNull(
                    s.cardsCount(copies),
                    if (currency != null && total > 0) "≈ " + Money(total, currency).format() else null,
                    if (pricedCopies < copies) s.pricedOf(pricedCopies, copies) else null,
                ).joinToString(" · "),
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            busy?.let { SmallNote(it, Modifier.padding(horizontal = 16.dp)) }

            LazyColumn(Modifier.weight(1f)) {
                items(items, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onClick = { onOpenCard(item.cardSlug, item.editionUuid) }, onLongClick = { editing = item })
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardThumb(item.imageUrl, width = 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.cardName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            SmallNote(
                                listOfNotNull(
                                    "${item.setPrefix} ${item.collectorNumber}".trim(),
                                    if (item.foil) s.foil else null,
                                    prices[item.id]?.format(),
                                ).joinToString(" · ")
                            )
                        }
                        IconButton(onClick = { editing = item }) { Icon(Icons.Default.Edit, s.edit) }
                        QuantityStepper(item.quantity, onChange = { vm.setQuantity(item, item.quantity + it) })
                    }
                    HorizontalDivider()
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    TargetChips(target, decks, onTarget = vm::setTarget, onSelectDeck = { vm.selectDeck(it) }, onCreateDeck = { vm.createDeck(it) })
                    Button(
                        onClick = {
                            if (target.target == AddTarget.SELL || target.target == AddTarget.DECK) askCollection = true
                            else vm.send(alsoCollection = false)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text(s.sendScanned(copies, targetName(s, target))) }
                }
            }
        }
    }

    editing?.let { item ->
        EditEntryDialog(
            cardSlug = item.cardSlug,
            cardName = item.cardName,
            editionUuid = item.editionUuid,
            foil = item.foil,
            quantity = item.quantity,
            section = null,
            priceFields = null,
            onDismiss = { editing = null },
            onSave = { e ->
                editing = null
                vm.edit(item, e.card, e.edition, e.foil, e.quantity)
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(s.clearScannedTitle) },
            text = { Text(s.clearScannedText) },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text(s.clear) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(s.cancel) } },
        )
    }
    if (askCollection) {
        AlertDialog(
            onDismissRequest = { askCollection = false },
            title = { Text(s.alsoCollectionTitle) },
            text = { Text(s.alsoCollectionText(targetName(s, target))) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { askCollection = false; vm.send(alsoCollection = true) }) { Text(s.alsoCollectionYes) }
                    TextButton(onClick = { askCollection = false; vm.send(alsoCollection = false) }) { Text(s.alsoCollectionNo(targetName(s, target))) }
                    TextButton(onClick = { askCollection = false }) { Text(s.cancel) }
                }
            },
        )
    }
}

@Composable
private fun TargetChips(
    target: ReviewTarget,
    decks: List<DeckWithCount>,
    onTarget: (AddTarget) -> Unit,
    onSelectDeck: (Long) -> Unit,
    onCreateDeck: (String) -> Unit,
) {
    val s = LocalStrings.current
    var deckMenu by remember { mutableStateOf(false) }
    var newDeckDialog by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(s.addTo, style = MaterialTheme.typography.labelLarge)
        listOf(AddTarget.COLLECTION to s.targetCollection, AddTarget.SELL to s.targetSell, AddTarget.BUY to s.targetBuy).forEach { (t, label) ->
            FilterChip(selected = target.target == t, onClick = { onTarget(t) }, label = { Text(label) })
        }
        Box {
            FilterChip(
                selected = target.target == AddTarget.DECK,
                onClick = { deckMenu = true },
                label = {
                    Text(if (target.target == AddTarget.DECK && target.deckName != null) "${s.targetDeck}: ${target.deckName}" else s.targetDeck, maxLines = 1)
                },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            )
            DropdownMenu(expanded = deckMenu, onDismissRequest = { deckMenu = false }) {
                decks.forEach { d ->
                    DropdownMenuItem(text = { Text("${d.deck.name} (${d.cardCount})") }, onClick = { deckMenu = false; onSelectDeck(d.deck.id) })
                }
                DropdownMenuItem(text = { Text(s.newDeckEllipsis) }, onClick = { deckMenu = false; newDeckDialog = true })
            }
        }
    }
    if (newDeckDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newDeckDialog = false },
            title = { Text(s.newDeck) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(s.deckName) }) },
            confirmButton = { TextButton(onClick = { newDeckDialog = false; onCreateDeck(name) }) { Text(s.save) } },
            dismissButton = { TextButton(onClick = { newDeckDialog = false }) { Text(s.cancel) } },
        )
    }
}
