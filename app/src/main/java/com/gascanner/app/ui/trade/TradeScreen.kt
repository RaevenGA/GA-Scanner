package com.gascanner.app.ui.trade

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.deal.DealItem
import com.gascanner.app.data.deal.DealSide
import com.gascanner.app.data.prices.Money
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.i18n.Strings
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.EditEntryDialog
import com.gascanner.app.ui.common.QuantityStepper
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

/** Which dialog is open on the trade screen. */
private sealed interface TradeDialog {
    data object Add : TradeDialog
    data class Pick(val source: Int) : TradeDialog
    data object Search : TradeDialog
    data object ShowCode : TradeDialog
    data object Complete : TradeDialog
    data object Clear : TradeDialog
}

private const val PICK_SELL = 0
private const val PICK_BUY = 1
private const val PICK_COLLECTION = 2

@Composable
fun TradeScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onScanCode: () -> Unit,
    onOpenCard: (String, String?) -> Unit,
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: TradeViewModel = viewModel { TradeViewModel(container) }
    val items by vm.items.collectAsStateWithLifecycle()
    val prices by vm.prices.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sideIndex by rememberSaveable { mutableIntStateOf(0) }
    val side = if (sideIndex == 0) DealSide.GIVE else DealSide.GET
    var dialog by remember { mutableStateOf<TradeDialog?>(null) }
    var editing by remember { mutableStateOf<DealItem?>(null) }
    var adding by remember { mutableStateOf<CardDto?>(null) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(message) {
        if (message != null) {
            onMessageShown()
            sideIndex = 1
            snackbar.showSnackbar(message)
        }
    }

    val give = items.filter { it.side == DealSide.GIVE.name }
    val get = items.filter { it.side == DealSide.GET.name }
    val currency = prices.values.firstOrNull()?.currency
    fun total(list: List<DealItem>) = list.sumOf { i -> (prices[i.id]?.amount ?: 0.0) * i.quantity }
    fun unpriced(list: List<DealItem>) = list.filter { it.id !in prices }.sumOf { it.quantity }

    ScreenScaffold(
        title = s.tradeTitle,
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = { dialog = TradeDialog.ShowCode }, enabled = give.isNotEmpty()) { Icon(Icons.Default.QrCode2, s.tradeShowCode) }
            IconButton(onClick = onScanCode) { Icon(Icons.Default.QrCodeScanner, s.tradeScanCode) }
            IconButton(onClick = vm::updatePrices, enabled = items.isNotEmpty()) { Icon(Icons.Default.Refresh, s.updatePrices) }
            IconButton(onClick = { dialog = TradeDialog.Clear }, enabled = items.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, s.clear) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Summary(s, give, get, total(give), total(get), unpriced(give), unpriced(get), currency)
            busy?.let { SmallNote(it, Modifier.padding(horizontal = 16.dp)) }
            TabRow(selectedTabIndex = sideIndex) {
                Tab(selected = sideIndex == 0, onClick = { sideIndex = 0 }, text = { Text("${s.tradeIGive} (${give.sumOf { it.quantity }})") })
                Tab(selected = sideIndex == 1, onClick = { sideIndex = 1 }, text = { Text("${s.tradeIGet} (${get.sumOf { it.quantity }})") })
            }
            val list = if (side == DealSide.GIVE) give else get
            if (list.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (side == DealSide.GIVE) s.tradeGiveEmpty else s.tradeGetEmpty, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.id }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenCard(item.cardSlug, item.editionUuid) }
                                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CardThumb(item.imageUrl, width = 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.cardName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                SmallNote(
                                    listOfNotNull(
                                        "${item.setPrefix} ${item.collectorNumber}".trim(),
                                        if (item.foil) s.foil else null,
                                        prices[item.id]?.format(),
                                    ).joinToString(" · ")
                                )
                            }
                            IconButton(onClick = { editing = item }) { Icon(Icons.Default.Edit, s.edit) }
                            QuantityStepper(item.quantity, onChange = { vm.setQuantity(item, item.quantity + it) })
                        }
                        HorizontalDivider()
                    }
                }
            }
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { dialog = TradeDialog.Add }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text(s.addCards)
                    }
                    Button(onClick = { dialog = TradeDialog.Complete }, enabled = items.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Text(s.tradeComplete)
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        TradeDialog.Add -> AddChooser(
            s, side,
            onPick = { dialog = TradeDialog.Pick(it) },
            onScan = { dialog = null; vm.scanFor(side); onScan() },
            onSearch = { dialog = TradeDialog.Search },
            onScanCode = { dialog = null; onScanCode() },
            onDismiss = { dialog = null },
        )
        is TradeDialog.Pick -> {
            val source = when (d.source) {
                PICK_SELL -> vm.sellList
                PICK_BUY -> vm.buyList
                else -> vm.collection
            }.collectAsStateWithLifecycle().value
            PickDialog(
                title = when (d.source) {
                    PICK_SELL -> s.tradeFromSell
                    PICK_BUY -> s.tradeFromBuy
                    else -> s.addFromCollection
                },
                entries = source,
                inTrade = { e -> items.filter { it.side == side.name && it.editionUuid == e.editionUuid && it.foil == e.foil }.sumOf { it.quantity } },
                onPick = { vm.addPick(side, it) },
                onDismiss = { dialog = null },
            )
        }
        TradeDialog.Search -> SearchDialog(vm, onPick = { dialog = null; adding = it }, onDismiss = { dialog = null })
        TradeDialog.ShowCode -> CodeDialog(remember(items) { vm.myCodes() }, onDismiss = { dialog = null })
        TradeDialog.Complete -> CompleteDialog(
            s, give.sumOf { it.quantity }, get.sumOf { it.quantity },
            onConfirm = { dialog = null; vm.complete(it) },
            onDismiss = { dialog = null },
        )
        TradeDialog.Clear -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(s.tradeClearTitle) },
            text = { Text(s.tradeClearText) },
            confirmButton = { TextButton(onClick = { dialog = null; vm.clear() }) { Text(s.clear) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(s.cancel) } },
        )
        null -> {}
    }

    editing?.let { item ->
        EditEntryDialog(
            cardSlug = item.cardSlug, cardName = item.cardName, editionUuid = item.editionUuid, foil = item.foil,
            quantity = item.quantity, section = null, priceFields = null,
            onDismiss = { editing = null },
            onSave = { e -> editing = null; vm.edit(item, e.card, e.edition, e.foil, e.quantity) },
        )
    }
    adding?.let { card ->
        val first = card.allEditions.firstOrNull()
        if (first == null) {
            adding = null
        } else {
            EditEntryDialog(
                cardSlug = card.slug, cardName = card.name, editionUuid = first.uuid, foil = false,
                quantity = 1, section = null, priceFields = null,
                onDismiss = { adding = null },
                onSave = { e -> adding = null; vm.addCard(side, e.card, e.edition, e.foil, e.quantity) },
            )
        }
    }
}

