package com.gascanner.app.ui.compare

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.SwapHorizontalCircle
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gascanner.app.AppContainer
import com.gascanner.app.appContainer
import com.gascanner.app.data.compare.CompareRow
import com.gascanner.app.data.compare.ListComparer
import com.gascanner.app.data.compare.MatchStatus
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.scan.rarityLabel
import com.gascanner.app.ui.common.CardThumb
import com.gascanner.app.ui.common.Loading
import com.gascanner.app.ui.common.ScreenScaffold
import com.gascanner.app.ui.common.SmallNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Their list is someone's "for sale" list (check against my Want to buy) or their "wanted" list (my Want to sell). */
enum class TheirList(val mine: TradeListType) { SELLING(TradeListType.BUY), BUYING(TradeListType.SELL) }

class CompareViewModel(private val c: AppContainer) : ViewModel() {
    val text = MutableStateFlow("")
    val theirList = MutableStateFlow(TheirList.SELLING)
    private val _rows = MutableStateFlow<List<CompareRow>?>(null)
    val rows: StateFlow<List<CompareRow>?> = _rows.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val comparer = ListComparer(c.cards)

    fun check() {
        val t = text.value
        if (t.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val mine = c.trade.all(theirList.value.mine)
                val owned = c.collection.all.first()
                _rows.value = comparer.compare(t, mine, owned)
            } finally {
                _busy.value = false
            }
        }
    }

    fun clear() {
        text.value = ""
        _rows.value = null
    }
}

private val GREEN = Color(0xFF2E7D32)
private val AMBER = Color(0xFFF9A825)
private val BLUE = Color(0xFF1E88E5)

@Composable
fun CompareScreen(onBack: () -> Unit, onOpenCard: (String, String?) -> Unit) {
    val container = appContainer()
    val s = LocalStrings.current
    val vm: CompareViewModel = viewModel { CompareViewModel(container) }
    val text by vm.text.collectAsStateWithLifecycle()
    val their by vm.theirList.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    ScreenScaffold(title = s.compareTitle, onBack = onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val opts = listOf(TheirList.SELLING to s.theyAreSelling, TheirList.BUYING to s.theyAreBuying)
                opts.forEachIndexed { i, (v, label) ->
                    SegmentedButton(
                        selected = their == v,
                        onClick = { vm.theirList.value = v },
                        shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                    ) { Text(label, maxLines = 1) }
                }
            }
            SmallNote(
                if (their == TheirList.SELLING) s.compareWithBuy else s.compareWithSell,
                Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { vm.text.value = it },
                placeholder = { Text(s.pasteHint) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { vm.text.value = it } }) {
                    Icon(Icons.Default.ContentPaste, null)
                    Spacer(Modifier.width(6.dp))
                    Text(s.paste)
                }
                Button(onClick = vm::check, enabled = text.isNotBlank() && !busy) { Text(s.checkList) }
                if (rows != null) TextButton(onClick = vm::clear) { Text(s.clear) }
            }
            if (busy) Loading()
            rows?.let { list ->
                Legend(list)
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { i, _ -> i }) { _, row ->
                        ResultRow(row, onClick = { row.card?.let { onOpenCard(it.slug, row.edition?.uuid) } })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private fun statusIcon(st: MatchStatus): Pair<ImageVector, Color> = when (st) {
    MatchStatus.EXACT -> Icons.Default.CheckCircle to GREEN
    MatchStatus.NAME -> Icons.Default.SwapHorizontalCircle to AMBER
    MatchStatus.IN_COLLECTION -> Icons.Default.CollectionsBookmark to BLUE
    MatchStatus.NONE -> Icons.Default.RemoveCircleOutline to Color.Gray
    MatchStatus.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline to Color.Gray
}

@Composable
private fun Legend(rows: List<CompareRow>) {
    val s = LocalStrings.current
    val counts = rows.groupingBy { it.status }.eachCount()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(
            MatchStatus.EXACT to s.statusExact,
            MatchStatus.NAME to s.statusName,
            MatchStatus.IN_COLLECTION to s.statusCollection,
            MatchStatus.NONE to s.statusNone,
            MatchStatus.UNKNOWN to s.statusUnknown,
        ).forEach { (st, label) ->
            val (icon, color) = statusIcon(st)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("$label: ${counts[st] ?: 0}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ResultRow(row: CompareRow, onClick: () -> Unit) {
    val s = LocalStrings.current
    val (icon, color) = statusIcon(row.status)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = row.card != null, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color)
        Spacer(Modifier.width(8.dp))
        CardThumb((row.edition ?: row.card?.editions?.firstOrNull())?.imageUrl, width = 32.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${row.line.quantity}× " + (row.card?.name ?: row.line.name),
                fontWeight = if (row.status == MatchStatus.EXACT) FontWeight.SemiBold else FontWeight.Normal,
            )
            val version = row.edition?.let { listOfNotNull(it.label, rarityLabel(it.rarity?.toInt())).joinToString(" ") }
                ?: row.line.footer?.let { "${it.setPrefix} ${it.number}" }
            SmallNote(
                listOfNotNull(
                    version,
                    if (row.line.foil) s.foil else null,
                    if (row.onMyList > 0) s.onMyList(row.onMyList) else null,
                    if (row.owned > 0) s.ownedBadge(row.owned) else null,
                    if (row.card == null) row.line.raw else null,
                ).joinToString(" · ")
            )
        }
    }
}
