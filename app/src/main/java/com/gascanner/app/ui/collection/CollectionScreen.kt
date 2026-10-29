package com.gascanner.app.ui.collection

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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.appContainer
import com.gascanner.app.data.db.CollectionEntry
import com.gascanner.app.data.db.CollectionRepository
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CollectionViewModel(private val repo: CollectionRepository) : ViewModel() {
    val entries: StateFlow<List<CollectionEntry>> =
        repo.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun change(entry: CollectionEntry, delta: Int) {
        viewModelScope.launch { repo.changeQuantity(entry, delta) }
    }
}

@Composable
fun CollectionScreen(onOpenCard: (slug: String, editionUuid: String?) -> Unit) {
    val container = appContainer()
    val vm: CollectionViewModel = viewModel { CollectionViewModel(container.collection) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }

    val total = entries.sumOf { it.quantity }
    ScreenScaffold(title = "Collection · $total cards") { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (entries.isEmpty()) {
                CenteredMessage("Your collection is empty. Scan a card or open one from search to add it.")
                return@Column
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text("Filter by name or set") },
                leadingIcon = { Icon(Icons.Default.FilterList, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val shown = entries.filter {
                filter.isBlank() || it.cardName.contains(filter, true) || it.setPrefix.contains(filter, true) ||
                    it.setName.contains(filter, true)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id }) { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenCard(entry.cardSlug, entry.editionUuid) }
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CardThumb(entry.imageUrl, width = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.cardName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOf("${entry.setPrefix} ${entry.collectorNumber}".trim(), if (entry.foil) "Foil" else "")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        QuantityStepper(entry.quantity, onChange = { vm.change(entry, it) })
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
