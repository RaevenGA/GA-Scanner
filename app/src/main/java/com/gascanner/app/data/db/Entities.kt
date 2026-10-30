package com.gascanner.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "collection",
    indices = [Index(value = ["editionUuid", "foil"], unique = true), Index("cardSlug")]
)
data class CollectionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardSlug: String,
    val cardName: String,
    val editionUuid: String,
    val setPrefix: String,
    val setName: String,
    val collectorNumber: String,
    val imageUrl: String?,
    val foil: Boolean,
    val quantity: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "decks")
data class Deck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** Grand Archive decks have a main deck and a material deck; sideboard is optional. */
enum class DeckSection(val label: String) {
    MATERIAL("Material deck"),
    MAIN("Main deck"),
    SIDEBOARD("Sideboard"),
    /** Pantheon only: one Lesser and one Greater Boon. */
    BOONS("Boons"),
}

@Entity(
    tableName = "deck_cards",
    foreignKeys = [
        ForeignKey(
            entity = Deck::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("deckId"), Index(value = ["deckId", "section", "editionUuid"], unique = true)]
)
data class DeckCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val section: String,
    val cardSlug: String,
    val cardName: String,
    val editionUuid: String,
    val setPrefix: String,
    val imageUrl: String?,
    val quantity: Int,
)

data class DeckWithCount(
    @Embedded val deck: Deck,
    val cardCount: Int,
)
