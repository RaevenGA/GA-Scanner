package com.gascanner.app.ui.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.VerticalDivider
import com.gascanner.app.ui.common.ElementDot
import com.gascanner.app.ui.common.prettyName
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.appContainer
import com.gascanner.app.data.lists.TradeEntry
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.data.prices.Money
import com.gascanner.app.data.settings.AppSettings
import com.gascanner.app.data.settings.ViewMode
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.CardTile
import com.gascanner.app.ui.common.CenteredMessage
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import com.gascanner.app.ui.common.EditEntryDialog
import com.gascanner.app.ui.common.PriceFields
import com.gascanner.app.ui.common.ViewModeButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in any of the three lists, flattened for shared list/grid rendering. */
private data class EntryRow(
    val key: String,
    val slug: String,
    val editionUuid: String,
    val name: String,
    val setLine: String,
    val imageUrl: String?,
    val foil: Boolean,
    val quantity: Int,
    val price: Money?,
    val ownPrice: Boolean,
    val note: String,
    val search: String,
    val trade: TradeEntry? = null,
    val owned: com.gascanner.app.data.db.CollectionEntry? = null,
    val elements: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    /** Newer first for "Recent". */
    val addedAt: Long = 0,
)

@Composable
fun CollectionScreen(onOpenCard: (slug: String, editionUuid: String?) -> Unit, onCompare: () -> Unit) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: CollectionViewModel = viewModel { CollectionViewModel(container) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val viewMode by vm.viewMode.collectAsStateWithLifecycle()
    val owned by vm.owned.collectAsStateWithLifecycle()
    val sell by vm.sell.collectAsStateWithLifecycle()
    val buy by vm.buy.collectAsStateWithLifecycle()
    val valuation by vm.valuation.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val failures by vm.importFailures.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf(CollectionSort.RECENT) }
    var sortMenu by remember { mutableStateOf(false) }
    var elementFilter by rememberSaveable { mutableStateOf(setOf<String>()) }
    var classFilter by rememberSaveable { mutableStateOf(setOf<String>()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var filter by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TradeEntry?>(null) }
    var editingOwned by remember { mutableStateOf<com.gascanner.app.data.db.CollectionEntry?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val text = vm.exportCsv()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess
                }
                vm.notify(if (ok) s.exportDone else s.updateFailed("CSV"))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            pendingImport = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
        }
    }

    fun share(type: TradeListType) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, vm.shareText(type))
        }
        context.startActivity(Intent.createChooser(intent, s.shareList))
    }

    val rows: List<EntryRow> = when (tab) {
        ListTab.OWNED -> owned.map {
            EntryRow(
                key = "o${it.id}", slug = it.cardSlug, editionUuid = it.editionUuid, name = it.cardName,
                setLine = "${it.setPrefix} ${it.collectorNumber}".trim(), imageUrl = it.imageUrl, foil = it.foil,
                quantity = it.quantity, price = valuation.perCopy[priceKey(it.editionUuid, it.foil)], ownPrice = false,
                note = "", search = "${it.cardName} ${it.setPrefix} ${it.setName}", owned = it,
                elements = info[it.cardSlug].elementList(), classes = info[it.cardSlug]?.classes.orEmpty(), addedAt = it.addedAt,
            )
        }
        ListTab.SELL, ListTab.BUY -> (if (tab == ListTab.SELL) sell else buy).map {
            val own = it.price?.let { p -> Money(p, it.currency ?: valuation.currency) }
            EntryRow(
                key = "t${it.id}", slug = it.cardSlug, editionUuid = it.editionUuid, name = it.cardName,
                setLine = "${it.setPrefix} ${it.collectorNumber}".trim(), imageUrl = it.imageUrl, foil = it.foil,
                quantity = it.quantity, price = own ?: valuation.perCopy[priceKey(it.editionUuid, it.foil)],
                ownPrice = own != null, note = it.note, search = "${it.cardName} ${it.setPrefix} ${it.setName} ${it.note}", trade = it,
                elements = info[it.cardSlug].elementList(), classes = info[it.cardSlug]?.classes.orEmpty(), addedAt = it.id,
            )
        }
    }
    val allElements = rows.flatMap { it.elements }.distinct().sortedBy { ELEMENT_ORDER.indexOf(it.uppercase()).let { i -> if (i < 0) 99 else i } }
    val allClasses = rows.flatMap { it.classes }.distinct().sorted()
    val shown = rows
        .filter { filter.isBlank() || it.search.contains(filter, ignoreCase = true) }
        .filter { elementFilter.isEmpty() || it.elements.any { e -> e in elementFilter } }
        .filter { classFilter.isEmpty() || it.classes.any { c -> c in classFilter } }
        .let { list -> sortRows(list, sort) }
    val totalCards = rows.sumOf { it.quantity }
    val priced = rows.filter { it.price != null }
    val total = priced.sumOf { r -> if (r.price!!.currency == valuation.currency) r.price.amount * r.quantity else 0.0 }

    ScreenScaffold(
        title = s.collectionTitle,
        snackbarHostState = snackbar,
        actions = {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, s.sortBy) }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    CollectionSort.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(s, o)) },
                            leadingIcon = { if (o == sort) Icon(Icons.Default.Check, null) },
                            onClick = { sort = o; sortMenu = false },
                        )
                    }
                }
            }
            ViewModeButton(viewMode, vm::setViewMode)
            if (tab != ListTab.OWNED) {
                IconButton(onClick = { share(if (tab == ListTab.SELL) TradeListType.SELL else TradeListType.BUY) }, enabled = rows.isNotEmpty()) {
                    Icon(Icons.Default.Share, s.shareList)
                }
            }
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, s.more) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(s.compareTitle) }, onClick = { menuOpen = false; onCompare() })
                DropdownMenuItem(text = { Text(s.updatePrices) }, onClick = { menuOpen = false; vm.updatePrices() })
                DropdownMenuItem(text = { Text(s.exportCsv) }, onClick = { menuOpen = false; exportLauncher.launch(vm.exportFileName()) })
                DropdownMenuItem(
                    text = { Text(s.importCsv) },
                    onClick = { menuOpen = false; importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "*/*")) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                listOf(ListTab.OWNED to s.tabOwned, ListTab.SELL to s.tabSellList, ListTab.BUY to s.tabBuyList).forEach { (t, label) ->
                    Tab(selected = tab == t, onClick = { vm.tab.value = t }, text = { Text(label, maxLines = 1) })
                }
            }
            busy?.let {
                SmallNote(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            }
            if (tab != ListTab.OWNED) {
                TextButton(onClick = onCompare, modifier = Modifier.padding(horizontal = 8.dp)) { Text(s.compareTitle) }
            }
            if (rows.isEmpty()) {
                CenteredMessage(
                    when (tab) {
                        ListTab.OWNED -> s.emptyCollection
                        ListTab.SELL -> s.emptySell
                        ListTab.BUY -> s.emptyBuy
                    }
                )
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        s.cardsCount(totalCards) + if (total > 0) " · " + s.value(Money(total, valuation.currency).format()) else "",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    SmallNote(s.pricedOf(priced.sumOf { it.quantity }, totalCards))
                }
                TextButton(onClick = vm::updatePrices, enabled = busy == null) { Text(s.updatePrices) }
            }
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = { Text(s.filterHint) },
                leadingIcon = { Icon(Icons.Default.FilterList, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (allElements.size + allClasses.size > 1) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    allElements.forEach { el ->
                        FilterChip(
                            selected = el in elementFilter,
                            onClick = { elementFilter = if (el in elementFilter) elementFilter - el else elementFilter + el },
                            label = { Text(prettyName(el)) },
                            leadingIcon = { ElementDot(el, 18.dp) },
                        )
                    }
                    if (allElements.isNotEmpty() && allClasses.isNotEmpty()) VerticalDivider(Modifier.height(24.dp))
                    allClasses.forEach { cl ->
                        FilterChip(
                            selected = cl in classFilter,
                            onClick = { classFilter = if (cl in classFilter) classFilter - cl else classFilter + cl },
                            label = { Text(prettyName(cl)) },
                        )
                    }
                    if (elementFilter.isNotEmpty() || classFilter.isNotEmpty()) {
                        TextButton(onClick = { elementFilter = emptySet(); classFilter = emptySet() }) { Text(s.clear) }
                    }
                }
                if (shown.size != rows.size) SmallNote(s.showingOf(shown.sumOf { it.quantity }, totalCards), Modifier.padding(horizontal = 16.dp))
            }
            if (viewMode == ViewMode.LIST) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.key }) { r ->
                        ListRow(
                            r,
                            onOpen = { onOpenCard(r.slug, r.editionUuid) },
                            onChange = { d -> r.owned?.let { vm.changeOwned(it, d) } ?: r.trade?.let { vm.changeTrade(it, d) } },
                            onEdit = { r.trade?.let { editing = it } ?: r.owned?.let { editingOwned = it } },
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(viewMode.columns),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.key }) { r ->
                        CardTile(
                            imageUrl = r.imageUrl,
                            title = r.name,
                            quantity = r.quantity,
                            foil = r.foil,
                            price = r.price?.format(),
                            onClick = { onOpenCard(r.slug, r.editionUuid) },
                            onLongClick = { r.trade?.let { editing = it } ?: r.owned?.let { editingOwned = it } },
                        )
                    }
                }
            }
        }
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(s.importCsv) },
            text = { Text(s.importModeQuestion) },
            confirmButton = {
                TextButton(onClick = { pendingImport = null; vm.import(text, replace = false) }) { Text(s.importMerge) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null; vm.import(text, replace = true) }) { Text(s.importReplace) }
            },
        )
    }
    if (failures.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = vm::dismissImportFailures,
            title = { Text(s.notRecognised) },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { failures.forEach { Text(it) } } },
            confirmButton = { TextButton(onClick = vm::dismissImportFailures) { Text(s.close) } },
        )
    }
    editing?.let { entry ->
        EditEntryDialog(
            cardSlug = entry.cardSlug,
            cardName = entry.cardName,
            editionUuid = entry.editionUuid,
            foil = entry.foil,
            quantity = entry.quantity,
            section = null,
            priceFields = PriceFields(entry.type == TradeListType.SELL, entry.price, entry.currency, entry.note, valuation.currency),
            onDismiss = { editing = null },
            onSave = { e -> vm.editTrade(entry, e); editing = null },
        )
    }
    editingOwned?.let { entry ->
        EditEntryDialog(
            cardSlug = entry.cardSlug,
            cardName = entry.cardName,
            editionUuid = entry.editionUuid,
            foil = entry.foil,
            quantity = entry.quantity,
            section = null,
            priceFields = null,
            onDismiss = { editingOwned = null },
            onSave = { e -> vm.editOwned(entry, e); editingOwned = null },
        )
    }
}

