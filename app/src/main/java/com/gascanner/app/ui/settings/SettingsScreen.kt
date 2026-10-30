package com.gascanner.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.gascanner.app.data.cache.SyncState
import com.gascanner.app.data.settings.AppSettings
import com.gascanner.app.data.settings.PriceSourceConfig
import com.gascanner.app.data.settings.PriceSourceKind
import com.gascanner.app.data.settings.StorePlatform
import com.gascanner.app.i18n.Languages
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.scan.ScanMode
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> =
        c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sync: StateFlow<SyncState> = c.cards.sync

    private val _offlineCount = MutableStateFlow(0)
    val offlineCount: StateFlow<Int> = _offlineCount.asStateFlow()

    /** Bumped when a shop's platform detection may have changed, to refresh the labels. */
    private val _detectTick = MutableStateFlow(0)
    val detectTick: StateFlow<Int> = _detectTick.asStateFlow()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val s get() = c.strings.value

    init {
        viewModelScope.launch {
            sync.collect { _offlineCount.value = runCatching { c.cards.offlineCount() }.getOrDefault(0) }
        }
    }

    fun detected(source: PriceSourceConfig): StorePlatform? = c.stores.detectedPlatform(source)

    fun setLanguage(code: String) = viewModelScope.launch { c.settings.setLanguage(code) }
    fun setScanMode(mode: ScanMode) = viewModelScope.launch { c.settings.setScanMode(mode) }
    fun setDefaultSource(id: String) = viewModelScope.launch { c.settings.setDefaultSource(id) }

    fun saveApiKey(key: String) = viewModelScope.launch {
        c.settings.setApiKey(key)
        messages.tryEmit(s.keySaved)
    }

    fun saveModel(model: String) = viewModelScope.launch {
        c.settings.setModel(model)
        messages.tryEmit(s.modelSaved)
    }

    fun setCurrency(code: String) = viewModelScope.launch { c.settings.setCurrency(code) }

    private fun updateSources(transform: (List<PriceSourceConfig>) -> List<PriceSourceConfig>) = viewModelScope.launch {
        c.settings.setSources(transform(c.settings.current().priceSources))
    }

    fun toggle(id: String, enabled: Boolean) = updateSources { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    fun delete(id: String) = updateSources { list -> list.filterNot { it.id == id } }

    fun upsertStore(original: PriceSourceConfig?, edited: PriceSourceConfig) {
        original?.let { c.stores.forget(it) }
        c.stores.forget(edited)
        _detectTick.value++
        updateSources { list ->
            if (original == null) list + edited.copy(id = "custom-" + UUID.randomUUID().toString().take(8))
            else list.map { if (it.id == original.id) edited.copy(id = original.id, builtIn = original.builtIn) else it }
        }
    }

    fun resetSources() = viewModelScope.launch { c.settings.resetSources() }

    fun refreshPrices() = viewModelScope.launch {
        messages.tryEmit(s.tcgDownloading)
        val result = runCatching { c.prices.refreshTcgplayer() }
        messages.tryEmit(if (result.isSuccess) s.tcgUpdated else s.updateFailed(result.exceptionOrNull()?.message.orEmpty()))
    }

    fun syncNow(full: Boolean) {
        c.appScope.launch { c.cards.sync(full) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val offlineCount by vm.offlineCount.collectAsStateWithLifecycle()
    val detectTick by vm.detectTick.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<PriceSourceConfig?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    ScreenScaffold(title = s.settingsTitle, snackbarHostState = snackbar) { padding ->
        val st = settings ?: return@ScreenScaffold
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Language
            item { Section(s.language) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = st.language == "system", onClick = { vm.setLanguage("system") }, label = { Text(s.languageSystem) })
                    Languages.names.forEach { (code, label) ->
                        FilterChip(selected = st.language == code, onClick = { vm.setLanguage(code) }, label = { Text(label) })
                    }
                }
            }

            // Offline database
            item { HorizontalDivider() }
            item { Section(s.offlineDb) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val date = if (st.lastSyncAt > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(st.lastSyncAt)) else null
                    Text(if (offlineCount > 0 && date != null) s.offlineDbStatus(offlineCount, date) else s.offlineDbNever)
                    when (val sy = sync) {
                        is SyncState.Running -> {
                            SmallNote(if (sy.total > 0) s.syncProgress(sy.done, sy.total) else s.syncStarting)
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        is SyncState.Failed -> SmallNote(s.syncFailed(sy.message), color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                    SmallNote(s.offlineDbHelp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.syncNow(full = false) }, enabled = sync !is SyncState.Running) { Text(s.checkUpdates) }
                        OutlinedButton(onClick = { vm.syncNow(full = true) }, enabled = sync !is SyncState.Running) { Text(s.redownload) }
                    }
                }
            }

            // Scanning
            item { HorizontalDivider() }
            item { Section(s.cardScanning) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = st.scanMode == ScanMode.ON_DEVICE, onClick = { vm.setScanMode(ScanMode.ON_DEVICE) }, label = { Text(s.scanOnDevice) })
                    FilterChip(selected = st.scanMode == ScanMode.CLAUDE, onClick = { vm.setScanMode(ScanMode.CLAUDE) }, label = { Text(s.scanClaude) })
                }
                SmallNote(if (st.scanMode == ScanMode.ON_DEVICE) s.scanOnDeviceInfo else s.scanClaudeInfo)
            }
            if (st.scanMode == ScanMode.CLAUDE) {
                item { ApiKeyField(st.anthropicApiKey, onSave = { vm.saveApiKey(it) }) }
                item {
                    var model by remember(st.visionModel) { mutableStateOf(st.visionModel) }
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text(s.visionModel) },
                        supportingText = { Text(s.visionModelHelp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { if (model != st.visionModel) TextButton(onClick = { vm.saveModel(model) }) { Text(s.save) } },
                    )
                }
            }

            // Prices
            item { HorizontalDivider() }
            item { Section(s.displayCurrency) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSettings.CURRENCIES.forEach { code ->
                        FilterChip(
                            selected = st.displayCurrency == code,
                            onClick = { vm.setCurrency(code) },
                            label = { Text(if (code == "ORIGINAL") s.currencyOriginal else code) },
                        )
                    }
                }
            }
            item { Section(s.defaultShop) }
            item {
                val choices = st.priceSources.filter { it.enabled && it.canFetch }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { src ->
                        FilterChip(
                            selected = st.defaultSource?.id == src.id,
                            onClick = { vm.setDefaultSource(src.id) },
                            label = { Text(src.name) },
                        )
                    }
                }
                SmallNote(s.defaultShopHelp)
            }
            item { Section(s.priceSources) }
            item { SmallNote(s.priceSourcesHelp) }
            items(st.priceSources, key = { it.id + detectTick }) { source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.bodyLarge)
                        val status = when {
                            source.kind == PriceSourceKind.TCGPLAYER_MARKET -> s.tcgMarketInfo
                            !source.fetchPrices -> s.linkOnly
                            else -> when (vm.detected(source)) {
                                StorePlatform.SHOPIFY -> "${s.livePrices} · Shopify"
                                StorePlatform.WOOCOMMERCE -> "${s.livePrices} · WooCommerce"
                                StorePlatform.NONE -> s.pricesNotReadable
                                else -> "${s.livePrices} · ${source.currency}"
                            }
                        }
                        SmallNote(status)
                    }
                    if (source.kind == PriceSourceKind.SEARCH_LINK) {
                        IconButton(onClick = { editing = source }) { Icon(Icons.Default.Edit, s.edit) }
                        if (!source.builtIn) IconButton(onClick = { vm.delete(source.id) }) { Icon(Icons.Default.Delete, s.delete) }
                    }
                    Switch(checked = source.enabled, onCheckedChange = { vm.toggle(source.id, it) })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { adding = true }) { Text(s.addStore) }
                    OutlinedButton(onClick = { vm.resetSources() }) { Text(s.reset) }
                }
            }
            item {
                OutlinedButton(onClick = { vm.refreshPrices() }, modifier = Modifier.fillMaxWidth()) { Text(s.refreshTcg) }
            }
            item { HorizontalDivider() }
            item { SmallNote(s.dataNotice) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (adding || editing != null) {
        StoreDialog(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { edited ->
                vm.upsertStore(editing, edited)
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
    val s = LocalStrings.current
    var key by remember(saved) { mutableStateOf(saved) }
    var visible by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(s.apiKey) },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, s.showKey)
                }
            },
            supportingText = { Text(s.apiKeyHelp) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (key != saved) TextButton(onClick = { onSave(key) }) { Text(s.saveKey) }
    }
}

