package com.gascanner.app.ui.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.api.GameFormat
import com.gascanner.app.data.api.asText
import com.gascanner.app.data.api.legalityLimit
import com.gascanner.app.data.db.CollectionEntry
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.db.DeckWithCount
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.data.prices.PriceResult
import com.gascanner.app.data.prices.PriceService
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.i18n.Strings
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.Loading
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote

private enum class AddDialog { COLLECTION, DECK, SELL, BUY }

@Composable
fun CardDetailScreen(slug: String, editionUuid: String?, onBack: () -> Unit) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: CardDetailViewModel = viewModel(key = "card-$slug") { CardDetailViewModel(slug, editionUuid, container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val owned by vm.owned.collectAsStateWithLifecycle()
    val sellCount by vm.sellCount.collectAsStateWithLifecycle()
    val buyCount by vm.buyCount.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<AddDialog?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(title = state.card?.name ?: s.cardTitle, onBack = onBack, snackbarHostState = snackbar) { padding ->
        val card = state.card
        when {
            state.loading -> Loading(Modifier.padding(padding))
            card == null -> CenteredMessage(state.error ?: s.cardNotFound, Modifier.padding(padding)) {
                TextButton(onClick = vm::load) { Text(s.retry) }
            }
            else -> CardDetailContent(
                card = card,
                state = state,
                owned = owned,
                sellCount = sellCount,
                buyCount = buyCount,
                padding = padding,
                onSelectEdition = vm::selectEdition,
                onAdd = { dialog = it },
                onChangeOwned = vm::changeOwned,
                onRefreshPrices = { vm.loadPrices(forceRefresh = true) },
                onFoilPrices = vm::setFoilPrices,
            )
        }
    }

    val edition = state.selectedEdition
    when (dialog) {
        AddDialog.COLLECTION, AddDialog.SELL, AddDialog.BUY -> if (edition != null) {
            val d = dialog
            AddCopiesDialog(
                title = when (d) {
                    AddDialog.SELL -> s.addToSellTitle
                    AddDialog.BUY -> s.addToBuyTitle
                    else -> s.addToCollectionTitle
                },
                edition = edition,
                onDismiss = { dialog = null },
                onConfirm = { qty, foil ->
                    when (d) {
                        AddDialog.SELL -> vm.addToList(TradeListType.SELL, qty, foil)
                        AddDialog.BUY -> vm.addToList(TradeListType.BUY, qty, foil)
                        else -> vm.addToCollection(qty, foil)
                    }
                    dialog = null
                },
            )
        }
        AddDialog.DECK -> AddToDeckDialog(
            decks = decks,
            onDismiss = { dialog = null },
            onConfirm = { deckId, newName, section, qty ->
                vm.addToDeck(deckId, newName, section, qty, sectionLabel(s, section).lowercase())
                dialog = null
            },
        )
        null -> Unit
    }
}

fun sectionLabel(s: Strings, section: DeckSection) = when (section) {
    DeckSection.MATERIAL -> s.sectionMaterial
    DeckSection.MAIN -> s.sectionMain
    DeckSection.SIDEBOARD -> s.sectionSideboard
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardDetailContent(
    card: CardDto,
    state: CardDetailState,
    owned: List<CollectionEntry>,
    sellCount: Int,
    buyCount: Int,
    padding: PaddingValues,
    onSelectEdition: (String) -> Unit,
    onAdd: (AddDialog) -> Unit,
    onChangeOwned: (CollectionEntry, Int) -> Unit,
    onRefreshPrices: () -> Unit,
    onFoilPrices: (Boolean) -> Unit,
) {
    val s = LocalStrings.current
    val edition = state.selectedEdition
    LazyColumn(
        Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = edition?.imageUrl,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .aspectRatio(0.716f)
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
        item {
            Text(card.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(card.elementLabel, card.classes.joinToString(" "), card.types.joinToString(" "), card.subtypes.joinToString(" "))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val stats = listOfNotNull(
                card.cost?.let { cost ->
                    cost.value.asText()?.let {
                        val label = when (cost.type?.lowercase()) {
                            "memory" -> s.costMemory
                            "reserve" -> s.costReserve
                            else -> s.cost
                        }
                        "$label $it"
                    }
                },
                card.level.asText()?.let { "${s.statLevel} $it" },
                card.power.asText()?.let { "${s.statPower} $it" },
                card.life.asText()?.let { "${s.statLife} $it" },
                card.durability.asText()?.let { "${s.statDurability} $it" },
                card.speed.asText()?.let { "${s.statSpeed} $it" },
            )
            if (stats.isNotEmpty()) Text(stats.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
        }
        val effect = edition?.effectRaw.asText() ?: card.effectRaw.asText()
        if (!effect.isNullOrBlank()) {
            item { Text(effect, style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            val parts = listOf(GameFormat.STANDARD to s.formatStandard, GameFormat.PANTHEON to s.formatPantheon).map { (f, label) ->
                val limit = card.legalityLimit(f)
                "$label: " + when (limit) {
                    null -> s.legal
                    0 -> s.banned
                    else -> s.limitedTo(limit)
                }
            }
            SmallNote("${s.legality} — " + parts.joinToString(" · "))
        }
        item {
            Text(s.editions, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(card.editions, key = { it.uuid }) { e ->
                    FilterChip(
                        selected = e.uuid == edition?.uuid,
                        onClick = { onSelectEdition(e.uuid) },
                        label = { Text(e.label.ifBlank { e.set?.name ?: "?" }) },
                    )
                }
            }
            edition?.set?.name?.let { SmallNote(it) }
        }
        item {
            val total = owned.sumOf { it.quantity }
            Text(if (total > 0) s.inCollection(total) else s.notInCollection, style = MaterialTheme.typography.bodyMedium)
            if (sellCount > 0) SmallNote(s.onSellList(sellCount))
            if (buyCount > 0) SmallNote(s.onBuyList(buyCount))
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onAdd(AddDialog.COLLECTION) }, enabled = edition != null) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.btnCollection)
                }
                OutlinedButton(onClick = { onAdd(AddDialog.DECK) }, enabled = edition != null) {
                    Icon(Icons.Default.Style, null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.btnDeck)
                }
                FilledTonalButton(onClick = { onAdd(AddDialog.SELL) }, enabled = edition != null) {
                    Icon(Icons.Default.Sell, null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.btnSell)
                }
                FilledTonalButton(onClick = { onAdd(AddDialog.BUY) }, enabled = edition != null) {
                    Icon(Icons.Default.ShoppingCart, null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.btnBuy)
                }
            }
        }
        if (owned.isNotEmpty()) {
            item {
                Text(s.yourCopies, style = MaterialTheme.typography.titleSmall)
                owned.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CardThumb(entry.imageUrl, width = 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${entry.setPrefix} ${entry.collectorNumber}".trim() + if (entry.foil) " · ${s.foil}" else "",
                            Modifier.weight(1f),
                        )
                        QuantityStepper(entry.quantity, onChange = { onChangeOwned(entry, it) })
                    }
                }
            }
        }
        item { HorizontalDivider() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.prices, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilterChip(selected = state.foilPrices, onClick = { onFoilPrices(!state.foilPrices) }, label = { Text(s.foil) })
                IconButton(onClick = onRefreshPrices) { Icon(Icons.Default.Refresh, s.refreshPrices) }
            }
            PricesSection(state.prices, state.pricesLoading)
        }
    }
}