@Composable
private fun Summary(
    s: Strings, give: List<DealItem>, get: List<DealItem>, giveTotal: Double, getTotal: Double,
    giveUnpriced: Int, getUnpriced: Int, currency: String?,
) {
    fun money(v: Double) = currency?.let { Money(v, it).format() } ?: "–"
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    SmallNote(s.tradeIGive)
                    Text("≈ " + money(giveTotal), style = MaterialTheme.typography.titleMedium)
                    SmallNote(s.cardsCount(give.sumOf { it.quantity }) + if (giveUnpriced > 0) " · " + s.tradeNoPrice(giveUnpriced) else "")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    SmallNote(s.tradeIGet)
                    Text("≈ " + money(getTotal), style = MaterialTheme.typography.titleMedium)
                    SmallNote(s.cardsCount(get.sumOf { it.quantity }) + if (getUnpriced > 0) " · " + s.tradeNoPrice(getUnpriced) else "")
                }
            }
            if (give.isNotEmpty() || get.isNotEmpty()) {
                val diff = getTotal - giveTotal
                val text = when {
                    currency == null -> s.tradeNoPrices
                    kotlin.math.abs(diff) < 0.005 -> s.tradeEven
                    diff > 0 -> s.tradeYouGetMore(money(diff))
                    else -> s.tradeYouGiveMore(money(-diff))
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        currency == null || kotlin.math.abs(diff) < 0.005 -> MaterialTheme.colorScheme.onSurface
                        diff > 0 -> Color(0xFF2E7D32)
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AddChooser(
    s: Strings, side: DealSide, onPick: (Int) -> Unit, onScan: () -> Unit, onSearch: () -> Unit,
    onScanCode: () -> Unit, onDismiss: () -> Unit,
) {
    val options: List<Triple<ImageVector, String, () -> Unit>> = if (side == DealSide.GIVE) listOf(
        Triple(Icons.Default.Sell, s.tradeFromSell, { onPick(PICK_SELL) }),
        Triple(Icons.Default.CollectionsBookmark, s.addFromCollection, { onPick(PICK_COLLECTION) }),
        Triple(Icons.Default.CameraAlt, s.addByScanning, onScan),
        Triple(Icons.Default.Search, s.addBySearching, onSearch),
    ) else listOf(
        Triple(Icons.Default.QrCodeScanner, s.tradeScanCode, onScanCode),
        Triple(Icons.Default.ShoppingCart, s.tradeFromBuy, { onPick(PICK_BUY) }),
        Triple(Icons.Default.CameraAlt, s.addByScanning, onScan),
        Triple(Icons.Default.Search, s.addBySearching, onSearch),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (side == DealSide.GIVE) s.tradeAddGive else s.tradeAddGet) },
        text = {
            Column {
                options.forEach { (icon, label, action) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { Icon(icon, null) },
                        modifier = Modifier.clickable { action() },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** One of my lists; tapping a card puts one more copy into the trade. */
@Composable
private fun PickDialog(
    title: String,
    entries: List<PickEntry>,
    inTrade: (PickEntry) -> Int,
    onPick: (PickEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val shown = entries.filter { query.isBlank() || it.cardName.contains(query.trim(), true) || it.setLine.contains(query.trim(), true) }
        .sortedBy { it.cardName.lowercase() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(s.filterHint) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    SmallNote(if (entries.isEmpty()) s.tradeListEmpty else s.noCardsMatch, Modifier.padding(top = 12.dp))
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.key }) { e ->
                        val n = inTrade(e)
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(e) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CardThumb(e.imageUrl, width = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.cardName, maxLines = 1, fontWeight = FontWeight.SemiBold)
                                SmallNote(listOfNotNull(e.setLine, if (e.foil) s.foil else null, "×${e.quantity}").joinToString(" · "))
                            }
                            if (n > 0) {
                                Text(
                                    "+$n",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.done) } },
    )
}

@Composable
private fun SearchDialog(vm: TradeViewModel, onPick: (CardDto) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CardDto>>(emptyList()) }
    LaunchedEffect(query) {
        delay(250)
        results = if (query.trim().length >= 2) vm.search(query) else emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.addBySearching) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(s.searchHint) },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(results, key = { it.slug }) { card ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(card) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CardThumb(card.allEditions.firstOrNull()?.imageUrl, width = 32.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(card.name, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** My "I give" side as QR codes, one after another if it doesn't fit in one. */
@Composable
private fun CodeDialog(codes: List<String>, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var index by remember(codes) { mutableIntStateOf(0) }
    val bitmap = remember(codes, index) { codes.getOrNull(index)?.let { qrBitmap(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.tradeShowCode) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallNote(s.tradeShowCodeHelp)
                Spacer(Modifier.width(8.dp))
                bitmap?.let {
                    Image(
                        it.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White).padding(8.dp),
                    )
                }
                if (codes.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { index-- }, enabled = index > 0) { Text("‹") }
                        Text(s.tradeCodePart(index + 1, codes.size))
                        TextButton(onClick = { index++ }, enabled = index < codes.size - 1) { Text("›") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(s.close) } },
    )
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
}

@Composable
private fun CompleteDialog(s: Strings, giveCount: Int, getCount: Int, onConfirm: (CompleteOptions) -> Unit, onDismiss: () -> Unit) {
    var o by remember { mutableStateOf(CompleteOptions()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.tradeComplete) },
        text = {
            Column {
                Text(s.tradeCompleteSummary(giveCount, getCount))
                Spacer(Modifier.width(8.dp))
                SmallNote(s.tradeIGive, Modifier.padding(top = 8.dp))
                Check(o.removeGivenFromCollection, s.tradeRemoveFromCollection, giveCount > 0) { o = o.copy(removeGivenFromCollection = it) }
                Check(o.removeGivenFromSell, s.tradeRemoveFromSell, giveCount > 0) { o = o.copy(removeGivenFromSell = it) }
                SmallNote(s.tradeIGet, Modifier.padding(top = 8.dp))
                Check(o.addReceivedToCollection, s.tradeAddToCollection, getCount > 0) { o = o.copy(addReceivedToCollection = it) }
                Check(o.removeReceivedFromBuy, s.tradeRemoveFromBuy, getCount > 0) { o = o.copy(removeReceivedFromBuy = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(o) }) { Text(s.tradeComplete) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun Check(checked: Boolean, label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
