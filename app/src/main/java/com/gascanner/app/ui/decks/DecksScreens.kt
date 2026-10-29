package com.gascanner.app.ui.decks

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.appContainer
import com.gascanner.app.data.db.Deck
import com.gascanner.app.data.db.DeckCard
import com.gascanner.app.data.db.DeckRepository
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.db.DeckWithCount
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------- Deck list ----------

class DecksViewModel(private val repo: DeckRepository) : ViewModel() {
    val decks: StateFlow<List<DeckWithCount>> =
        repo.decks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, then: (Long) -> Unit) {
        viewModelScope.launch { then(repo.createDeck(name.ifBlank { "New deck" })) }
    }
}

@Composable
fun DecksScreen(onOpenDeck: (Long) -> Unit) {
    val container = appContainer()
    val vm: DecksViewModel = viewModel { DecksViewModel(container.decks) }
    val decks by vm.decks.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "Decks",
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New deck") },
            )
        },
    ) { padding ->
        if (decks.isEmpty()) {
            CenteredMessage("No decks yet. Create one, then add cards from a card's page.", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(decks, key = { it.deck.id }) { d ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenDeck(d.deck.id) }.padding(16.dp)
                    ) {
                        Text(d.deck.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${d.cardCount} cards",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNew) {
        NameDialog(
            title = "New deck",
            initial = "",
            onDismiss = { showNew = false },
            onConfirm = { name ->
                showNew = false
                vm.create(name, onOpenDeck)
            },
        )
    }
}

// ---------- Deck detail ----------

class DeckDetailViewModel(private val deckId: Long, private val repo: DeckRepository) : ViewModel() {
    val deck: StateFlow<Deck?> = repo.deck(deckId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val cards: StateFlow<List<DeckCard>> =
        repo.cards(deckId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun change(card: DeckCard, delta: Int) = viewModelScope.launch { repo.changeQuantity(card, delta) }
    fun rename(name: String) = viewModelScope.launch { deck.value?.let { repo.renameDeck(it, name) } }
    fun delete(then: () -> Unit) = viewModelScope.launch {
        deck.value?.let { repo.deleteDeck(it) }
        then()
    }
}

/** Plain-text decklist, grouped by section, for sharing. */
fun exportDecklist(name: String, cards: List<DeckCard>): String = buildString {
    appendLine(name)
    DeckSection.entries.forEach { section ->
        val inSection = cards.filter { it.section == section.name }
        if (inSection.isNotEmpty()) {
            appendLine()
            appendLine("${section.label} (${inSection.sumOf { it.quantity }})")
            inSection.groupBy { it.cardName }.forEach { (cardName, list) ->
                appendLine("${list.sumOf { it.quantity }} $cardName")
            }
        }
    }
}

@Composable
fun DeckDetailScreen(
    deckId: Long,
    onBack: () -> Unit,
    onOpenCard: (slug: String, editionUuid: String?) -> Unit,
    onAddCards: () -> Unit,
) {
    val container = appContainer()
    val vm: DeckDetailViewModel = viewModel(key = "deck-$deckId") { DeckDetailViewModel(deckId, container.decks) }
    val deck by vm.deck.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = deck?.name ?: "Deck",
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                val text = exportDecklist(deck?.name ?: "Deck", cards)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(intent, "Share decklist"))
            }) { Icon(Icons.Default.Share, "Share decklist") }
            IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rename") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete deck") }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCards,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add cards") },
            )
        },
    ) { padding ->
        if (cards.isEmpty()) {
            CenteredMessage(
                "This deck is empty. Find a card in search or scan one, then tap \"Deck\" on its page.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                DeckSection.entries.forEach { section ->
                    val inSection = cards.filter { it.section == section.name }
                    if (inSection.isNotEmpty()) {
                        item(key = "h-${section.name}") {
                            Text(
                                "${section.label} · ${inSection.sumOf { it.quantity }}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(inSection, key = { it.id }) { card ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenCard(card.cardSlug, card.editionUuid) }
                                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CardThumb(card.imageUrl, width = 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(card.cardName, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        card.setPrefix,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                QuantityStepper(card.quantity, onChange = { vm.change(card, it) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Rename deck",
            initial = deck?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = {
                renaming = false
                vm.rename(it)
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete deck?") },
            text = { Text("\"${deck?.name}\" and its card list will be removed. Your collection is not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
