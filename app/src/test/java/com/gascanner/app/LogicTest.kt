package com.gascanner.app

import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.GameFormat
import com.gascanner.app.data.api.legalityLimit
import com.gascanner.app.data.csv.CardCsv
import com.gascanner.app.data.csv.Csv
import com.gascanner.app.data.db.DeckCard
import com.gascanner.app.data.db.DeckIssue
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.db.DeckValidator
import com.gascanner.app.data.prices.StorePriceProvider
import com.gascanner.app.data.settings.PriceSourceConfig
import com.gascanner.app.data.settings.PriceSourceKind
import com.gascanner.app.i18n.StringsPl
import com.gascanner.app.scan.CardTextParser
import com.gascanner.app.scan.FooterCode
import com.gascanner.app.scan.OcrLine
import com.gascanner.app.util.nameSimilarity
import com.gascanner.app.util.normalizeNumber
import com.gascanner.app.util.normalizePrefix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FooterParsingTest {
    @Test fun readsCommonFooters() {
        assertEquals(listOf(FooterCode("AMB", "010")), CardTextParser.footerCodesIn("AMB · 010"))
        assertEquals(listOf(FooterCode("DOA 1st", "001")), CardTextParser.footerCodesIn("DOA 1st • 001 R"))
        assertEquals(listOf(FooterCode("ALC 1st", "120")), CardTextParser.footerCodesIn("ALC 1ST 12O"))
        assertEquals(listOf(FooterCode("HVN", "045")), CardTextParser.footerCodesIn("HVN-045 SR"))
    }

    @Test fun ignoresRulesText() {
        assertTrue(CardTextParser.footerCodesIn("LV 2").isEmpty())
        assertTrue(CardTextParser.footerCodesIn("Draw 2 cards").isEmpty())
        assertTrue(CardTextParser.footerCodesIn("© 2024 Weebs of the Shore").isEmpty())
    }

    @Test fun parsesNameFromTopAndFooterFromBottom() {
        val lines = listOf(
            OcrLine("Lorraine, Wandering Warrior", 10, 10, 400, 40),
            OcrLine("CHAMPION - WARRIOR", 10, 300, 400, 320),
            OcrLine("Draw 2 cards.", 10, 400, 400, 420),
            OcrLine("DOA 1st · 001", 10, 560, 200, 580),
        )
        val g = CardTextParser.parse(lines)
        assertEquals("Lorraine, Wandering Warrior", g.name)
        assertEquals("DOA 1st", g.setPrefix)
        assertEquals("001", g.collectorNumber)
    }

    @Test fun manyCardsInOnePhoto() {
        val lines = listOf(
            OcrLine("AMB · 010", 0, 500, 100, 520),
            OcrLine("DOA · 002", 300, 500, 400, 520),
        )
        val guesses = CardTextParser.parseMany(lines)
        assertEquals(2, guesses.count { it.footerCodes.isNotEmpty() })
    }
}

class TextTest {
    @Test fun normalizes() {
        assertEquals("DOA1ST", normalizePrefix("DOA 1st"))
        assertEquals("10", normalizeNumber("010"))
        assertEquals("P01", normalizeNumber("P01"))
    }

    @Test fun ocrTypos() {
        assertTrue(nameSimilarity("Lorralne, Wanderlng Warrior", "Lorraine, Wandering Warrior") > 0.85)
        assertTrue(nameSimilarity("Slime Shield", "Lorraine, Wandering Warrior") < 0.5)
        assertEquals(1.0, nameSimilarity("Crescent Glaive", "crescent glaive"), 0.0)
    }
}

class CsvTest {
    @Test fun roundTripWithQuotesAndCommas() {
        val text = Csv.write(listOf(listOf("name", "note"), listOf("Lorraine, Wandering Warrior", "say \"hi\"")))
        assertEquals(listOf(listOf("name", "note"), listOf("Lorraine, Wandering Warrior", "say \"hi\"")), Csv.parse(text))
    }

    @Test fun readsSemicolonAndPolishHeaders() {
        val rows = CardCsv.readRows("Nazwa;Dodatek;Nr;Foil;Ilość\nCrescent Glaive;AMB;010;tak;3\n")
        assertEquals(1, rows.size)
        val r = rows.first()
        assertEquals("Crescent Glaive", r.name)
        assertEquals("AMB", r.setPrefix)
        assertEquals("010", r.number)
        assertTrue(r.foil)
        assertEquals(3, r.quantity)
    }

    @Test fun exportHeaderIsReadBack() {
        val text = Csv.write(listOf(CardCsv.COLLECTION_HEADER, listOf("X", "AMB", "001", "1", "2", "Mortal Ambition", "abc123", "x")))
        val r = CardCsv.readRows(text).single()
        assertEquals("abc123", r.editionUuid)
        assertEquals(2, r.quantity)
        assertTrue(r.foil)
    }
}

class DeckRulesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun card(slug: String, types: List<String>, level: Int? = null, legality: String? = null) = CardDto(
        uuid = slug, slug = slug, name = slug, types = types,
        level = level?.let { JsonPrimitive(it) },
        legality = legality?.let { json.parseToJsonElement(it) },
    )
    private fun dc(slug: String, section: DeckSection, qty: Int) =
        DeckCard(deckId = 1, section = section.name, cardSlug = slug, cardName = slug, editionUuid = slug, setPrefix = "AMB", imageUrl = null, quantity = qty)

    @Test fun legalStandardDeck() {
        val info = mapOf(
            "champ" to card("champ", listOf("CHAMPION"), level = 0),
            "ally" to card("ally", listOf("ALLY")),
        ) + (1..15).associate { "c$it" to card("c$it", listOf("ACTION")) }
        val cards = listOf(dc("champ", DeckSection.MATERIAL, 1)) + (1..15).map { dc("c$it", DeckSection.MAIN, 4) }
        val check = DeckValidator.check(GameFormat.STANDARD, cards, info)
        assertTrue(check.issues.toString(), check.legal)
        assertEquals(60, check.mainCount)
    }

    @Test fun catchesProblems() {
        val info = mapOf(
            "champ" to card("champ", listOf("CHAMPION"), level = 1),
            "ally" to card("ally", listOf("ALLY"), legality = """{"STANDARD": {"limit": 0}}"""),
        )
        val cards = listOf(dc("champ", DeckSection.MATERIAL, 2), dc("ally", DeckSection.MAIN, 5))
        val issues = DeckValidator.check(GameFormat.STANDARD, cards, info).issues
        assertTrue(issues.any { it is DeckIssue.MainTooSmall })
        assertTrue(issues.any { it is DeckIssue.NoLevelZeroChampion })
        assertTrue(issues.any { it is DeckIssue.TooManyCopies })
        assertTrue(issues.any { it is DeckIssue.MaterialDuplicate })
        assertTrue(issues.any { it is DeckIssue.Banned })
    }

    @Test fun legalityShapes() {
        assertEquals(1, card("a", emptyList(), legality = """{"STANDARD": {"limit": 1}}""").legalityLimit(GameFormat.STANDARD))
        assertEquals(0, card("a", emptyList(), legality = """[{"format": "PANTHEON", "limit": 0}]""").legalityLimit(GameFormat.PANTHEON))
        assertNull(card("a", emptyList(), legality = """{"STANDARD": {"limit": 1}}""").legalityLimit(GameFormat.DRAFT))
        assertNull(card("a", emptyList()).legalityLimit(GameFormat.STANDARD))
    }
}

class StoreMatchingTest {
    @Test fun matchesWholeNameOnly() {
        assertEquals(2, StorePriceProvider.matchScore("Crescent Glaive (AMB 010) - Grand Archive", "Crescent Glaive", "AMB", "010"))
        assertEquals(1, StorePriceProvider.matchScore("Crescent Glaive [Foil]", "Crescent Glaive", "DOA", "005"))
        assertEquals(0, StorePriceProvider.matchScore("Lorraine, Wandering Warrior", "Lorraine, Crux Knight", null, null))
        assertEquals(0, StorePriceProvider.matchScore("Crescent Glaives Booster", "Crescent Glaive", null, null))
    }

    @Test fun foilDetection() {
        assertTrue(StorePriceProvider.looksFoil("Crescent Glaive (Foil)"))
        assertFalse(StorePriceProvider.looksFoil("Crescent Glaive (Non-Foil)"))
    }
}

class SettingsTest {
    @Test fun upgradeAddsNewShopsAndKeepsEdits() {
        val old = listOf(
            PriceSourceConfig(id = "tcgplayer-market", name = "TCG", kind = PriceSourceKind.TCGPLAYER_MARKET, enabled = false),
            PriceSourceConfig(id = "custom-1", name = "My shop", kind = PriceSourceKind.SEARCH_LINK, urlTemplate = "https://x/?q={name}"),
        )
        val merged = PriceSourceConfig.mergeWithDefaults(old)
        assertEquals("tcgplayer-market", merged[0].id)
        assertFalse(merged[0].enabled)
        assertEquals(listOf("caduko", "zarc"), merged.drop(1).take(2).map { it.id })
        assertTrue(merged.any { it.id == "custom-1" })
    }
}

class TranslationTest {
    @Test fun polishPlurals() {
        assertEquals("1 karta", StringsPl.cardsCount(1))
        assertEquals("3 karty", StringsPl.cardsCount(3))
        assertEquals("5 kart", StringsPl.cardsCount(5))
        assertEquals("12 kart", StringsPl.cardsCount(12))
        assertEquals("22 karty", StringsPl.cardsCount(22))
    }
}

class ProbeFindingsTest {
    @Test fun alterPrintings() {
        assertEquals(listOf(FooterCode("DTR Alter", "223")), CardTextParser.footerCodesIn("DTR ALTER • 223"))
        assertEquals("DTRALTER", normalizePrefix("DTR Alter"))
    }

    @Test fun cadukoListings() {
        // Real Caduko (WooCommerce) data: title with an HTML dash entity, set/number/finish in the SKU.
        val title = StorePriceProvider.stripHtml("Crescent Glaive &#8211; Non-Foil")
        assertEquals("Crescent Glaive – Non-Foil", title)
        assertEquals(2, StorePriceProvider.matchScore("$title AMB • 10 • C • NF", "Crescent Glaive", "AMB", "010"))
        assertFalse(StorePriceProvider.looksFoil("$title AMB • 10 • C • NF"))
        assertTrue(StorePriceProvider.looksFoil("Crescent Glaive – Foil AMB • 10 • C • Foil"))
    }
}