@Composable
private fun ListRow(r: EntryRow, onOpen: () -> Unit, onChange: (Int) -> Unit, onEdit: () -> Unit) {
    val s = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardThumb(r.imageUrl, width = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                r.elements.forEach { ElementDot(it, 14.dp); Spacer(Modifier.width(3.dp)) }
                if (r.elements.isNotEmpty()) Spacer(Modifier.width(3.dp))
                SmallNote(listOf(r.setLine, if (r.foil) s.foil else "").filter { it.isNotBlank() }.joinToString(" · "))
            }
            val priceText = r.price?.let { (if (r.ownPrice) "" else "≈ ") + it.format() }
            val line = listOfNotNull(priceText, r.note.ifBlank { null }).joinToString(" · ")
            if (line.isNotBlank()) {
                SmallNote(line, color = if (r.ownPrice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, s.edit) }
        QuantityStepper(r.quantity, onChange = onChange)
    }
}

@Composable
private fun TradePriceDialog(
    entry: TradeEntry,
    isSell: Boolean,
    defaultCurrency: String,
    onDismiss: () -> Unit,
    onSave: (Double?, String?, String) -> Unit,
) {
    val s = LocalStrings.current
    var price by remember { mutableStateOf(entry.price?.let { "%.2f".format(java.util.Locale.US, it) }.orEmpty()) }
    var currency by remember { mutableStateOf(entry.currency ?: defaultCurrency) }
    var note by remember { mutableStateOf(entry.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${entry.cardName} · ${entry.setPrefix} ${entry.collectorNumber}".trim()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text(if (isSell) s.askingPrice else s.maxPrice) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSettings.CURRENCIES.filter { it != "ORIGINAL" }.forEach { code ->
                        FilterChip(selected = currency == code, onClick = { currency = code }, label = { Text(code) })
                    }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(s.note) })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.replace(",", ".").toDoubleOrNull()
                onSave(p, if (p != null) currency else null, note)
            }) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

