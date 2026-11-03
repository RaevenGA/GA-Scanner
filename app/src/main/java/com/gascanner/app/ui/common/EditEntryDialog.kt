package com.gascanner.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gascanner.app.appContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.settings.AppSettings
import com.gascanner.app.i18n.LocalStrings
import com.gascanner.app.scan.rarityLabel
import com.gascanner.app.ui.card.sectionLabel

/** What the user chose in [EditEntryDialog]. */
data class EntryEdit(
    val card: CardDto,
    val edition: EditionDto,
    val foil: Boolean,
    val quantity: Int,
    val section: DeckSection?,
    val price: Double?,
    val currency: String?,
    val note: String,
)

/** Optional price fields, for the sell / buy lists. */
data class PriceFields(val isSell: Boolean, val price: Double?, val currency: String?, val note: String, val defaultCurrency: String)

/**
 * Fix a card in the collection, a list or a deck: pick the right printing, foil, quantity
 * and (in decks) the section. Changing the printing merges with an existing entry for it.
 */
@Composable
fun EditEntryDialog(
    cardSlug: String,
    cardName: String,
    editionUuid: String,
    foil: Boolean?,
    quantity: Int,
    section: DeckSection?,
    priceFields: PriceFields?,
    onDismiss: () -> Unit,
    onSave: (EntryEdit) -> Unit,
) {
    val s = LocalStrings.current
    val container = appContainer()
    val card by produceState<CardDto?>(null, cardSlug) { value = runCatching { container.cards.card(cardSlug) }.getOrNull() }
    var selected by remember { mutableStateOf(editionUuid) }
    var isFoil by remember { mutableStateOf(foil ?: false) }
    var qty by remember { mutableIntStateOf(quantity) }
    var sec by remember { mutableStateOf(section) }
    var price by remember { mutableStateOf(priceFields?.price?.let { "%.2f".format(java.util.Locale.US, it) }.orEmpty()) }
    var currency by remember { mutableStateOf(priceFields?.currency ?: priceFields?.defaultCurrency ?: "EUR") }
    var note by remember { mutableStateOf(priceFields?.note.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cardName) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.version, style = MaterialTheme.typography.labelLarge)
                val editions = card?.editions.orEmpty()
                if (card == null) Loading()
                editions.forEach { e -> EditionRow(e, selected == e.uuid) { selected = e.uuid; isFoil = e.fixFoil(isFoil) } }

                if (foil != null) {
                    val ed = editions.firstOrNull { it.uuid == selected }
                    LaunchedEffect(ed?.uuid) { ed?.let { isFoil = it.fixFoil(isFoil) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(com.gascanner.app.ui.card.finishLabel(s, ed), Modifier.weight(1f))
                        Switch(checked = isFoil, enabled = ed?.hasBothFinishes ?: true, onCheckedChange = { isFoil = it })
                    }
                }
                if (section != null) {
                    Text(s.deckSection, style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DeckSection.entries.forEach { d ->
                            FilterChip(selected = sec == d, onClick = { sec = d }, label = { Text(sectionLabel(s, d)) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.quantity, Modifier.weight(1f))
                    QuantityStepper(qty, onChange = { qty = (qty + it).coerceAtLeast(0) })
                }
                if (priceFields != null) {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text(if (priceFields.isSell) s.askingPrice else s.maxPrice) },
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
                if (qty == 0) SmallNote(s.willBeRemoved, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = card != null,
                onClick = {
                    val c = card ?: return@TextButton
                    val e = c.allEditions.firstOrNull { it.uuid == selected } ?: c.editions.firstOrNull() ?: return@TextButton
                    val p = price.replace(",", ".").toDoubleOrNull()
                    onSave(EntryEdit(c, e, isFoil, qty, sec, p, if (p != null) currency else null, note))
                },
            ) { Text(s.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

@Composable
private fun EditionRow(e: EditionDto, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        CardThumb(e.imageUrl, width = 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(e.label, rarityLabel(e.rarity?.toInt())).joinToString(" "), style = MaterialTheme.typography.bodyMedium)
            e.set?.name?.let { SmallNote(it) }
        }
    }
    Spacer(Modifier.height(2.dp))
}
