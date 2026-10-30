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
    data class MaterialNotExact(val count: Int, val required: Int) : DeckIssue
    data class NoSideboardAllowed(val count: Int) : DeckIssue
    data class BoonsNeeded(val lesser: Int, val greater: Int) : DeckIssue
    data class BoonsOnlyInPantheon(val count: Int) : DeckIssue
    data class BoonInWrongSection(val cardName: String) : DeckIssue
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
 *  Pantheon: main deck ≥ 60 with one copy per card; material deck exactly 12 with a Lv 0 champion;
 *  one Lesser and one Greater Boon; no sideboard.
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

        val boons = cards.filter { it.section == DeckSection.BOONS.name }
        val boonCount = boons.sumOf { it.quantity }

        when (format) {
            GameFormat.STANDARD -> {
                if (mainCount < 60) issues += DeckIssue.MainTooSmall(mainCount, 60)
                if (materialCount > 12) issues += DeckIssue.MaterialTooBig(materialCount, 12)
                if (!hasLevelZero(material, info)) issues += DeckIssue.NoLevelZeroChampion()
                copyLimit(main, 4, issues)
                materialSingles(material, issues)
                val points = side.sumOf { c -> c.quantity * (if (info[c.cardSlug]?.isMaterial == true) 3 else 1) }
                if (sideCount > 15 || points > 15) issues += DeckIssue.SideboardTooBig(sideCount, points)
                if (boonCount > 0) issues += DeckIssue.BoonsOnlyInPantheon(boonCount)
            }
            GameFormat.PANTHEON -> {
                // Official Pantheon rules: main >= 60 with one copy of each card; material exactly 12
                // with a level 0 champion; one Lesser + one Greater Boon; no sideboard.
                if (mainCount < 60) issues += DeckIssue.MainTooSmall(mainCount, 60)
                if (materialCount != 12) issues += DeckIssue.MaterialNotExact(materialCount, 12)
                if (!hasLevelZero(material, info)) issues += DeckIssue.NoLevelZeroChampion()
                copyLimit(main, 1, issues)
                materialSingles(material, issues)
                if (sideCount > 0) issues += DeckIssue.NoSideboardAllowed(sideCount)
                val lesser = boons.filter { info[it.cardSlug]?.isLesserBoon == true }.sumOf { it.quantity }
                val greater = boons.filter { info[it.cardSlug]?.isGreaterBoon == true }.sumOf { it.quantity }
                val unknownBoons = boons.any { it.cardSlug !in info }
                if (!unknownBoons && (lesser != 1 || greater != 1 || boonCount != 2)) issues += DeckIssue.BoonsNeeded(lesser, greater)
            }
            GameFormat.DRAFT -> {
                if (mainCount < 30) issues += DeckIssue.MainTooSmall(mainCount, 30)
                if (boonCount > 0) issues += DeckIssue.BoonsOnlyInPantheon(boonCount)
            }
        }

        // Boons belong in their own section, and only there.
        (main + material + side).forEach { c -> if (info[c.cardSlug]?.isBoon == true) issues += DeckIssue.BoonInWrongSection(c.cardName) }
        boons.forEach { c -> if (info[c.cardSlug]?.let { !it.isBoon } == true) issues += DeckIssue.BoonInWrongSection(c.cardName) }

        // Cards in the wrong section.
        main.forEach { c -> if (info[c.cardSlug]?.let { it.isMaterial && !it.isBoon } == true) issues += DeckIssue.MainHasMaterial(c.cardName) }
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

    private fun hasLevelZero(material: List<DeckCard>, info: Map<String, CardDto>): Boolean {
        if (material.isEmpty()) return false
        // Can't tell without card data: don't report a problem we can't verify.
        if (material.any { it.cardSlug !in info }) return true
        return material.any { info[it.cardSlug]?.let { c -> c.isChampion && c.levelNumber == 0 } == true }
    }

    private fun copyLimit(main: List<DeckCard>, max: Int, issues: MutableList<DeckIssue>) {
        main.groupBy { it.cardName }.forEach { (name, list) ->
            val n = list.sumOf { it.quantity }
            if (n > max) issues += DeckIssue.TooManyCopies(name, n, max)
        }
    }

    private fun materialSingles(material: List<DeckCard>, issues: MutableList<DeckIssue>) {
        material.groupBy { it.cardName }.forEach { (name, list) ->
            val n = list.sumOf { it.quantity }
            if (n > 1) issues += DeckIssue.MaterialDuplicate(name, n)
        }
    }
}
