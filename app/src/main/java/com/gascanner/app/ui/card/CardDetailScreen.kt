package com.gascanner.app.ui.card

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.api.asText
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.db.DeckWithCount
import com.gascanner.app.data.prices.PriceResult
import com.gascanner.app.data.prices.PriceService
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.Loading
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold

@Composable
fun CardDetailScreen(slug: String, editionUuid: String?, onBack: () -> Unit) {
    val container = appContainer()
    val vm: CardDetailViewModel = viewModel(key = "card-$slug") { CardDetailViewModel(slug, editionUuid, container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val owned by vm.ownedCount.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCollectionDialog by remember { mutableStateOf(false) }
    var showDeckDialog by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(title = state.card?.name ?: "Card", onBack = onBack, snackbarHostState = snackbar) { padding ->
        val card = state.card
        when {
            state.loading -> Loading(Modifier.padding(padding))
            card == null -> CenteredMessage(state.error ?: "Card not found", Modifier.padding(padding)) {
                TextButton(onClick = vm::load) { Text("Retry") }
            }
            else -> CardDetailContent(
                card = card,
                state = state,
                owned = owned,
                padding = padding,
                onSelectEdition = vm::selectEdition,
                onAddToCollection = { showCollectionDialog = true },
                onAddToDeck = { showDeckDialog = true },
                onRefreshPrices = { vm.loadPrices(forceRefresh = true) },
            )
        }
    }

    val edition = state.selectedEdition
    if (showCollectionDialog && edition != null) {
        AddToCollectionDialog(
            edition = edition,
            onDismiss = { showCollectionDialog = false },
            onConfirm = { qty, foil ->
                vm.addToCollection(qty, foil)
                showCollectionDialog = false
            },
        )
    }
    if (showDeckDialog) {
        AddToDeckDialog(
            decks = decks,
            onDismiss = { showDeckDialog = false },
            onConfirm = { deckId, newName, section, qty ->
                vm.addToDeck(deckId, newName, section, qty)
                showDeckDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDetailContent(
    card: CardDto,
    state: CardDetailState,
    owned: Int,
    padding: PaddingValues,
    onSelectEdition: (String) -> Unit,
    onAddToCollection: () -> Unit,
    onAddToDeck: () -> Unit,
    onRefreshPrices: () -> Unit,
) {
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
                card.cost?.let { cost -> cost.value.asText()?.let { "${cost.type?.replaceFirstChar { ch -> ch.uppercase() } ?: "Cost"} $it" } },
                card.level.asText()?.let { "Level $it" },
                card.power.asText()?.let { "Power $it" },
                card.life.asText()?.let { "Life $it" },
                card.durability.asText()?.let { "Durability $it" },
                card.speed.asText()?.let { "Speed $it" },
            )
            if (stats.isNotEmpty()) {
                Text(stats.joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val effect = edition?.effectRaw.asText() ?: card.effectRaw.asText()
        if (!effect.isNullOrBlank()) {
            item { Text(effect, style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            Text("Editions", style = MaterialTheme.typography.titleSmall)
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
            edition?.set?.name?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Text(
                if (owned > 0) "In your collection: $owned" else "Not in your collection yet",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAddToCollection, enabled = edition != null) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Collection")
                }
                OutlinedButton(onClick = onAddToDeck, enabled = edition != null) {
                    Icon(Icons.Default.Style, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Deck")
                }
            }
        }
        item { HorizontalDivider() }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Prices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefreshPrices) { Icon(Icons.Default.Refresh, "Refresh prices") }
            }
            PricesSection(state.prices, state.pricesLoading)
        }
    }
}

@Composable
private fun PricesSection(prices: PriceResult?, loading: Boolean) {
    val uri = LocalUriHandler.current
    if (loading && prices == null) {
        Text(
            "Loading prices… (the first TCGplayer download takes a moment)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Loading()
        return
    }
    if (prices == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (prices.quotes.isEmpty() && prices.errors.isEmpty()) {
            Text(
                "No listed market price for this card.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        prices.quotes.forEach { q ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = q.url != null) { q.url?.let(uri::openUri) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        q.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (q.bestMatch) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        listOfNotNull(q.sourceName, q.detail, if (q.bestMatch) "matches this edition" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    PriceService.format(q.amount, q.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (q.bestMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        prices.errors.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (prices.links.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Check in stores", style = MaterialTheme.typography.titleSmall)
            prices.links.forEach { link ->
                TextButton(onClick = { uri.openUri(link.url) }) {
                    Text(link.name, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                }
            }
        }
    }
}

@Composable
fun AddToCollectionDialog(
    edition: EditionDto,
    initialFoil: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (quantity: Int, foil: Boolean) -> Unit,
) {
    var qty by remember { mutableIntStateOf(1) }
    var foil by remember { mutableStateOf(initialFoil) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to collection") },
        text = {
            Column {
                Text("${edition.label} · ${edition.set?.name.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", Modifier.weight(1f))
                    QuantityStepper(qty, onChange = { qty = (qty + it).coerceAtLeast(1) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Foil", Modifier.weight(1f))
                    Switch(checked = foil, onCheckedChange = { foil = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(qty, foil) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToDeckDialog(
    decks: List<DeckWithCount>,
    onDismiss: () -> Unit,
    onConfirm: (deckId: Long?, newDeckName: String?, section: DeckSection, quantity: Int) -> Unit,
) {
    var selectedDeck by remember { mutableStateOf(decks.firstOrNull()?.deck?.id) }
    var newName by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(DeckSection.MAIN) }
    var qty by remember { mutableIntStateOf(1) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to deck") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                decks.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selectedDeck = d.deck.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedDeck == d.deck.id, onClick = { selectedDeck = d.deck.id })
                        Text("${d.deck.name} (${d.cardCount})")
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { selectedDeck = null },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selectedDeck == null, onClick = { selectedDeck = null })
                    Text("New deck")
                }
                if (selectedDeck == null) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Deck name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeckSection.entries.forEach { s ->
                        FilterChip(
                            selected = section == s,
                            onClick = { section = s },
                            label = { Text(s.label.substringBefore(" ")) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", Modifier.weight(1f))
                    QuantityStepper(qty, onChange = { qty = (qty + it).coerceAtLeast(1) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedDeck, newName, section, qty) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
