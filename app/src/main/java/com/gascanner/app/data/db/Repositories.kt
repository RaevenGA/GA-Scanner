package com.gascanner.app.data.db

import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import kotlinx.coroutines.flow.Flow

class CollectionRepository(private val dao: CollectionDao) {
    val all: Flow<List<CollectionEntry>> = dao.observeAll()

    fun countForCard(slug: String): Flow<Int> = dao.observeCountForCard(slug)
    fun entriesForCard(slug: String): Flow<List<CollectionEntry>> = dao.observeForCard(slug)
    suspend fun snapshot(): List<CollectionEntry> = dao.all()
    suspend fun clear() = dao.clear()

    suspend fun add(card: CardDto, edition: EditionDto, foil: Boolean, quantity: Int = 1) {
        val existing = dao.find(edition.uuid, foil)
        if (existing != null) {
            dao.update(existing.copy(quantity = existing.quantity + quantity))
        } else {
            dao.insert(
                CollectionEntry(
                    cardSlug = card.slug,
                    cardName = card.name,
                    editionUuid = edition.uuid,
                    setPrefix = edition.setPrefix,
                    setName = edition.set?.name.orEmpty(),
                    collectorNumber = edition.collectorNumber,
                    imageUrl = edition.imageUrl,
                    foil = foil,
                    quantity = quantity,
                )
            )
        }
    }

    suspend fun changeQuantity(entry: CollectionEntry, delta: Int) {
        val newQty = entry.quantity + delta
        if (newQty <= 0) dao.delete(entry) else dao.update(entry.copy(quantity = newQty))
    }

    suspend fun remove(entry: CollectionEntry) = dao.delete(entry)

    /** Change printing / foil / quantity of an entry; merges into an existing entry for the new printing. */
    suspend fun edit(entry: CollectionEntry, card: CardDto, edition: EditionDto, foil: Boolean, quantity: Int) {
        if (quantity <= 0) {
            dao.delete(entry)
            return
        }
        val samePlace = entry.editionUuid == edition.uuid && entry.foil == foil
        if (samePlace) {
            dao.update(entry.copy(quantity = quantity))
            return
        }
        dao.delete(entry)
        add(card, edition, foil, quantity)
    }

    /** Remove one copy (used to undo a scan). */
    suspend fun removeOne(editionUuid: String, foil: Boolean) {
        dao.find(editionUuid, foil)?.let { changeQuantity(it, -1) }
    }
}

class DeckRepository(private val dao: DeckDao) {
    val decks: Flow<List<DeckWithCount>> = dao.observeDecks()

    fun deck(id: Long): Flow<Deck?> = dao.observeDeck(id)
    fun cards(deckId: Long): Flow<List<DeckCard>> = dao.observeCards(deckId)

    suspend fun createDeck(name: String): Long = dao.insertDeck(Deck(name = name.trim()))
    suspend fun renameDeck(deck: Deck, name: String) = dao.updateDeck(deck.copy(name = name.trim()))
    suspend fun deleteDeck(deck: Deck) = dao.deleteDeck(deck)

    suspend fun addCard(deckId: Long, section: DeckSection, card: CardDto, edition: EditionDto, quantity: Int = 1) {
        val existing = dao.findCard(deckId, section.name, edition.uuid)
        if (existing != null) {
            dao.updateCard(existing.copy(quantity = existing.quantity + quantity))
        } else {
            dao.insertCard(
                DeckCard(
                    deckId = deckId,
                    section = section.name,
                    cardSlug = card.slug,
                    cardName = card.name,
                    editionUuid = edition.uuid,
                    setPrefix = edition.setPrefix,
                    imageUrl = edition.imageUrl,
                    quantity = quantity,
                )
            )
        }
    }

    /** Change printing / section / quantity of a deck entry; merges into an existing entry. */
    suspend fun edit(entry: DeckCard, card: CardDto, edition: EditionDto, section: DeckSection, quantity: Int) {
        if (quantity <= 0) {
            dao.deleteCard(entry)
            return
        }
        if (entry.editionUuid == edition.uuid && entry.section == section.name) {
            dao.updateCard(entry.copy(quantity = quantity))
            return
        }
        dao.deleteCard(entry)
        addCard(entry.deckId, section, card, edition, quantity)
    }

    /** Remove one copy (used to undo a scan). */
    suspend fun removeOne(deckId: Long, section: DeckSection, editionUuid: String) {
        dao.findCard(deckId, section.name, editionUuid)?.let { changeQuantity(it, -1) }
    }

    suspend fun changeQuantity(card: DeckCard, delta: Int) {
        val newQty = card.quantity + delta
        if (newQty <= 0) dao.deleteCard(card) else dao.updateCard(card.copy(quantity = newQty))
    }
}
