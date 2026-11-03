package com.gascanner.app.data.compare

import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.cache.CardRepository
import com.gascanner.app.data.db.CollectionEntry
import com.gascanner.app.data.lists.TradeEntry
import com.gascanner.app.scan.CardTextParser
import com.gascanner.app.scan.FooterCode
import com.gascanner.app.util.nameSimilarity
import com.gascanner.app.util.normalizePrefix
import kotlinx.coroutines.CancellationException

/** One line of someone else's list, before it is matched to a card. */
data class PastedLine(
    val raw: String,
    val quantity: Int,
    val name: String,
    val footer: FooterCode?,
    val foil: Boolean,
)

/**
 * Reads lists pasted from chats or other apps, e.g.
 *   "2× Lorraine, Wandering Warrior (DOA 1st 001, foil) — 12,00 €"   (this app's share format)
 *   "2x Crescent Glaive - AMB 010", "Crescent Glaive x3", "1 Spirit of Fire", "- Slime Shield"
 */
object ListParser {
    private val skipLine = Regex("""(?i)^(wts|wtb|want to (sell|buy)|sprzedam|kupię|kupie|total|razem|made with|lista z|gesamt|suche|verkaufe|vendo|busco|à vendre|recherche)\b""")
    private val leadingQty = Regex("""^(\d{1,3})\s*[x×*]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val trailingQty = Regex("""^(.+?)\s+[x×*]\s*(\d{1,3})$""", RegexOption.IGNORE_CASE)
    private val price = Regex("""\s+[—–\-|:]\s*[≈~]?\s*[\d.,]+\s*(zł|zl|pln|€|eur|\$|usd|£|gbp)?\s*$""", RegexOption.IGNORE_CASE)
    private val pricePrefix = Regex("""^[≈~]?\s*(zł|zl|pln|€|eur|\$|usd|£|gbp)?\s*[\d.,]+\s*(zł|zl|pln|€|eur|\$|usd|£|gbp)""", RegexOption.IGNORE_CASE)
    private val parens = Regex("""[(\[]([^)\]]*)[)\]]""")

    fun parse(text: String): List<PastedLine> = text.lines().mapNotNull(::parseLine)

    fun parseLine(line: String): PastedLine? {
        var t = line.trim().trimStart('-', '•', '*', '·', '–', '—', '>', ' ').trim()
        if (t.length < 3 || skipLine.containsMatchIn(t) || !t.any { it.isLetter() }) return null
        if (pricePrefix.containsMatchIn(t) && t.count { it.isLetter() } < 5) return null

        // Price at the end ("— 12,00 €"), repeated for "≈ 3 zł — note" style lines.
        repeat(2) { t = t.replace(price, "").trim() }

        // Version / foil inside brackets: "(DOA 1st 001, foil)".
        var footer: FooterCode? = null
        var foil = false
        parens.findAll(t).forEach { m ->
            val inner = m.groupValues[1]
            CardTextParser.footerCodesIn(inner).firstOrNull()?.let { footer = footer ?: it }
            if (isFoil(inner)) foil = true
        }
        t = t.replace(parens, " ").replace(Regex("\\s+"), " ").trim()

        var qty = 1
        leadingQty.find(t)?.let { qty = it.groupValues[1].toInt(); t = it.groupValues[2] }
            ?: trailingQty.find(t)?.let { qty = it.groupValues[2].toInt(); t = it.groupValues[1] }

        // "foil" may come after the version, which is cut off below, so look for it first.
        if (isFoil(t)) foil = true

        // Version written after the name: "Crescent Glaive - AMB 010", "Crescent Glaive AMB 1st 010 foil".
        if (footer == null) {
            val codes = CardTextParser.footerCodesIn(t)
            codes.firstOrNull()?.let { code ->
                footer = code
                val idx = t.uppercase().indexOf(code.setPrefix.substringBefore(' ').uppercase())
                if (idx > 0) t = t.substring(0, idx)
            }
        }
        if (isFoil(t)) {
            foil = true
            t = t.replace(Regex("(?i)\\bfoil\\b"), "")
        }
        val name = t.trim().trim('-', '—', '–', ',', '|', ':', ' ')
        if (name.count { it.isLetter() } < 3) return null
        return PastedLine(line.trim(), qty.coerceAtLeast(1), name, footer, foil)
    }

    private fun isFoil(s: String): Boolean {
        val l = s.lowercase()
        return Regex("\\bfoil\\b").containsMatchIn(l) && !l.contains("non-foil") && !l.contains("nonfoil")
    }
}

enum class MatchStatus {
    /** Same card and printing on my list. */
    EXACT,
    /** Same card on my list, different (or unknown) printing. */
    NAME,
    /** Not on my list, but in my collection. */
    IN_COLLECTION,
    NONE,
    /** Couldn't find this card. */
    UNKNOWN,
}

data class CompareRow(
    val line: PastedLine,
    val card: CardDto?,
    val edition: EditionDto?,
    val status: MatchStatus,
    /** Copies of this card on my list / in my collection. */
    val onMyList: Int,
    val owned: Int,
)

class ListComparer(private val cards: CardRepository) {

    suspend fun compare(text: String, myList: List<TradeEntry>, collection: List<CollectionEntry>): List<CompareRow> {
        val bySlugList = myList.groupBy { it.cardSlug }
        val bySlugOwned = collection.groupBy { it.cardSlug }
        return ListParser.parse(text).map { line ->
            val (card, edition) = try {
                resolve(line)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null to null
            }
            if (card == null) return@map CompareRow(line, null, null, MatchStatus.UNKNOWN, 0, 0)
            val mine = bySlugList[card.slug].orEmpty()
            val owned = bySlugOwned[card.slug].orEmpty()
            val status = when {
                edition != null && line.footer != null && mine.any { it.editionUuid == edition.uuid } -> MatchStatus.EXACT
                mine.isNotEmpty() -> MatchStatus.NAME
                owned.isNotEmpty() -> MatchStatus.IN_COLLECTION
                else -> MatchStatus.NONE
            }
            CompareRow(line, card, edition, status, mine.sumOf { it.quantity }, owned.sumOf { it.quantity })
        }
    }

    private suspend fun resolve(line: PastedLine): Pair<CardDto?, EditionDto?> {
        // Name first…
        val card = if (cards.offlineCount() > 0) {
            cards.fuzzyByName(line.name, limit = 1, minScore = MIN_SCORE).firstOrNull()?.let { cards.card(it.first.slug) }
        } else {
            cards.search(line.name).cards.maxByOrNull { nameSimilarity(line.name, it.name) }
                ?.takeIf { nameSimilarity(line.name, it.name) >= MIN_SCORE }
        } ?: return null to null
        // …then the printing, if the line names one.
        val code = line.footer ?: return card to null
        val edition = card.allEditions.firstOrNull {
            normalizePrefix(it.setPrefix) == normalizePrefix(code.setPrefix) &&
                it.collectorNumber.trimStart('0') == code.number.trimStart('0')
        } ?: card.allEditions.firstOrNull { normalizePrefix(it.setPrefix) == normalizePrefix(code.setPrefix) }
        return card to edition
    }

    private companion object {
        const val MIN_SCORE = 0.8
    }
}
