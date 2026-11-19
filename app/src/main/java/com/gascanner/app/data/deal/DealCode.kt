package com.gascanner.app.data.deal

/** One card in a trade code: printing, foil and number of copies. */
data class CodeLine(val editionUuid: String, val foil: Boolean, val quantity: Int)

/**
 * Text carried by the trade QR codes:
 *   GAT1|<part>/<parts>|<id>|uuid.qty[.f],uuid.qty[.f],…
 * A long side is split over several codes (same <id>), shown one after another.
 * Names and prices aren't included: each phone looks the printings up in its own card database.
 */
object DealCode {
    const val PREFIX = "GAT1"
    /** Characters of card data per QR, small enough to scan quickly from another phone's screen. */
    const val PART_SIZE = 700

    fun encode(lines: List<CodeLine>, id: String = randomId()): List<String> {
        val entries = lines.filter { it.quantity > 0 && it.editionUuid.isNotBlank() }
            .map { "${it.editionUuid}.${it.quantity}" + if (it.foil) ".f" else "" }
        val chunks = mutableListOf<MutableList<String>>(mutableListOf())
        var size = 0
        for (e in entries) {
            if (size + e.length + 1 > PART_SIZE && chunks.last().isNotEmpty()) {
                chunks += mutableListOf<String>()
                size = 0
            }
            chunks.last() += e
            size += e.length + 1
        }
        return chunks.mapIndexed { i, c -> "$PREFIX|${i + 1}/${chunks.size}|$id|${c.joinToString(",")}" }
    }

    /** One scanned code. */
    data class Part(val index: Int, val count: Int, val id: String, val lines: List<CodeLine>)

    fun parse(text: String): Part? {
        val bits = text.trim().split('|')
        if (bits.size != 4 || bits[0] != PREFIX) return null
        val (index, count) = bits[1].split('/').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        if (index !in 1..count || count > 50) return null
        val lines = bits[3].split(',').filter { it.isNotBlank() }.mapNotNull { e ->
            val p = e.split('.')
            val qty = p.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            if (p[0].isBlank() || qty !in 1..999) return@mapNotNull null
            CodeLine(p[0], p.getOrNull(2) == "f", qty)
        }
        return Part(index, count, bits[2], lines)
    }

    private fun randomId(): String = (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
}

/** Collects the codes of one side until every part has been scanned. */
class DealCodeCollector {
    private var id: String? = null
    private val parts = sortedMapOf<Int, DealCode.Part>()
    var count: Int = 0
        private set

    val received: Int get() = parts.size
    val complete: Boolean get() = count > 0 && parts.size == count

    /** Returns true when the code was new (not a repeat or from another trade). */
    fun add(part: DealCode.Part): Boolean {
        if (id != part.id) {
            id = part.id
            parts.clear()
            count = part.count
        }
        if (part.index in parts) return false
        parts[part.index] = part
        return true
    }

    fun lines(): List<CodeLine> = parts.values.flatMap { it.lines }
}
