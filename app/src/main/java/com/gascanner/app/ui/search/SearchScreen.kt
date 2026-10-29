package com.gascanner.app.ui.search

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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.GatcgApi
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.Loading
import com.gascanner.app.ui.common.ScreenScaffold
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val results: List<CardDto> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val api: GatcgApi) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _query.debounce(350).distinctUntilChanged().collectLatest { load(it, page = 1, append = false) }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        viewModelScope.launch { load(_query.value, s.page + 1, append = true) }
    }

    private suspend fun load(q: String, page: Int, append: Boolean) {
        if (q.isBlank()) {
            _state.value = SearchState()
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        try {
            val r = api.search(q.trim(), page)
            _state.update {
                it.copy(
                    results = (if (append) it.results + r.data else r.data).distinctBy { c -> c.uuid.ifBlank { c.slug } },
                    page = r.page,
                    hasMore = r.hasMore || r.page < r.totalPages,
                    loading = false,
                    searched = true,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Search failed", searched = true) }
        }
    }
}

@Composable
fun SearchScreen(onOpenCard: (slug: String) -> Unit) {
    val container = appContainer()
    val vm: SearchViewModel = viewModel { SearchViewModel(container.gatcg) }
    val query by vm.query.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Grand Archive cards") { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search by card name") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, "Clear") }
                    }
                },
                singleLine = true,
            )
            when {
                state.error != null && state.results.isEmpty() -> CenteredMessage(state.error!!)
                state.loading && state.results.isEmpty() -> Loading()
                query.isBlank() -> CenteredMessage("Type a card name, or use Scan to identify a card with the camera.")
                state.searched && state.results.isEmpty() -> CenteredMessage("No cards found for \"$query\".")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.results, key = { it.uuid.ifBlank { it.slug } }) { card ->
                        CardRow(card, onClick = { onOpenCard(card.slug) })
                        HorizontalDivider()
                    }
                    if (state.hasMore) {
                        item {
                            TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(if (state.loading) "Loading…" else "Load more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardRow(card: CardDto, onClick: () -> Unit) {
    val edition = card.resultEditions.firstOrNull() ?: card.editions.firstOrNull()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(edition?.imageUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(card.elementLabel, card.types.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                card.editions.map { it.setPrefix }.distinct().joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
