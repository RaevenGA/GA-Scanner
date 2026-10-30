package com.gascanner.app.data.db

import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.GameFormat
import com.gascanner.app.data.api.legalityLimit

/** A rule problem in a deck. Text is built by the UI from these values, so it can be translated. */
sealed interface DeckIssue {
    data class MainTooSmall(val count: Int, val min: Int) : DeckIssue
    data class MaterialTooBig(val count: Int, val max: Int) : DeckIssue
    data class NoLevelZeroChampion(val dummy: Unit = Unit) : DeckIssue
    data class TooManyCopies(val cardName: String, val count: Int, val max: Int) : DeckIssue
    data class MaterialDuplicate(val cardName: String, val count: Int) : DeckIssue
    data class SideboardTooBig(val count: Int, val points: Int) : DeckIssue
    data class Banned(val cardName: String) : DeckIssue
    data class OverLimit(val cardName: String, val count: Int, val limit: Int) : DeckIssue
    data class MainHasMaterial(val cardName: String) : DeckIssue
    data class MaterialHasMain(val cardName: String) : DeckIssue
}

data class DeckCheck(
    val format: GameFormat,
    val issues: List<DeckIssue>,
    val mainCount: Int,
    val materialCount: Int,
    val sideboardCount: Int,
    /** Cards we couldn't look up (offline data missing) — their rules weren't checked. */
    val unknownCards: Int,
) {
    val legal: Boolean get() = issues.isEmpty()
}

/**
 * Deck-building rules from the Grand Archive tournament rules (trg.gatcg.com):
 *  Standard: main deck ≥ 60, max 4 copies per card; material deck ≤ 12, one copy each, at least one Lv 0 champion;
 *  sideboard ≤ 15 cards and ≤ 15 points (main-deck cards 1 point, champions/regalia 3 points).
 *  Draft: main deck ≥ 30, no copy limits.
 *  Banned and restricted cards come from each card's `legality` data in the card database.
 */
object DeckValidator {
    fun check(format: GameFormat, cards: List<DeckCard>, info: Map<String, CardDto>): DeckCheck {
        val issues = mutableListOf<DeckIssue>()
        val main = cards.filter { it.section == DeckSection.MAIN.name }
        val material = cards.filter { it.section == DeckSection.MATERIAL.name }
        val side = cards.filter { it.section == DeckSection.SIDEBOARD.name }
        val mainCount = main.sumOf { it.quantity }
        val materialCount = material.sumOf { it.quantity }
        val sideCount = side.sumOf { it.quantity }
        val unknown = cards.map { it.cardSlug }.distinct().count { it !in info }

        when (format) {
            GameFormat.STANDARD, GameFormat.PANTHEON -> {
                if (mainCount < 60) issues += DeckIssue.MainTooSmall(mainCount, 60)
                if (materialCount > 12) issues += DeckIssue.MaterialTooBig(materialCount, 12)
                val hasLv0 = material.any { info[it.cardSlug]?.let { c -> c.isChampion && c.levelNumber == 0 } == true }
                if (material.isNotEmpty() && !hasLv0 && material.all { it.cardSlug in info }) {
                    issues += DeckIssue.NoLevelZeroChampion()
                }
                if (material.isEmpty()) issues += DeckIssue.NoLevelZeroChampion()
                main.groupBy { it.cardName }.forEach { (name, list) ->
                    val n = list.sumOf { it.quantity }
                    if (n > 4) issues += DeckIssue.TooManyCopies(name, n, 4)
                }
                material.groupBy { it.cardName }.forEach { (name, list) ->
                    val n = list.sumOf { it.quantity }
                    if (n > 1) issues += DeckIssue.MaterialDuplicate(name, n)
                }
                val points = side.sumOf { c -> c.quantity * (if (info[c.cardSlug]?.isMaterial == true) 3 else 1) }
                if (sideCount > 15 || points > 15) issues += DeckIssue.SideboardTooBig(sideCount, points)
            }
            GameFormat.DRAFT -> {
                if (mainCount < 30) issues += DeckIssue.MainTooSmall(mainCount, 30)
            }
        }

        // Cards in the wrong section.
        main.forEach { c -> if (info[c.cardSlug]?.isMaterial == true) issues += DeckIssue.MainHasMaterial(c.cardName) }
        material.forEach { c -> if (info[c.cardSlug]?.let { !it.isMaterial } == true) issues += DeckIssue.MaterialHasMain(c.cardName) }

        // Banned / restricted for this format.
        cards.groupBy { it.cardSlug }.forEach { (slug, list) ->
            val card = info[slug] ?: return@forEach
            val limit = card.legalityLimit(format) ?: return@forEach
            val n = list.sumOf { it.quantity }
            if (limit == 0) issues += DeckIssue.Banned(card.name)
            else if (n > limit) issues += DeckIssue.OverLimit(card.name, n, limit)
        }

        return DeckCheck(format, issues.distinct(), mainCount, materialCount, sideCount, unknown)
    }
}
