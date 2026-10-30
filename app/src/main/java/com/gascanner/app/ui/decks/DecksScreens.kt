package com.gascanner.app.ui.decks

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import com.gascanner.app.AppContainer
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.GameFormat
import com.gascanner.app.data.db.Deck
import com.gascanner.app.data.db.DeckCard
import com.gascanner.app.data.db.DeckCheck
import com.gascanner.app.data.db.DeckIssue
import com.gascanner.app.data.db.DeckRepository
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.db.DeckValidator
import com.gascanner.app.data.db.DeckWithCount
import com.gascanner.app.data.prices.Money
import com.gascanner.app.data.prices.PricedItem
import com.gascanner.app.data.settings.ViewMode
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.i18n.Strings
import com.gascanner.app.ui.card.sectionLabel
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CardTile
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import com.gascanner.app.ui.common.ViewModeButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---------- Deck list ----------

class DecksViewModel(private val repo: DeckRepository) : ViewModel() {
    val decks: StateFlow<List<DeckWithCount>> =
        repo.decks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, fallback: String, then: (Long) -> Unit) {
        viewModelScope.launch { then(repo.createDeck(name.ifBlank { fallback })) }
    }
}

@Composable
fun DecksScreen(onOpenDeck: (Long) -> Unit) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: DecksViewModel = viewModel { DecksViewModel(container.decks) }
    val decks by vm.decks.collectAsStateWithLifecycle()
    var showNew by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = s.decksTitle,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(s.newDeck) },
            )
        },
    ) { padding ->
        if (decks.isEmpty()) {
            CenteredMessage(s.noDecks, Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(decks, key = { it.deck.id }) { d ->
                    Column(Modifier.fillMaxWidth().clickable { onOpenDeck(d.deck.id) }.padding(16.dp)) {
                        Text(d.deck.name, style = MaterialTheme.typography.titleMedium)
                        SmallNote(s.cardsCount(d.cardCount))
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNew) {
        NameDialog(
            title = s.newDeck,
            initial = "",
            onDismiss = { showNew = false },
            onConfirm = { name ->
                showNew = false
                vm.create(name, s.newDeck, onOpenDeck)
            },
        )
    }
}

// ---------- Deck detail ----------

class DeckDetailViewModel(private val deckId: Long, private val c: AppContainer) : ViewModel() {
    private val repo = c.decks
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T) =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val deck: StateFlow<Deck?> = repo.deck(deckId).state(null)
    val cards: StateFlow<List<DeckCard>> = repo.cards(deckId).state(emptyList())
    val viewMode: StateFlow<ViewMode> = c.settings.settings.map { it.viewMode }.state(ViewMode.LIST)
    val format: StateFlow<GameFormat> = c.settings.deckFormat(deckId)
        .map { runCatching { GameFormat.valueOf(it) }.getOrDefault(GameFormat.STANDARD) }
        .state(GameFormat.STANDARD)

    private val info = MutableStateFlow<Map<String, CardDto>>(emptyMap())
    val check: StateFlow<DeckCheck?> = combine(format, cards, info) { f, list, i -> DeckValidator.check(f, list, i) }.state(null)

    private val _value = MutableStateFlow<Money?>(null)
    val value: StateFlow<Money?> = _value.asStateFlow()

    init {
        viewModelScope.launch {
            cards.collectLatest { list ->
                val map = runCatching { c.cards.cardsBySlug(list.map { it.cardSlug }.toSet()) }.getOrDefault(emptyMap())
                info.value = map
                _value.value = deckValue(list, map)
            }
        }
    }

    private suspend fun deckValue(list: List<DeckCard>, map: Map<String, CardDto>): Money? {
        if (list.isEmpty()) return null
        val currency = c.prices.totalCurrency()
        val cached = c.prices.allCached()
        var total = 0.0
        for (dc in list) {
            val edition = map[dc.cardSlug]?.allEditions?.firstOrNull { it.uuid == dc.editionUuid }
            val item = PricedItem(dc.editionUuid, dc.cardName, dc.setPrefix, edition?.set?.name.orEmpty(), edition?.collectorNumber.orEmpty(), false)
            val m = runCatching { c.prices.estimate(item, cached) }.getOrNull() ?: continue
            val conv = if (m.currency == currency) m else c.prices.convert(m, currency) ?: continue
            total += conv.amount * dc.quantity
        }
        return if (total > 0) Money(total, currency) else null
    }

    fun setFormat(f: GameFormat) = viewModelScope.launch { c.settings.setDeckFormat(deckId, f.name) }
    fun setViewMode(m: ViewMode) = viewModelScope.launch { c.settings.setViewMode(m) }
    fun change(card: DeckCard, delta: Int) = viewModelScope.launch { repo.changeQuantity(card, delta) }
    fun rename(name: String) = viewModelScope.launch { deck.value?.let { repo.renameDeck(it, name) } }
    fun delete(then: () -> Unit) = viewModelScope.launch {
        deck.value?.let { repo.deleteDeck(it) }
        then()
    }
}

/** Plain-text decklist, grouped by section, for sharing. */
fun exportDecklist(s: Strings, name: String, cards: List<DeckCard>): String = buildString {
    appendLine(name)
    DeckSection.entries.forEach { section ->
        val inSection = cards.filter { it.section == section.name }
        if (inSection.isNotEmpty()) {
            appendLine()
            appendLine("${sectionLabel(s, section)} (${inSection.sumOf { it.quantity }})")
            inSection.groupBy { it.cardName }.forEach { (cardName, list) ->
                appendLine("${list.sumOf { it.quantity }} $cardName")
            }
        }
    }
}

fun issueText(s: Strings, issue: DeckIssue): String = when (issue) {
    is DeckIssue.MainTooSmall -> s.issueMainTooSmall(issue.count, issue.min)
    is DeckIssue.MaterialTooBig -> s.issueMaterialTooBig(issue.count, issue.max)
    is DeckIssue.NoLevelZeroChampion -> s.issueNoLevelZero
    is DeckIssue.TooManyCopies -> s.issueTooManyCopies(issue.cardName, issue.count, issue.max)
    is DeckIssue.MaterialDuplicate -> s.issueMaterialDuplicate(issue.cardName, issue.count)
    is DeckIssue.SideboardTooBig -> s.issueSideboard(issue.count, issue.points)
    is DeckIssue.Banned -> s.issueBanned(issue.cardName)
    is DeckIssue.OverLimit -> s.issueOverLimit(issue.cardName, issue.count, issue.limit)
    is DeckIssue.MainHasMaterial -> s.issueMainHasMaterial(issue.cardName)
    is DeckIssue.MaterialHasMain -> s.issueMaterialHasMain(issue.cardName)
    is DeckIssue.MaterialNotExact -> s.issueMaterialNotExact(issue.count, issue.required)
    is DeckIssue.NoSideboardAllowed -> s.issueNoSideboard(issue.count)
    is DeckIssue.BoonsNeeded -> s.issueBoons(issue.lesser, issue.greater)
    is DeckIssue.BoonsOnlyInPantheon -> s.issueBoonsOnlyPantheon(issue.count)
    is DeckIssue.BoonInWrongSection -> s.issueBoonWrongSection(issue.cardName)
}

@Composable
fun DeckDetailScreen(
    deckId: Long,
    onBack: () -> Unit,
    onOpenCard: (slug: String, editionUuid: String?) -> Unit,
    onAddCards: () -> Unit,
) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: DeckDetailViewModel = viewModel(key = "deck-$deckId") { DeckDetailViewModel(deckId, container) }
    val deck by vm.deck.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val format by vm.format.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val value by vm.value.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = deck?.name ?: s.decksTitle,
        onBack = onBack,
        actions = {
            ViewModeButton(viewMode, vm::setViewMode)
            IconButton(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, exportDecklist(s, deck?.name ?: s.decksTitle, cards))
                }
                context.startActivity(Intent.createChooser(intent, s.shareDecklist))
            }) { Icon(Icons.Default.Share, s.shareDecklist) }
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, s.more) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(s.renameDeck) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { menu = false; renaming = true })
                DropdownMenuItem(text = { Text(s.deleteDeck) }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menu = false; confirmDelete = true })
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddCards, icon = { Icon(Icons.Default.Add, null) }, text = { Text(s.addCards) })
        },
    ) { padding ->
        if (cards.isEmpty()) {
            CenteredMessage(s.deckEmpty, Modifier.padding(padding))
            return@ScreenScaffold
        }
        val sections = DeckSection.entries.map { sec -> sec to cards.filter { it.section == sec.name } }.filter { it.second.isNotEmpty() }
        val header: @Composable () -> Unit = {
            Column {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.format, style = MaterialTheme.typography.labelLarge)
                    listOf(GameFormat.STANDARD to s.formatStandard, GameFormat.PANTHEON to s.formatPantheon, GameFormat.DRAFT to s.formatDraft)
                        .forEach { (f, label) -> FilterChip(selected = format == f, onClick = { vm.setFormat(f) }, label = { Text(label) }) }
                }
                check?.let { CheckCard(it) }
                value?.let { SmallNote(s.deckValue(it.format()), Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
            }
        }
        if (viewMode == ViewMode.LIST) {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item(key = "header") { header() }
                sections.forEach { (section, inSection) ->
                    item(key = "h-${section.name}") { SectionTitle(sectionLabel(s, section), inSection.sumOf { it.quantity }) }
                    items(inSection, key = { it.id }) { card ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenCard(card.cardSlug, card.editionUuid) }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CardThumb(card.imageUrl, width = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(card.cardName, style = MaterialTheme.typography.bodyLarge)
                                SmallNote(card.setPrefix)
                            }
                            QuantityStepper(card.quantity, onChange = { vm.change(card, it) })
                        }
                    }
                }
                item { Spacer(Modifier.width(1.dp).padding(bottom = 80.dp)) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(viewMode.columns),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
                sections.forEach { (section, inSection) ->
                    item(key = "h-${section.name}", span = { GridItemSpan(maxLineSpan) }) {
                        SectionTitle(sectionLabel(s, section), inSection.sumOf { it.quantity })
                    }
                    items(inSection, key = { it.id }) { card ->
                        CardTile(
                            imageUrl = card.imageUrl,
                            title = card.cardName,
                            quantity = card.quantity,
                            foil = false,
                            price = null,
                            onClick = { onOpenCard(card.cardSlug, card.editionUuid) },
                        )
                    }
                }
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = s.renameDeck,
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
            title = { Text(s.deleteDeckQuestion) },
            text = { Text(s.deleteDeckBody(deck?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onBack)
                }) { Text(s.delete) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(s.cancel) } },
        )
    }
}

@Composable
private fun SectionTitle(label: String, count: Int) {
    Text(
        "$label · $count",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun CheckCard(check: DeckCheck) {
    val s = LocalStrings.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (check.legal) Icons.Default.CheckCircle else Icons.Default.Warning,
                    null,
                    tint = if (check.legal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (check.legal) s.deckLegal else s.deckProblems(check.issues.size), style = MaterialTheme.typography.titleSmall)
            }
            check.issues.forEach { Text("• " + issueText(s, it), style = MaterialTheme.typography.bodySmall) }
            if (check.unknownCards > 0) SmallNote(s.uncheckedCards(check.unknownCards))
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(s.name) })
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
