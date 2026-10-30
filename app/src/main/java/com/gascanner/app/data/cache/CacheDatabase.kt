package com.gascanner.app.data.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/*
 * Re-downloadable data only (card database, prices, image fingerprints).
 * It lives in its own database file so it can be rebuilt freely without touching
 * the user's collection, decks or trade lists.
 */

@Entity(tableName = "cards", indices = [Index(value = ["slug"], unique = true), Index("nameNorm")])
data class CachedCard(
    @PrimaryKey val uuid: String,
    val slug: String,
    val name: String,
    val nameNorm: String,
    /** Full CardDto as JSON. */
    val json: String,
)

@Entity(
    tableName = "editions",
    indices = [Index(value = ["prefixNorm", "numberNorm"]), Index("cardUuid")],
)
data class CachedEdition(
    @PrimaryKey val uuid: String,
    val cardUuid: String,
    val cardSlug: String,
    val setPrefix: String,
    val prefixNorm: String,
    val collectorNumber: String,
    val numberNorm: String,
    val imageUrl: String?,
)

@Entity(tableName = "price_cache", primaryKeys = ["editionUuid", "sourceId", "foil"])
data class CachedPrice(
    val editionUuid: String,
    val sourceId: String,
    val foil: Boolean,
    val amount: Double,
    val currency: String,
    val url: String?,
    val fetchedAt: Long,
)

@Entity(tableName = "art_hashes")
data class ArtHash(
    @PrimaryKey val editionUuid: String,
    val hash: Long,
)

data class CardName(val uuid: String, val slug: String, val name: String)

@Dao
interface CardCacheDao {
    @Query("SELECT COUNT(*) FROM cards")
    suspend fun count(): Int

    @Query("SELECT * FROM cards WHERE nameNorm LIKE :pattern ORDER BY name COLLATE NOCASE LIMIT :limit")
    suspend fun searchByName(pattern: String, limit: Int): List<CachedCard>

    @Query("SELECT * FROM cards WHERE slug = :slug LIMIT 1")
    suspend fun bySlug(slug: String): CachedCard?

    @Query("SELECT * FROM cards WHERE uuid = :uuid LIMIT 1")
    suspend fun byUuid(uuid: String): CachedCard?

    @Query("SELECT * FROM cards WHERE slug IN (:slugs)")
    suspend fun bySlugs(slugs: List<String>): List<CachedCard>

    @Query("SELECT uuid, slug, name FROM cards")
    suspend fun allNames(): List<CardName>

    @Query("SELECT * FROM editions WHERE prefixNorm = :prefixNorm AND numberNorm = :numberNorm")
    suspend fun editionsByFooter(prefixNorm: String, numberNorm: String): List<CachedEdition>

    @Query("SELECT * FROM editions WHERE prefixNorm LIKE :prefixPattern AND numberNorm = :numberNorm")
    suspend fun editionsByFooterLike(prefixPattern: String, numberNorm: String): List<CachedEdition>

    @Query("SELECT * FROM editions WHERE uuid = :uuid LIMIT 1")
    suspend fun edition(uuid: String): CachedEdition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCards(cards: List<CachedCard>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEditions(editions: List<CachedEdition>)

    @Query("DELETE FROM editions WHERE cardUuid IN (:cardUuids)")
    suspend fun deleteEditionsOf(cardUuids: List<String>)

    @Transaction
    suspend fun replaceCards(cards: List<CachedCard>, editions: List<CachedEdition>) {
        deleteEditionsOf(cards.map { it.uuid })
        insertCards(cards)
        insertEditions(editions)
    }
}

@Dao
interface PriceCacheDao {
    @Query("SELECT * FROM price_cache WHERE editionUuid = :editionUuid")
    suspend fun forEdition(editionUuid: String): List<CachedPrice>

    @Query("SELECT * FROM price_cache WHERE sourceId = :sourceId")
    suspend fun forSource(sourceId: String): List<CachedPrice>

    @Query("SELECT * FROM price_cache")
    suspend fun all(): List<CachedPrice>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(price: CachedPrice)
}

@Dao
interface ArtHashDao {
    @Query("SELECT * FROM art_hashes WHERE editionUuid IN (:uuids)")
    suspend fun get(uuids: List<String>): List<ArtHash>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(hash: ArtHash)
}

@Database(
    entities = [CachedCard::class, CachedEdition::class, CachedPrice::class, ArtHash::class],
    version = 1,
    exportSchema = false,
)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cards(): CardCacheDao
    abstract fun prices(): PriceCacheDao
    abstract fun artHashes(): ArtHashDao
}
