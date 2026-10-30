package com.gascanner.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collection ORDER BY cardName COLLATE NOCASE, setPrefix, collectorNumber")
    fun observeAll(): Flow<List<CollectionEntry>>

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM collection WHERE cardSlug = :slug")
    fun observeCountForCard(slug: String): Flow<Int>

    @Query("SELECT * FROM collection WHERE editionUuid = :editionUuid AND foil = :foil LIMIT 1")
    suspend fun find(editionUuid: String, foil: Boolean): CollectionEntry?

    @Query("SELECT * FROM collection ORDER BY cardName COLLATE NOCASE, setPrefix, collectorNumber")
    suspend fun all(): List<CollectionEntry>

    @Query("SELECT * FROM collection WHERE cardSlug = :slug ORDER BY setPrefix, collectorNumber")
    fun observeForCard(slug: String): Flow<List<CollectionEntry>>

    @Query("DELETE FROM collection")
    suspend fun clear()

    @Insert
    suspend fun insert(entry: CollectionEntry): Long

    @Update
    suspend fun update(entry: CollectionEntry)

    @Delete
    suspend fun delete(entry: CollectionEntry)
}

@Dao
interface DeckDao {
    @Query(
        """
        SELECT decks.*, COALESCE((SELECT SUM(quantity) FROM deck_cards WHERE deck_cards.deckId = decks.id), 0) AS cardCount
        FROM decks ORDER BY createdAt DESC
        """
    )
    fun observeDecks(): Flow<List<DeckWithCount>>

    @Query("SELECT * FROM decks WHERE id = :id")
    fun observeDeck(id: Long): Flow<Deck?>

    @Query("SELECT * FROM deck_cards WHERE deckId = :deckId ORDER BY cardName COLLATE NOCASE")
    fun observeCards(deckId: Long): Flow<List<DeckCard>>

    @Query("SELECT * FROM deck_cards WHERE deckId = :deckId AND section = :section AND editionUuid = :editionUuid LIMIT 1")
    suspend fun findCard(deckId: Long, section: String, editionUuid: String): DeckCard?

    @Insert
    suspend fun insertDeck(deck: Deck): Long

    @Update
    suspend fun updateDeck(deck: Deck)

    @Delete
    suspend fun deleteDeck(deck: Deck)

    @Insert
    suspend fun insertCard(card: DeckCard): Long

    @Update
    suspend fun updateCard(card: DeckCard)

    @Delete
    suspend fun deleteCard(card: DeckCard)
}

@Database(
    entities = [CollectionEntry::class, Deck::class, DeckCard::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collectionDao(): CollectionDao
    abstract fun deckDao(): DeckDao
}
