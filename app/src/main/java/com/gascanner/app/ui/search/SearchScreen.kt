package com.gascanner.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.AppContainer
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.cache.SetInfo
import com.gascanner.app.data.cache.SyncState
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.Loading
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One result row: the card, plus the printing to show (the chosen set's, when filtering by set). */
data class ResultRow(val card: CardDto, val edition: EditionDto?)

data class Filters(
    val query: String = "",
    val set: SetInfo? = null,
    val element: String? = null,
    val type: String? = null,
    val notOwned: Boolean = false,
) {
    val isEmpty: Boolean get() = query.isBlank() && set == null && element == null && type == null
}

data class SearchState(
    val results: List<ResultRow> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
    /** True when keyword/set filters aren't available yet (offline database still downloading). */
    val onlineOnly: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters.asStateFlow()

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _sets = MutableStateFlow<List<SetInfo>>(emptyList())
    val sets: StateFlow<List<SetInfo>> = _sets.asStateFlow()
    private val _elements = MutableStateFlow<List<String>>(emptyList())
    val elements: StateFlow<List<String>> = _elements.asStateFlow()
    private val _types = MutableStateFlow<List<String>>(emptyList())
    val types: StateFlow<List<String>> = _types.asStateFlow()

    /** card slug -> copies owned. */
    val owned: StateFlow<Map<String, Int>> = c.collection.all
        .map { list -> list.groupBy { it.cardSlug }.mapValues { (_, v) -> v.sumOf { it.quantity } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val sync: StateFlow<SyncState> = c.cards.sync

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            // Re-run when the offline database finishes downloading or the collection changes ("Not owned").
            val syncDone = c.cards.sync.map { it is SyncState.Done }.distinctUntilChanged()
            combine(_filters.debounce(250).distinctUntilChanged(), syncDone, owned) { f, done, _ -> f to done }
                .collectLatest { (f, done) ->
                    if (done || _sets.value.isEmpty()) loadFacets()
                    load(f, page = 1, append = false)
                }
        }
    }

    private suspend fun loadFacets() {
        runCatching {
            _sets.value = c.cards.sets()
            val (el, ty) = c.cards.facets()
            _elements.value = el
            _types.value = ty
        }
    }

    fun update(transform: (Filters) -> Filters) = _filters.update(transform)

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        viewModelScope.launch { load(_filters.value, s.page + 1, append = true) }
    }

    fun addToBuy(row: ResultRow) {
        val edition = row.edition ?: row.card.editions.firstOrNull() ?: return
        viewModelScope.launch {
            c.trade.add(TradeListType.BUY, row.card, edition, foil = false, quantity = 1)
            _messages.tryEmit(c.strings.value.addedToBuy(1, row.card.name))
        }
    }

    private suspend fun load(f: Filters, page: Int, append: Boolean) {
        if (f.isEmpty) {
            _state.value = SearchState()
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            val local = c.cards.browse(f.query, f.set?.prefixNorm, f.element, f.type)
            val ownedNow = owned.value
            if (local != null) {
                val rows = local
                    .filter { !f.notOwned || (ownedNow[it.card.slug] ?: 0) == 0 }
                    .map { ResultRow(it.card, it.edition ?: it.card.editions.firstOrNull()) }
                _state.value = SearchState(results = rows, loading = false, searched = true)
            } else {
                // Offline database not ready: plain online name search.
                if (f.query.isBlank()) {
                    _state.value = SearchState(searched = true, onlineOnly = true)
                    return
                }
                val r = c.cards.search(f.query, page)
                val rows = r.cards.filter { !f.notOwned || (ownedNow[it.slug] ?: 0) == 0 }
                    .map { ResultRow(it, it.resultEditions.firstOrNull() ?: it.editions.firstOrNull()) }
                _state.update {
                    it.copy(
                        results = (if (append) it.results + rows else rows).distinctBy { row -> row.card.uuid.ifBlank { row.card.slug } },
                        page = page, hasMore = r.hasMore, loading = false, searched = true, onlineOnly = true,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Search failed", searched = true) }
        }
    }
}

@Composable
fun SearchScreen(onOpenCard: (slug: String, editionUuid: String?) -> Unit) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: SearchViewModel = viewModel { SearchViewModel(container) }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val sets by vm.sets.collectAsStateWithLifecycle()
    val elements by vm.elements.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val owned by vm.owned.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(title = s.searchTitle, snackbarHostState = snackbar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncBanner(sync)
            OutlinedTextField(
                value = filters.query,
                onValueChange = { q -> vm.update { it.copy(query = q) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(s.searchPlaceholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (filters.query.isNotEmpty()) {
                        IconButton(onClick = { vm.update { it.copy(query = "") } }) { Icon(Icons.Default.Clear, s.clear) }
                    }
                },
                singleLine = true,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuChip(
                    label = filters.set?.let { "${it.setPrefix} · ${it.setName}" } ?: s.filterSet,
                    selected = filters.set != null,
                    options = listOf(null to s.allSets) + sets.map { it to "${it.setPrefix} — ${it.setName} (${it.cards})" },
                    onPick = { picked -> vm.update { it.copy(set = picked) } },
                )
                MenuChip(
                    label = filters.element?.let(::pretty) ?: s.filterElement,
                    selected = filters.element != null,
                    options = listOf(null to s.anyValue) + elements.map { it to pretty(it) },
                    onPick = { picked -> vm.update { it.copy(element = picked) } },
                )
                MenuChip(
                    label = filters.type?.let(::pretty) ?: s.filterType,
                    selected = filters.type != null,
                    options = listOf(null to s.anyValue) + types.map { it to pretty(it) },
                    onPick = { picked -> vm.update { it.copy(type = picked) } },
                )
                FilterChip(
                    selected = filters.notOwned,
                    onClick = { vm.update { it.copy(notOwned = !it.notOwned) } },
                    label = { Text(s.notOwned) },
                )
            }
            if (state.onlineOnly) SmallNote(s.keywordNeedsDatabase, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                state.error != null && state.results.isEmpty() -> CenteredMessage(state.error!!)
                state.loading && state.results.isEmpty() -> Loading()
                filters.isEmpty -> CenteredMessage(s.searchHint)
                state.searched && state.results.isEmpty() -> CenteredMessage(s.noCardsFound(filters.query.ifBlank { filters.set?.setName.orEmpty() }))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "count") { SmallNote(s.resultsCount(state.results.size), Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                    items(state.results, key = { it.card.uuid.ifBlank { it.card.slug } }) { row ->
                        CardRow(
                            row,
                            owned = owned[row.card.slug] ?: 0,
                            onClick = { onOpenCard(row.card.slug, row.edition?.uuid) },
                            onAddToBuy = { vm.addToBuy(row) },
                        )
                        HorizontalDivider()
                    }
                    if (state.hasMore) {
                        item(key = "more") {
                            TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(if (state.loading) s.loading else s.loadMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "LUXEM" -> "Luxem", "REGALIA" -> "Regalia". */
private fun pretty(v: String) = v.lowercase().split('_', ' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

@Composable
private fun <T> MenuChip(label: String, selected: Boolean, options: List<Pair<T?, String>>, onPick: (T?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected,
            onClick = { open = true },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

@Composable
fun SyncBanner(sync: SyncState) {
    val s = LocalStrings.current
    when (sync) {
        is SyncState.Running -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SmallNote(if (sync.total > 0) s.syncProgress(sync.done, sync.total) else s.syncStarting)
            if (sync.total > 0) {
                LinearProgressIndicator(
                    progress = { (sync.done.toFloat() / sync.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        is SyncState.Failed -> if (!sync.hasData) {
            SmallNote(s.syncFailed(sync.message), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), MaterialTheme.colorScheme.error)
        }
        else -> Unit
    }
}

@Composable
private fun CardRow(row: ResultRow, owned: Int, onClick: () -> Unit, onAddToBuy: () -> Unit) {
    val s = LocalStrings.current
    val card = row.card
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(row.edition?.imageUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(card.elementLabel, card.types.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(
                    row.edition?.label?.takeIf { it.isNotBlank() },
                    if (owned > 0) s.ownedBadge(owned) else null,
                ).joinToString(" · ").ifBlank { card.editions.map { it.setPrefix }.distinct().joinToString(", ") },
                style = MaterialTheme.typography.bodySmall,
                color = if (owned > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onAddToBuy) { Icon(Icons.Default.ShoppingCart, s.quickAddBuy) }
    }
}
