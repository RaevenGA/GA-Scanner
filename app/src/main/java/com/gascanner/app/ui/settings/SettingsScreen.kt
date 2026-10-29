package com.gascanner.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.AppContainer
import com.gascanner.app.appContainer
import com.gascanner.app.data.settings.AppSettings
import com.gascanner.app.data.settings.PriceSourceConfig
import com.gascanner.app.data.settings.PriceSourceKind
import com.gascanner.app.scan.ScanMode
import com.gascanner.app.ui.common.ScreenScaffold
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun setScanMode(mode: ScanMode) = viewModelScope.launch { c.settings.setScanMode(mode) }

    fun saveApiKey(key: String) = viewModelScope.launch {
        c.settings.setApiKey(key)
        messages.tryEmit("API key saved")
    }

    fun saveModel(model: String) = viewModelScope.launch {
        c.settings.setModel(model)
        messages.tryEmit("Model saved")
    }

    fun setCurrency(code: String) = viewModelScope.launch { c.settings.setCurrency(code) }

    private fun updateSources(transform: (List<PriceSourceConfig>) -> List<PriceSourceConfig>) = viewModelScope.launch {
        c.settings.setSources(transform(c.settings.current().priceSources))
    }

    fun toggle(id: String, enabled: Boolean) = updateSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    fun delete(id: String) = updateSources { list -> list.filterNot { it.id == id } }
    fun upsertStore(id: String?, name: String, template: String) = updateSources { list ->
        if (id == null) {
            list + PriceSourceConfig(
                id = "custom-" + UUID.randomUUID().toString().take(8),
                name = name.trim(),
                kind = PriceSourceKind.SEARCH_LINK,
                urlTemplate = template.trim(),
            )
        } else {
            list.map { if (it.id == id) it.copy(name = name.trim(), urlTemplate = template.trim()) else it }
        }
    }

    fun resetSources() = viewModelScope.launch { c.settings.resetSources() }

    fun refreshPrices() = viewModelScope.launch {
        messages.tryEmit("Downloading TCGplayer prices…")
        val result = runCatching { c.prices.refreshTcgplayer() }
        messages.tryEmit(if (result.isSuccess) "TCGplayer prices updated" else "Update failed: ${result.exceptionOrNull()?.message}")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<PriceSourceConfig?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(title = "Settings", snackbarHostState = snackbar) { padding ->
        val s = settings ?: return@ScreenScaffold
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Section("Card scanning") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanMode.entries.forEach { m ->
                        FilterChip(selected = s.scanMode == m, onClick = { vm.setScanMode(m) }, label = { Text(m.label) })
                    }
                }
                Text(
                    if (s.scanMode == ScanMode.ON_DEVICE)
                        "Reads the card name and footer on your phone. Free, works offline."
                    else
                        "Sends the photo to Claude. Handles glare, foils and odd angles better, but needs your own paid API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.scanMode == ScanMode.CLAUDE) item { ApiKeyField(s.anthropicApiKey, onSave = { vm.saveApiKey(it) }) }
            if (s.scanMode == ScanMode.CLAUDE) item {
                var model by remember(s.visionModel) { mutableStateOf(s.visionModel) }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Vision model") },
                    supportingText = { Text("e.g. claude-sonnet-5-5, or claude-haiku-5-5 for cheaper scans") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (model != s.visionModel) TextButton(onClick = { vm.saveModel(model) }) { Text("Save") }
                    },
                )
            }

            item { HorizontalDivider() }
            item { Section("Price display currency") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSettings.CURRENCIES.forEach { code ->
                        FilterChip(
                            selected = s.displayCurrency == code,
                            onClick = { vm.setCurrency(code) },
                            label = { Text(if (code == "ORIGINAL") "Store's own" else code) },
                        )
                    }
                }
            }

            item { HorizontalDivider() }
            item { Section("Price sources & stores") }
            item {
                Text(
                    "Turn sources on or off. Store links open the store's search for the card; add any shop whose " +
                        "search URL you know.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(s.priceSources, key = { it.id }) { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (source.kind == PriceSourceKind.TCGPLAYER_MARKET) "Live market prices (USD), updated daily"
                            else source.urlTemplate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (source.kind == PriceSourceKind.SEARCH_LINK) {
                        IconButton(onClick = { editing = source }) { Icon(Icons.Default.Edit, "Edit") }
                        if (!source.builtIn) {
                            IconButton(onClick = { vm.delete(source.id) }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                    Switch(checked = source.enabled, onCheckedChange = { vm.toggle(source.id, it) })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { adding = true }) { Text("Add store") }
                    OutlinedButton(onClick = { vm.resetSources() }) { Text("Reset") }
                }
            }
            item {
                OutlinedButton(onClick = { vm.refreshPrices() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh TCGplayer prices now")
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (adding || editing != null) {
        StoreDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { name, template ->
                vm.upsertStore(editing?.id, name, template)
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ApiKeyField(saved: String, onSave: (String) -> Unit) {
    var key by remember(saved) { mutableStateOf(saved) }
    var visible by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("Anthropic API key") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show key")
                }
            },
            supportingText = { Text("Get one at console.anthropic.com. Stored only on this phone.") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (key != saved) {
            TextButton(onClick = { onSave(key) }) { Text("Save key") }
        }
    }
}

@Composable
private fun StoreDialog(
    initial: PriceSourceConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, template: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var template by remember { mutableStateOf(initial?.urlTemplate.orEmpty()) }
    val valid = name.isNotBlank() && template.startsWith("http") && template.contains("{")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add store" else "Edit store") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Store name") }, singleLine = true)
                OutlinedTextField(
                    template,
                    { template = it },
                    label = { Text("Search URL") },
                    placeholder = { Text("https://shop.example/search?q={name}") },
                )
                Text(
                    "Placeholders: {name} card name, {set} set code, {number} collector number, " +
                        "{query} name + set. Tip: search for any card on the shop's website and copy the " +
                        "address, replacing the card name with {name}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, template) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
