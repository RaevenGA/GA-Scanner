package com.gascanner.app.ui.scan

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gascanner.app.AppContainer
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.db.DeckWithCount
import com.gascanner.app.data.lists.TradeListType
import com.gascanner.app.ui.card.sectionLabel
import kotlinx.coroutines.flow.first
import com.gascanner.app.data.settings.AddTarget
import com.gascanner.app.scan.Candidate
import com.gascanner.app.scan.CardResolver
import com.gascanner.app.scan.CardTextParser
import com.gascanner.app.scan.Capture
import com.gascanner.app.scan.ScanGuess
import com.gascanner.app.scan.ScanMode
import com.gascanner.app.scan.footerStrip
import com.gascanner.app.scan.guideCrop
import com.gascanner.app.scan.guideRegion
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
    val canUndo: Boolean = false,
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
                    val guess = if (scanMode.value == ScanMode.ON_DEVICE) c.onDevice.recognize(capture) else recognizer.recognize(capture.jpeg)
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
            picked.forEach { addToTarget(it, foilIn = false, quiet = true) }
            _messages.tryEmit(s.added("${picked.size}"))
            reset()
        }
    }

    fun quickAdd(candidate: Candidate, foil: Boolean) = viewModelScope.launch { addToTarget(candidate, foil) }

    /** "Scan as foil": everything added while this is on is recorded as foil. */
    private val _scanFoil = MutableStateFlow(false)
    val scanFoil: StateFlow<Boolean> = _scanFoil.asStateFlow()
    fun setScanFoil(on: Boolean) { _scanFoil.value = on }

    /** What the last add did, so it can be undone or corrected. */
    private data class AddRecord(val candidate: Candidate, val target: AddTarget, val foil: Boolean, val deckId: Long, val section: DeckSection?)
    private var lastAdd: AddRecord? = null

    fun undoLast() {
        val r = lastAdd ?: return
        lastAdd = null
        viewModelScope.launch {
            undo(r)
            _live.update { it.copy(canUndo = false, justAdded = false) }
            _messages.tryEmit(s.undone(r.candidate.card.name))
        }
    }

    private suspend fun undo(r: AddRecord) {
        val uuid = r.candidate.edition?.uuid ?: r.candidate.card.editions.firstOrNull()?.uuid ?: return
        when (r.target) {
            AddTarget.COLLECTION -> c.collection.removeOne(uuid, r.foil)
            AddTarget.SELL -> c.trade.removeOne(TradeListType.SELL, uuid, r.foil)
            AddTarget.BUY -> c.trade.removeOne(TradeListType.BUY, uuid, r.foil)
            AddTarget.DECK -> r.section?.let { c.decks.removeOne(r.deckId, it, uuid) }
        }
    }

    private suspend fun addToTarget(candidate: Candidate, foilIn: Boolean, quiet: Boolean = false) {
        val edition = candidate.edition ?: candidate.card.editions.firstOrNull() ?: return
        val foil = foilIn || _scanFoil.value
        lastAdd = AddRecord(candidate, target.value, foil, 0L, null)
        when (target.value) {
            AddTarget.COLLECTION -> c.collection.add(candidate.card, edition, foil, 1)
            AddTarget.SELL -> c.trade.add(TradeListType.SELL, candidate.card, edition, foil, 1)
            AddTarget.BUY -> c.trade.add(TradeListType.BUY, candidate.card, edition, foil, 1)
            AddTarget.DECK -> {
                val deckId = ensureScanDeck()
                // Champions and regalia go to the material deck, Boons to their own section.
                val section = when {
                    candidate.card.isBoon -> DeckSection.BOONS
                    candidate.card.isMaterial -> DeckSection.MATERIAL
                    else -> DeckSection.MAIN
                }
                c.decks.addCard(deckId, section, candidate.card, edition, 1)
                lastAdd = AddRecord(candidate, AddTarget.DECK, foil, deckId, section)
                if (!quiet) _messages.tryEmit(s.addedToDeck(1, candidate.card.name, sectionLabel(s, section).lowercase()))
                return
            }
        }
        if (!quiet) _messages.tryEmit(s.added("${candidate.card.name} (${edition.label}${if (foil) ", foil" else ""})"))
    }

    // ---------- Scanning into a deck ----------

    val decks: StateFlow<List<DeckWithCount>> = c.decks.decks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val scanDeckId: StateFlow<Long> = c.settings.settings.map { it.scanDeckId }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun selectDeck(id: Long) = viewModelScope.launch {
        c.settings.setScanDeck(id)
        c.settings.setScanTarget(AddTarget.DECK)
    }

    fun createDeck(name: String) = viewModelScope.launch {
        val id = c.decks.createDeck(name.ifBlank { s.newDeck })
        selectDeck(id)
    }

    /** The chosen deck, or a new one if none is chosen (or it was deleted). */
    private suspend fun ensureScanDeck(): Long {
        val id = scanDeckId.value
        if (id != 0L && c.decks.decks.first().any { it.deck.id == id }) return id
        val created = c.decks.createDeck(s.newDeck)
        c.settings.setScanDeck(created)
        return created
    }

    // ---------- Live ----------

    private val liveBusy = AtomicBoolean(false)
    @Volatile private var lastFrameAt = 0L
    private var pendingKey: String? = null
    private var pendingCount = 0
    private var lockedKey: String? = null
    private var lastAddedKey: String? = null
    private var emptyFrames = 0
    private var lastThumb: IntArray? = null
    /** Something moved in the frame since the last add: a card was swapped (maybe for an identical one). */
    private var motionSinceAdd = false

    /** Called from the camera thread; true when the next frame should be processed. */
    fun wantsFrame(): Boolean =
        _captureMode.value == CaptureMode.LIVE && !liveBusy.get() &&
            SystemClock.elapsedRealtime() - lastFrameAt >= LIVE_INTERVAL_MS

    fun processFrame(frame: Bitmap) {
        if (!liveBusy.compareAndSet(false, true)) return
        lastFrameAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            try {
                val region = guideRegion(frame)
                region?.let { detectMotion(it) }
                val lines = c.onDevice.readLines(frame)
                // Second, enlarged read of the card's footer inside the guide frame.
                val footer = region?.let { runCatching { c.onDevice.readLines(footerStrip(it)) }.getOrNull() }.orEmpty()
                val guess = CardTextParser.withFooter(CardTextParser.parse(lines), footer)
                if (guess.isEmpty) {
                    onEmptyFrame(seeingText = lines.isNotEmpty())
                    return@launch
                }
                val offline = c.cards.offlineCount() > 0
                val candidate = c.resolver.resolve(guess, cardImage = null, allowNetwork = !offline, minNameScore = CardResolver.LIVE_MIN_NAME).firstOrNull()
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
                if (!stable) return@launch
                if (key != lockedKey) lock(key, candidate, frame)
                maybeAutoAdd(key, candidate)
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
        _live.update { it.copy(hit = candidate, lockCount = it.lockCount + 1, justAdded = false, seeingText = true) }
    }

    private suspend fun maybeAutoAdd(key: String, found: Candidate) {
        if (!autoAdd.value) return
        val hit = _live.value.hit?.takeIf { (it.edition?.uuid ?: it.card.uuid) == key || it.card.uuid == found.card.uuid } ?: found
        val previous = lastAdd

        // The same card was just added as another printing, and now the footer gives the exact one:
        // replace the earlier add instead of adding a second card.
        if (previous != null && !motionSinceAdd && found.exact && !previous.candidate.exact &&
            previous.candidate.card.uuid == found.card.uuid && key != lastAddedKey
        ) {
            undo(previous)
            addToTarget(found, foilIn = false, quiet = true)
            lastAddedKey = key
            _live.update { it.copy(hit = found, justAdded = true, canUndo = true) }
            _messages.tryEmit(s.corrected(found.card.name, found.edition?.label.orEmpty()))
            return
        }

        val newCard = key != lastAddedKey || motionSinceAdd
        // Wait for the footer (exact printing) a little longer before adding a name-only read.
        val confident = found.exact || pendingCount >= 4
        if (newCard && confident) {
            addToTarget(hit, foilIn = false, quiet = true)
            lastAddedKey = key
            motionSinceAdd = false
            _live.update { it.copy(justAdded = true, canUndo = true) }
        }
    }

    /** Compares a tiny grey thumbnail of the card area with the previous frame. */
    private fun detectMotion(region: Bitmap) {
        val small = Bitmap.createScaledBitmap(region, 16, 22, true)
        val px = IntArray(16 * 22)
        small.getPixels(px, 0, 16, 0, 0, 16, 22)
        val grey = IntArray(px.size) { i -> val c = px[i]; (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10 }
        val prev = lastThumb
        lastThumb = grey
        if (prev != null) {
            var diff = 0L
            for (i in grey.indices) diff += kotlin.math.abs(grey[i] - prev[i])
            if (diff / grey.size > MOTION_THRESHOLD) {
                motionSinceAdd = true
                // Let the same card lock (and be added) again after a swap.
                lockedKey = null
                pendingKey = null
                pendingCount = 0
            }
        }
    }

    private fun onEmptyFrame(seeingText: Boolean) {
        emptyFrames++
        // Card taken out of view: the same card may be added again next time.
        if (emptyFrames >= 2) {
            pendingKey = null
            pendingCount = 0
            lockedKey = null
            motionSinceAdd = true
        }
        _live.update { it.copy(seeingText = seeingText, justAdded = false) }
    }

    fun liveAddAgain() {
        val hit = _live.value.hit ?: return
        viewModelScope.launch {
            addToTarget(hit, foilIn = false)
            lastAddedKey = hit.edition?.uuid ?: hit.card.uuid
            _live.update { it.copy(canUndo = true) }
        }
    }

    fun clearLive() {
        _live.value = LiveState(lockCount = _live.value.lockCount)
        lockedKey = null
    }

    private companion object {
        const val LIVE_INTERVAL_MS = 350L
        /** Mean grey-level change (0–255) that counts as the card being moved or swapped. */
        const val MOTION_THRESHOLD = 28
    }
}
