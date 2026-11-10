package com.gascanner.app.ui.decks

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.gascanner.app.data.db.CollectionEntry
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.ui.card.defaultSection
import com.gascanner.app.ui.card.sectionLabel
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A collection line with how many copies of that printing are already in the deck. */
data class PickRow(val entry: CollectionEntry, val inDeck: Int, val section: DeckSection?)

class FromCollectionViewModel(private val deckId: Long, private val c: AppContainer) : ViewModel() {
    private val s get() = c.strings.value
    val query = MutableStateFlow("")

    val rows: StateFlow<List<PickRow>> = combine(c.collection.all, c.decks.cards(deckId), query) { owned, deck, q ->
        val inDeck = deck.groupBy { it.editionUuid to it.foil }
        owned.filter { q.isBlank() || it.cardName.contains(q.trim(), ignoreCase = true) || it.setPrefix.contains(q.trim(), ignoreCase = true) }
            .map { e ->
                val lines = inDeck[e.editionUuid to e.foil].orEmpty()
                PickRow(e, lines.sumOf { it.quantity }, lines.firstOrNull()?.let { runCatching { DeckSection.valueOf(it.section) }.getOrNull() })
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val deckName: StateFlow<String?> = c.decks.decks.map { list -> list.firstOrNull { it.deck.id == deckId }?.deck?.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    /** One more copy of this printing in the deck (champions to material, Songs to the Song deck…). */
    fun addOne(row: PickRow) = viewModelScope.launch {
        val hit = runCatching { c.cards.byEditionUuid(row.entry.editionUuid) }.getOrNull()
        val card = hit?.card ?: runCatching { c.cards.card(row.entry.cardSlug) }.getOrNull() ?: return@launch
        val edition = hit?.edition ?: card.allEditions.firstOrNull { it.uuid == row.entry.editionUuid } ?: return@launch
        val section = row.section ?: defaultSection(card)
        c.decks.addCard(deckId, section, card, edition, 1, row.entry.foil)
        if (row.inDeck + 1 > row.entry.quantity) _messages.tryEmit(s.moreThanOwned(card.name, row.entry.quantity))
        else _messages.tryEmit(s.addedToDeck(1, card.name, sectionLabel(s, section).lowercase()))
    }

    fun removeOne(row: PickRow) = viewModelScope.launch {
        val section = row.section ?: return@launch
        c.decks.removeOne(deckId, section, row.entry.editionUuid, row.entry.foil)
    }
}

@Composable
fun FromCollectionScreen(deckId: Long, onBack: () -> Unit, onOpenCard: (String, String?) -> Unit) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: FromCollectionViewModel = viewModel(key = "from-collection-$deckId") { FromCollectionViewModel(deckId, container) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val deckName by vm.deckName.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(title = deckName ?: s.addFromCollection, onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                placeholder = { Text(s.searchCollection) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (rows.isEmpty()) {
                CenteredMessage(if (query.isBlank()) s.collectionEmpty else s.noCardsMatch)
                return@Column
            }
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.entry.id }) { row ->
                    val e = row.entry
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenCard(e.cardSlug, e.editionUuid) }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardThumb(e.imageUrl, width = 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.cardName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            SmallNote(
                                listOfNotNull("${e.setPrefix} ${e.collectorNumber}".trim(), if (e.foil) s.foil else null, s.ownedInDeck(e.quantity, row.inDeck))
                                    .joinToString(" · ")
                            )
                        }
                        QuantityStepper(row.inDeck, onChange = { if (it > 0) vm.addOne(row) else vm.removeOne(row) })
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