@Composable
private fun PricesSection(prices: PriceResult?, loading: Boolean) {
    val s = LocalStrings.current
    val uri = LocalUriHandler.current
    if (loading && prices == null) {
        SmallNote(s.loadingPrices)
        Loading()
        return
    }
    if (prices == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (prices.quotes.isEmpty() && prices.errors.isEmpty()) SmallNote(s.noPrice)
        var lastSource: String? = null
        prices.quotes.forEach { q ->
            if (q.sourceId != lastSource) {
                lastSource = q.sourceId
                Text(
                    q.sourceName + if (q.isDefault) " · ${s.defaultBadge}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = q.url != null) { q.url?.let(uri::openUri) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(q.label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (q.bestMatch) FontWeight.SemiBold else FontWeight.Normal)
                    val detail = listOfNotNull(
                        q.detail,
                        if (q.bestMatch) s.matchesEdition else null,
                        if (q.inStock == false) s.outOfStock else null,
                    )
                    if (detail.isNotEmpty()) SmallNote(detail.joinToString(" · "))
                }
                Text(
                    PriceService.format(q.amount, q.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (q.bestMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        prices.errors.forEach { SmallNote(it, color = MaterialTheme.colorScheme.error) }
        if (prices.links.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(s.checkInStores, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                prices.links.forEach { link ->
                    AssistChip(
                        onClick = { uri.openUri(link.url) },
                        label = { Text(link.name) },
                        trailingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.width(16.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
fun AddCopiesDialog(
    title: String,
    edition: EditionDto,
    initialFoil: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Int, foil: Boolean) -> Unit,
) {
    val s = LocalStrings.current
    var qty by remember { mutableIntStateOf(1) }
    var foil by remember { mutableStateOf(initialFoil) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("${edition.label} · ${edition.set?.name.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.quantity, Modifier.weight(1f))
                    QuantityStepper(qty, onChange = { qty = (qty + it).coerceAtLeast(1) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.foil, Modifier.weight(1f))
                    Switch(checked = foil, onCheckedChange = { foil = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(qty, foil) }) { Text(s.add) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
fun AddToDeckDialog(
    decks: List<DeckWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (deckId: Long?, newDeckName: String?, section: DeckSection, quantity: Int) -> Unit,
) {
    val s = LocalStrings.current
    var selectedDeck by remember { mutableStateOf(decks.firstOrNull()?.deck?.id) }
    var newName by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(DeckSection.MAIN) }
    var qty by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.addToDeckTitle) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                decks.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { selectedDeck = d.deck.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedDeck == d.deck.id, onClick = { selectedDeck = d.deck.id })
                        Text("${d.deck.name} (${d.cardCount})")
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { selectedDeck = null }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedDeck == null, onClick = { selectedDeck = null })
                    Text(s.newDeck)
                }
                if (selectedDeck == null) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(s.deckName) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeckSection.entries.forEach { sec ->
                        FilterChip(
                            selected = section == sec,
                            onClick = { section = sec },
                            label = {
                                Text(
                                    when (sec) {
                                        DeckSection.MATERIAL -> s.sectionMaterialShort
                                        DeckSection.MAIN -> s.sectionMainShort
                                        DeckSection.SIDEBOARD -> s.sectionSideboardShort
                                    }
                                )
                            },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.quantity, Modifier.weight(1f))
                    QuantityStepper(qty, onChange = { qty = (qty + it).coerceAtLeast(1) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selectedDeck, newName, section, qty) }) { Text(s.add) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
