package com.gascanner.app.ui.scan

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gascanner.app.AppContainer
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.data.settings.AddTarget
import com.gascanner.app.scan.Candidate
import com.gascanner.app.scan.CardTextParser
import com.gascanner.app.scan.Capture
import com.gascanner.app.scan.ScanGuess
import com.gascanner.app.scan.ScanMode
import com.gascanner.app.scan.guideCrop
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

enum class CaptureMode { LIVE, PHOTO, MULTI }

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data object Working : ScanUiState
    data class Result(val guess: ScanGuess, val candidates: List<Candidate>) : ScanUiState
    data class Multi(val candidates: List<Candidate>, val selected: Set<Int>) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

/** Live mode: what the camera currently sees. */
data class LiveState(
    val hit: Candidate? = null,
    /** Increments every time a new card is locked on (used to trigger a vibration). */
    val lockCount: Int = 0,
    val justAdded: Boolean = false,
    val seeingText: Boolean = false,
)

class ScanViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val _captureMode = MutableStateFlow(CaptureMode.LIVE)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val scanMode: StateFlow<ScanMode> = c.settings.settings.map { it.scanMode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScanMode.ON_DEVICE)
    val target: StateFlow<AddTarget> = c.settings.settings.map { it.scanTarget }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AddTarget.COLLECTION)
    val autoAdd: StateFlow<Boolean> = c.settings.settings.map { it.liveAutoAdd }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Only Claude mode needs a key; the default on-device mode is free. */
    val needsApiKey: StateFlow<Boolean> = c.settings.settings
        .map { it.scanMode == ScanMode.CLAUDE && it.anthropicApiKey.isBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val s get() = c.strings.value

    fun setCaptureMode(mode: CaptureMode) {
        _captureMode.value = mode
        reset()
    }

    fun setTarget(t: AddTarget) = viewModelScope.launch { c.settings.setScanTarget(t) }
    fun setAutoAdd(on: Boolean) = viewModelScope.launch { c.settings.setLiveAutoAdd(on) }

    // ---------- Photo & multi ----------

    fun identify(capture: Capture) {
        _state.value = ScanUiState.Working
        viewModelScope.launch {
            try {
                val recognizer = c.recognizer(scanMode.value)
                if (_captureMode.value == CaptureMode.MULTI) {
                    val guesses = recognizer.recognizeMany(capture.jpeg)
                    val found = c.resolver.resolveMany(guesses)
                    _state.value = if (found.isEmpty()) ScanUiState.Error(s.couldNotRead)
                    else ScanUiState.Multi(found, found.indices.toSet())
                } else {
                    val guess = recognizer.recognize(capture.jpeg)
                    if (guess.isEmpty) {
                        _state.value = ScanUiState.Error(s.couldNotRead)
                        return@launch
                    }
                    _state.value = ScanUiState.Result(guess, c.resolver.resolve(guess, capture.card))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ScanUiState.Error(e.message ?: s.couldNotRead)
            }
        }
    }

    fun fail(message: String) {
        _state.value = ScanUiState.Error(message)
    }

    fun reset() {
        _state.value = ScanUiState.Idle
    }

    fun toggleSelected(index: Int) = _state.update { st ->
        if (st is ScanUiState.Multi) st.copy(selected = if (index in st.selected) st.selected - index else st.selected + index) else st
    }

    fun addSelected() {
        val st = _state.value as? ScanUiState.Multi ?: return
        val picked = st.selected.sorted().mapNotNull { st.candidates.getOrNull(it) }
        viewModelScope.launch {
            picked.forEach { addToTarget(it, foil = false, quiet = true) }
            _messages.tryEmit(s.added("${picked.size}"))
            reset()
        }
    }

    fun quickAdd(candidate: Candidate, foil: Boolean) = viewModelScope.launch { addToTarget(candidate, foil) }

    private suspend fun addToTarget(candidate: Candidate, foil: Boolean, quiet: Boolean = false) {
        val edition = candidate.edition ?: candidate.card.editions.firstOrNull() ?: return
        when (target.value) {
            AddTarget.COLLECTION -> c.collection.add(candidate.card, edition, foil, 1)
            AddTarget.SELL -> c.trade.add(TradeListType.SELL, candidate.card, edition, foil, 1)
            AddTarget.BUY -> c.trade.add(TradeListType.BUY, candidate.card, edition, foil, 1)
        }
        if (!quiet) _messages.tryEmit(s.added("${candidate.card.name} (${edition.label}${if (foil) ", foil" else ""})"))
    }

    // ---------- Live ----------

    private val liveBusy = AtomicBoolean(false)
    @Volatile private var lastFrameAt = 0L
    private var pendingKey: String? = null
    private var pendingCount = 0
    private var lockedKey: String? = null
    private var lastAddedKey: String? = null
    private var emptyFrames = 0

    /** Called from the camera thread; true when the next frame should be processed. */
    fun wantsFrame(): Boolean =
        _captureMode.value == CaptureMode.LIVE && !liveBusy.get() &&
            SystemClock.elapsedRealtime() - lastFrameAt >= LIVE_INTERVAL_MS

    fun processFrame(frame: Bitmap) {
        if (!liveBusy.compareAndSet(false, true)) return
        lastFrameAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val lines = c.onDevice.readLines(frame)
                val guess = CardTextParser.parse(lines)
                if (guess.isEmpty) {
                    onEmptyFrame(seeingText = lines.isNotEmpty())
                    return@launch
                }
                val offline = c.cards.offlineCount() > 0
                val candidate = c.resolver.resolve(guess, cardImage = null, allowNetwork = !offline).firstOrNull()
                if (candidate == null) {
                    onEmptyFrame(seeingText = true)
                    return@launch
                }
                emptyFrames = 0
                val key = candidate.edition?.uuid ?: candidate.card.uuid
                if (key == pendingKey) pendingCount++ else {
                    pendingKey = key
                    pendingCount = 1
                }
                // An exact footer match is trusted at once; a name-only read must repeat.
                val stable = pendingCount >= 2 || (candidate.exact && pendingCount >= 1)
                if (stable && key != lockedKey) lock(key, candidate, frame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A bad frame is not worth reporting; the next one will be tried.
            } finally {
                liveBusy.set(false)
            }
        }
    }

    private suspend fun lock(key: String, found: Candidate, frame: Bitmap) {
        lockedKey = key
        var candidate = found
        if (!found.exact) {
            // Name-only read: try to pick the right printing by its artwork.
            guideCrop(frame)?.let { crop ->
                runCatching { c.art.bestEdition(crop, found.card.allEditions) }.getOrNull()?.let {
                    candidate = found.copy(edition = it.edition, artMatched = true)
                }
            }
        }
        var added = false
        if (autoAdd.value && key != lastAddedKey) {
            addToTarget(candidate, foil = false, quiet = true)
            lastAddedKey = key
            added = true
        }
        _live.update { LiveState(hit = candidate, lockCount = it.lockCount + 1, justAdded = added, seeingText = true) }
    }

    private fun onEmptyFrame(seeingText: Boolean) {
        emptyFrames++
        // Card taken out of view: the same card may be added again next time.
        if (emptyFrames >= 3) {
            pendingKey = null
            pendingCount = 0
            lockedKey = null
            lastAddedKey = null
        }
        _live.update { it.copy(seeingText = seeingText, justAdded = false) }
    }

    fun liveAddAgain() {
        val hit = _live.value.hit ?: return
        viewModelScope.launch {
            addToTarget(hit, foil = false)
            lastAddedKey = hit.edition?.uuid ?: hit.card.uuid
        }
    }

    fun clearLive() {
        _live.value = LiveState(lockCount = _live.value.lockCount)
        lockedKey = null
    }

    private companion object {
        const val LIVE_INTERVAL_MS = 350L
    }
}
