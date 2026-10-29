package com.gascanner.app.ui.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gascanner.app.AppContainer
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.db.DeckSection
import com.gascanner.app.data.db.DeckWithCount
import com.gascanner.app.data.prices.PriceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CardDetailState(
    val card: CardDto? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val selectedEditionUuid: String? = null,
    val prices: PriceResult? = null,
    val pricesLoading: Boolean = false,
) {
    val selectedEdition: EditionDto?
        get() = card?.allEditions?.firstOrNull { it.uuid == selectedEditionUuid } ?: card?.editions?.firstOrNull()
}

class CardDetailViewModel(
    private val slug: String,
    private val initialEdition: String?,
    private val c: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(CardDetailState())
    val state: StateFlow<CardDetailState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val ownedCount: StateFlow<Int> =
        c.collection.countForCard(slug).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val decks: StateFlow<List<DeckWithCount>> =
        c.decks.decks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var priceJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val card = c.gatcg.cardBySlug(slug)
                val selected = initialEdition?.takeIf { id -> card.allEditions.any { it.uuid == id } }
                    ?: card.editions.firstOrNull()?.uuid
                _state.update { it.copy(card = card, loading = false, selectedEditionUuid = selected) }
                loadPrices()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Could not load card") }
            }
        }
    }

    fun selectEdition(uuid: String) {
        if (uuid == _state.value.selectedEditionUuid) return
        _state.update { it.copy(selectedEditionUuid = uuid, prices = null) }
        loadPrices()
    }

    fun loadPrices(forceRefresh: Boolean = false) {
        val card = _state.value.card ?: return
        val edition = _state.value.selectedEdition
        priceJob?.cancel()
        priceJob = viewModelScope.launch {
            _state.update { it.copy(pricesLoading = true) }
            try {
                if (forceRefresh) runCatching { c.prices.refreshTcgplayer() }
                val result = c.prices.pricesFor(card, edition)
                _state.update { it.copy(prices = result, pricesLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(prices = PriceResult(errors = listOf(e.message ?: "Prices unavailable")), pricesLoading = false) }
            }
        }
    }

    fun addToCollection(quantity: Int, foil: Boolean) {
        val card = _state.value.card ?: return
        val edition = _state.value.selectedEdition ?: return
        viewModelScope.launch {
            c.collection.add(card, edition, foil, quantity)
            _messages.tryEmit("Added $quantity × ${card.name} (${edition.label}${if (foil) ", foil" else ""}) to collection")
        }
    }

    fun addToDeck(deckId: Long?, newDeckName: String?, section: DeckSection, quantity: Int) {
        val card = _state.value.card ?: return
        val edition = _state.value.selectedEdition ?: return
        viewModelScope.launch {
            val id = deckId ?: c.decks.createDeck(newDeckName?.ifBlank { null } ?: "New deck")
            c.decks.addCard(id, section, card, edition, quantity)
            _messages.tryEmit("Added $quantity × ${card.name} to ${section.label.lowercase()}")
        }
    }
}
