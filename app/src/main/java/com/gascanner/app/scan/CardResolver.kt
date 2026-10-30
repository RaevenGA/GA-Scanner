package com.gascanner.app.scan

import android.graphics.Bitmap
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.cache.CardRepository
import com.gascanner.app.util.nameSimilarity
import com.gascanner.app.util.normalizePrefix
import kotlinx.coroutines.CancellationException

data class Candidate(
    val card: CardDto,
    val edition: EditionDto?,
    /** Set code + collector number matched. */
    val exact: Boolean,
    /** Edition chosen by comparing the artwork. */
    val artMatched: Boolean = false,
)

/** Turns what was read off a card into database cards. Works offline once the card database is downloaded. */
class CardResolver(private val cards: CardRepository, private val art: ArtMatcher) {

    suspend fun resolve(guess: ScanGuess, cardImage: Bitmap?, allowNetwork: Boolean = true): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val nameQueries = (listOf(guess.name) + guess.nameLines).filter { it.isNotBlank() }.distinct()

        // 1) Footer (set code + collector number) identifies the exact printing.
        val codes = buildList {
            val p = guess.setPrefix
            val n = guess.collectorNumber
            if (!p.isNullOrBlank() && !n.isNullOrBlank()) add(FooterCode(p.trim(), n.trim()))
            addAll(guess.footerCodes)
        }.distinct().take(MAX_FOOTER_TRIES)

        for (code in codes) {
            val hits = cards.byFooter(code.setPrefix, code.number, allowNetwork)
            // A misread footer can point at another card; if a name was read too, it has to agree.
            val agreeing = hits.filter { h -> nameQueries.isEmpty() || nameQueries.any { nameSimilarity(it, h.card.name) >= 0.5 } }
            if (agreeing.isEmpty()) continue
            val wanted = normalizePrefix(code.setPrefix)
            agreeing.sortedByDescending { normalizePrefix(it.edition?.setPrefix.orEmpty()) == wanted }
                .forEach { out += Candidate(it.card, it.edition, exact = true) }
            break
        }

        // 2) Name matching, tolerant to OCR typos.
        if (nameQueries.isNotEmpty()) {
            val wantedPrefix = codes.firstOrNull()?.setPrefix
            for (card in byName(nameQueries, allowNetwork)) {
                if (out.any { it.card.uuid == card.uuid }) continue
                val edition = card.allEditions.firstOrNull {
                    wantedPrefix != null && normalizePrefix(it.setPrefix) == normalizePrefix(wantedPrefix)
                } ?: card.editions.firstOrNull()
                out += Candidate(card, edition, exact = false)
            }
        }

        // 3) Pick the printing by artwork when the footer didn't settle it.
        if (cardImage != null && out.isNotEmpty() && !out.first().exact) {
            val top = out.first()
            val match = runCatching { art.bestEdition(cardImage, top.card.allEditions) }.getOrNull()
            if (match != null) out[0] = top.copy(edition = match.edition, artMatched = true)
        }
        return out
    }

    /** Several cards from one photo. Each card appears once. */
    suspend fun resolveMany(guesses: List<ScanGuess>, allowNetwork: Boolean = true): List<Candidate> {
        val out = linkedMapOf<String, Candidate>()
        for (g in guesses.filter { it.footerCodes.isNotEmpty() || !it.collectorNumber.isNullOrBlank() }) {
            val p = g.setPrefix ?: g.footerCodes.firstOrNull()?.setPrefix ?: continue
            val n = g.collectorNumber ?: g.footerCodes.firstOrNull()?.number ?: continue
            val hit = cards.byFooter(p, n, allowNetwork).firstOrNull() ?: continue
            val key = hit.edition?.uuid ?: hit.card.uuid
            if (key !in out) out[key] = Candidate(hit.card, hit.edition, exact = true)
        }
        val offline = cards.offlineCount() > 0
        var networkNames = 0
        for (g in guesses.filter { it.name.isNotBlank() }) {
            val card = if (offline) {
                val best = cards.fuzzyByName(g.name, limit = 1, minScore = STRONG_NAME).firstOrNull() ?: continue
                runCatching { cards.card(best.first.slug) }.getOrNull() ?: continue
            } else {
                if (!allowNetwork || networkNames++ >= MAX_NETWORK_NAMES) continue
                byName(listOf(g.name), true).firstOrNull()?.takeIf { nameSimilarity(g.name, it.name) >= STRONG_NAME } ?: continue
            }
            if (out.values.any { it.card.uuid == card.uuid }) continue
            out[card.uuid] = Candidate(card, card.editions.firstOrNull(), exact = false)
        }
        return out.values.toList()
    }

    private suspend fun byName(queries: List<String>, allowNetwork: Boolean): List<CardDto> {
        if (cards.offlineCount() > 0) {
            val scored = queries.take(4).flatMap { q -> cards.fuzzyByName(q, limit = 6, minScore = MIN_NAME) }
                .groupBy { it.first.uuid }
                .map { (_, v) -> v.maxBy { it.second } }
                .sortedByDescending { it.second }
                .take(6)
            return scored.mapNotNull { runCatching { cards.card(it.first.slug) }.getOrNull() }
        }
        if (!allowNetwork) return emptyList()
        val found = linkedMapOf<String, CardDto>()
        for (q in queries.take(3)) {
            try {
                cards.search(q).cards.forEach { found.putIfAbsent(it.uuid, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            if (found.isNotEmpty()) break
        }
        return found.values
            .map { c -> c to queries.maxOf { nameSimilarity(it, c.name) } }
            .filter { it.second >= MIN_NAME }
            .sortedByDescending { it.second }
            .take(6)
            .map { it.first }
    }

    private companion object {
        const val MAX_FOOTER_TRIES = 4
        const val MIN_NAME = 0.5
        const val STRONG_NAME = 0.85
        const val MAX_NETWORK_NAMES = 8
    }
}