private val ELEMENT_ORDER = listOf("NORM", "FIRE", "WATER", "WIND", "ARCANE", "ASTRA", "CRUX", "LUXEM", "UMBRA", "EXIA", "NEOS", "TERA")

private fun com.gascanner.app.data.api.CardDto?.elementList(): List<String> =
    this?.elements?.ifEmpty { listOfNotNull(element) }.orEmpty()

private fun sortRows(rows: List<EntryRow>, sort: CollectionSort): List<EntryRow> = when (sort) {
    CollectionSort.RECENT -> rows.sortedByDescending { it.addedAt }
    CollectionSort.NAME -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    CollectionSort.ELEMENT -> rows.sortedWith(
        compareBy<EntryRow> { r -> r.elements.minOfOrNull { e -> ELEMENT_ORDER.indexOf(e.uppercase()).let { if (it < 0) 99 else it } } ?: 100 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )
    CollectionSort.CLASS -> rows.sortedWith(
        compareBy<EntryRow> { it.classes.firstOrNull() ?: "\uFFFF" }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )
    CollectionSort.SET -> rows.sortedWith(compareBy<EntryRow> { it.setLine.substringBefore(' ') }.thenBy {
        it.setLine.substringAfterLast(' ').filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE
    })
    CollectionSort.VALUE -> rows.sortedByDescending { it.price?.amount ?: -1.0 }
}

private fun sortLabel(s: com.gascanner.app.i18n.Strings, o: CollectionSort): String = when (o) {
    CollectionSort.RECENT -> s.sortRecent
    CollectionSort.NAME -> s.sortName
    CollectionSort.ELEMENT -> s.sortElement
    CollectionSort.CLASS -> s.sortClass
    CollectionSort.SET -> s.sortSet
    CollectionSort.VALUE -> s.sortValue
}