@Composable
private fun StoreDialog(
    initial: PriceSourceConfig?,
    onDismiss: () -> Unit,
    onSave: (PriceSourceConfig) -> Unit,
) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var template by remember { mutableStateOf(initial?.urlTemplate.orEmpty()) }
    var fetch by remember { mutableStateOf(initial?.fetchPrices ?: true) }
    var currency by remember { mutableStateOf(initial?.currency ?: "EUR") }
    val valid = name.isNotBlank() && template.startsWith("http") && template.contains("{")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) s.addStore else s.editStore) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(s.storeName) }, singleLine = true)
                OutlinedTextField(
                    template,
                    { template = it },
                    label = { Text(s.searchUrl) },
                    placeholder = { Text("https://shop.example/search?q={name}") },
                )
                SmallNote(s.storeUrlHelp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.readPrices)
                        SmallNote(s.readPricesHelp)
                    }
                    Switch(checked = fetch, onCheckedChange = { fetch = it })
                }
                if (fetch) {
                    Text(s.storeCurrency, style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppSettings.CURRENCIES.filter { it != "ORIGINAL" }.forEach { code ->
                            FilterChip(selected = currency == code, onClick = { currency = code }, label = { Text(code) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val base = (initial ?: PriceSourceConfig(id = "", name = "", kind = PriceSourceKind.SEARCH_LINK))
                    onSave(
                        base.copy(
                            name = name.trim(),
                            urlTemplate = template.trim(),
                            fetchPrices = fetch,
                            currency = currency,
                            // Re-detect the platform after edits.
                            platform = if (base.platform == StorePlatform.NONE && fetch) StorePlatform.AUTO else base.platform,
                            baseUrl = if (initial != null && template.trim() == initial.urlTemplate) initial.baseUrl else "",
                        )
                    )
                },
                enabled = valid,
            ) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
