package com.gascanner.app.data.csv

import com.gascanner.app.data.cache.CardHit
import com.gascanner.app.data.cache.CardRepository
import com.gascanner.app.data.db.CollectionEntry
import com.gascanner.app.data.db.CollectionRepository
import com.gascanner.app.data.lists.TradeEntry
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.data.lists.TradeRepository
import com.gascanner.app.data.settings.AddTarget
import com.gascanner.app.util.normalizePrefix
import kotlinx.coroutines.CancellationException

data class ImportResult(val added: Int, val failed: List<String>)

class ImportExport(
    private val cards: CardRepository,
    private val collection: CollectionRepository,
    private val trade: TradeRepository,
) {
    fun collectionCsv(entries: List<CollectionEntry>): String = Csv.write(
        listOf(CardCsv.COLLECTION_HEADER) + entries.map {
            listOf(it.cardName, it.setPrefix, it.collectorNumber, if (it.foil) "1" else "0", it.quantity.toString(),
                it.setName, it.editionUuid, it.cardSlug)
        }
    )

    fun tradeCsv(entries: List<TradeEntry>): String = Csv.write(
        listOf(CardCsv.TRADE_HEADER) + entries.map {
            listOf(it.listType.lowercase(), it.cardName, it.setPrefix, it.collectorNumber, if (it.foil) "1" else "0",
                it.quantity.toString(), it.price?.let { p -> "%.2f".format(java.util.Locale.US, p) }.orEmpty(),
                it.currency.orEmpty(), it.note, it.setName, it.editionUuid, it.cardSlug)
        }
    )

    /** Adds every row to [target]; with [replace] the target list is emptied first. */
    suspend fun import(text: String, target: AddTarget, replace: Boolean, onProgress: (Int, Int) -> Unit = { _, _ -> }): ImportResult {
        val rows = CardCsv.readRows(text)
        if (rows.isEmpty()) return ImportResult(0, listOf("No cards found. The file needs a header row with at least a name column."))
        if (replace) when (target) {
            AddTarget.COLLECTION -> collection.clear()
            AddTarget.SELL -> trade.clear(TradeListType.SELL)
            AddTarget.BUY -> trade.clear(TradeListType.BUY)
        }
        var added = 0
        val failed = mutableListOf<String>()
        rows.forEachIndexed { i, row ->
            val hit = try {
                resolve(row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val edition = hit?.edition ?: hit?.card?.editions?.firstOrNull()
            if (hit == null || edition == null) {
                failed += listOf(row.name, row.setPrefix, row.number).filter { it.isNotBlank() }.joinToString(" ")
            } else {
                when (target) {
                    AddTarget.COLLECTION -> collection.add(hit.card, edition, row.foil, row.quantity)
                    AddTarget.SELL, AddTarget.BUY -> trade.addRaw(
                        if (target == AddTarget.SELL) TradeListType.SELL else TradeListType.BUY,
                        hit.card.slug, hit.card.name, edition.uuid, edition.setPrefix, edition.set?.name.orEmpty(),
                        edition.collectorNumber, edition.imageUrl, row.foil, row.quantity, row.price, row.currency, row.note,
                    )
                }
                added += row.quantity
            }
            onProgress(i + 1, rows.size)
        }
        return ImportResult(added, failed)
    }

    private suspend fun resolve(row: ImportRow): CardHit? {
        if (row.editionUuid.isNotBlank()) cards.byEditionUuid(row.editionUuid)?.let { return it }
        if (row.setPrefix.isNotBlank() && row.number.isNotBlank()) {
            val hits = cards.byFooter(row.setPrefix, row.number)
            val exact = hits.firstOrNull { normalizePrefix(it.edition?.setPrefix.orEmpty()) == normalizePrefix(row.setPrefix) }
            (exact ?: hits.firstOrNull())?.let { return it }
        }
        if (row.name.isBlank()) return null
        val found = cards.search(row.name).cards
        val card = found.firstOrNull { it.name.equals(row.name, ignoreCase = true) } ?: found.firstOrNull() ?: return null
        val edition = card.allEditions.firstOrNull {
            row.setPrefix.isNotBlank() && normalizePrefix(it.setPrefix) == normalizePrefix(row.setPrefix)
        } ?: card.editions.firstOrNull()
        return CardHit(card, edition)
    }
}
