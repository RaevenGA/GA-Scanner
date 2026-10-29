package com.gascanner.app.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gascanner.app.AppContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.scan.FooterCode
import com.gascanner.app.scan.ScanGuess
import com.gascanner.app.scan.ScanMode
import com.gascanner.app.scan.nameSimilarity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Candidate(
    val card: CardDto,
    val edition: EditionDto?,
    /** True when set + collector number matched exactly. */
    val exact: Boolean,
)

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Working : ScanUiState
    data class Result(val guess: ScanGuess, val candidates: List<Candidate>) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class ScanViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val mode: StateFlow<ScanMode> = c.settings.settings
        .map { it.scanMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanMode.ON_DEVICE)

    /** Only Claude mode needs a key; the default on-device mode is free. */
    val needsApiKey: StateFlow<Boolean> = c.settings.settings
        .map { it.scanMode == ScanMode.CLAUDE && it.anthropicApiKey.isBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun identify(jpeg: ByteArray) {
        _state.value = ScanUiState.Working
        viewModelScope.launch {
            try {
                val guess = c.recognizer(mode.value).recognize(jpeg)
                if (guess.name.isBlank() && guess.collectorNumber.isNullOrBlank() && guess.footerCodes.isEmpty()) {
                    _state.value = ScanUiState.Error(
                        "Couldn't read a card. Fill the frame with one card, avoid glare, and hold steady."
                    )
                    return@launch
                }
                _state.value = ScanUiState.Result(guess, findCandidates(guess))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ScanUiState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun fail(message: String) {
        _state.value = ScanUiState.Error(message)
    }

    fun reset() {
        _state.value = ScanUiState.Idle
    }

    fun quickAdd(candidate: Candidate, foil: Boolean) {
        val edition = candidate.edition ?: candidate.card.editions.firstOrNull() ?: return
        viewModelScope.launch {
            c.collection.add(candidate.card, edition, foil, 1)
            _messages.tryEmit("Added ${candidate.card.name} (${edition.label}${if (foil) ", foil" else ""})")
        }
    }

    private suspend fun findCandidates(guess: ScanGuess): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val nameQueries = (listOf(guess.name) + guess.nameLines).filter { it.isNotBlank() }.distinct()

        // 1) Footer lookup (set code + collector number) — identifies the exact edition.
        val codes = buildList {
            if (!guess.setPrefix.isNullOrBlank() && !guess.collectorNumber.isNullOrBlank()) {
                add(FooterCode(guess.setPrefix.trim(), guess.collectorNumber.trim()))
            }
            addAll(guess.footerCodes)
        }.distinct().take(MAX_FOOTER_TRIES)

        for (code in codes) {
            val card = runCatching { c.gatcg.cardBySetAndNumber(code.setPrefix, code.number) }.getOrNull() ?: continue
            // A misread footer can point at the wrong card; if we also read a name, require it to agree.
            val agrees = nameQueries.isEmpty() || nameQueries.any { nameSimilarity(it, card.name) >= 0.5 }
            if (!agrees) continue
            val edition = card.allEditions.firstOrNull {
                it.setPrefix.equals(code.setPrefix, ignoreCase = true) && it.collectorNumber == code.number
            } ?: card.resultEditions.firstOrNull() ?: card.editions.firstOrNull()
            out += Candidate(card, edition, exact = true)
            break
        }

        // 2) Name search, ranked by similarity to what was read (tolerates OCR typos).
        if (nameQueries.isNotEmpty()) {
            val found = linkedMapOf<String, CardDto>()
            for (q in nameQueries.take(3)) {
                runCatching { c.gatcg.search(q).data }.getOrDefault(emptyList()).forEach { found.putIfAbsent(it.uuid, it) }
                if (found.isNotEmpty()) break
            }
            if (found.isEmpty()) {
                // OCR typo broke the search: retry with the longest clean word.
                nameQueries.flatMap { it.split(' ', ',') }.filter { it.length >= 4 }.maxByOrNull { it.length }?.let { word ->
                    runCatching { c.gatcg.search(word).data }.getOrDefault(emptyList()).forEach { found.putIfAbsent(it.uuid, it) }
                }
            }
            val wantedPrefix = codes.firstOrNull()?.setPrefix
            found.values
                .filter { card -> out.none { it.card.uuid == card.uuid } }
                .map { card -> card to nameQueries.maxOf { nameSimilarity(it, card.name) } }
                .filter { it.second >= 0.45 }
                .sortedByDescending { it.second }
                .take(6)
                .forEach { (card, _) ->
                    val edition = card.allEditions.firstOrNull {
                        wantedPrefix != null && it.setPrefix.equals(wantedPrefix, ignoreCase = true)
                    } ?: card.editions.firstOrNull()
                    out += Candidate(card, edition, exact = false)
                }
        }
        return out
    }

    private companion object {
        const val MAX_FOOTER_TRIES = 4
    }
}
