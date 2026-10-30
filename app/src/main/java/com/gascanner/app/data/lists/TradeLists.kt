package com.gascanner.app.data.lists

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.gascanner.app.data.api.CardDto
import com.gascanner.app.data.api.EditionDto
import kotlinx.coroutines.flow.Flow

enum class TradeListType { SELL, BUY }

/** One line on a "want to sell" or "want to buy" list. */
@Entity(
    tableName = "trade_entries",
    indices = [Index(value = ["listType", "editionUuid", "foil"], unique = true)],
)
data class TradeEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listType: String,
    val cardSlug: String,
    val cardName: String,
    val editionUuid: String,
    val setPrefix: String,
    val setName: String,
    val collectorNumber: String,
    val imageUrl: String?,
    val foil: Boolean,
    val quantity: Int,
    /** Asking price (sell) or max price (buy); null = not set. */
    val price: Double? = null,
    val currency: String? = null,
    val note: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    val type: TradeListType get() = runCatching { TradeListType.valueOf(listType) }.getOrDefault(TradeListType.SELL)
}

@Dao
interface TradeDao {
    @Query("SELECT * FROM trade_entries WHERE listType = :type ORDER BY cardName COLLATE NOCASE, setPrefix, collectorNumber")
    fun observe(type: String): Flow<List<TradeEntry>>

    @Query("SELECT * FROM trade_entries WHERE listType = :type ORDER BY cardName COLLATE NOCASE, setPrefix, collectorNumber")
    suspend fun all(type: String): List<TradeEntry>

    @Query("SELECT COALESCE(SUM(quantity), 0) FROM trade_entries WHERE listType = :type AND cardSlug = :slug")
    fun observeCount(type: String, slug: String): Flow<Int>

    @Query("SELECT * FROM trade_entries WHERE listType = :type AND editionUuid = :editionUuid AND foil = :foil LIMIT 1")
    suspend fun find(type: String, editionUuid: String, foil: Boolean): TradeEntry?

    @Query("DELETE FROM trade_entries WHERE listType = :type")
    suspend fun clear(type: String)

    @Insert
    suspend fun insert(entry: TradeEntry): Long

    @Update
    suspend fun update(entry: TradeEntry)

    @Delete
    suspend fun delete(entry: TradeEntry)
}

@Database(entities = [TradeEntry::class], version = 1, exportSchema = false)
abstract class ListsDatabase : RoomDatabase() {
    abstract fun trade(): TradeDao
}

class TradeRepository(private val dao: TradeDao) {
    fun observe(type: TradeListType): Flow<List<TradeEntry>> = dao.observe(type.name)
    fun count(type: TradeListType, slug: String): Flow<Int> = dao.observeCount(type.name, slug)
    suspend fun all(type: TradeListType): List<TradeEntry> = dao.all(type.name)

    suspend fun add(type: TradeListType, card: CardDto, edition: EditionDto, foil: Boolean, quantity: Int = 1) =
        addRaw(
            type, card.slug, card.name, edition.uuid, edition.setPrefix, edition.set?.name.orEmpty(),
            edition.collectorNumber, edition.imageUrl, foil, quantity,
        )

    suspend fun addRaw(
        type: TradeListType, slug: String, name: String, editionUuid: String, setPrefix: String, setName: String,
        number: String, imageUrl: String?, foil: Boolean, quantity: Int,
        price: Double? = null, currency: String? = null, note: String = "",
    ) {
        val existing = dao.find(type.name, editionUuid, foil)
        if (existing != null) {
            dao.update(
                existing.copy(
                    quantity = existing.quantity + quantity,
                    price = price ?: existing.price,
                    currency = currency ?: existing.currency,
                    note = note.ifBlank { existing.note },
                )
            )
        } else {
            dao.insert(
                TradeEntry(
                    listType = type.name, cardSlug = slug, cardName = name, editionUuid = editionUuid,
                    setPrefix = setPrefix, setName = setName, collectorNumber = number, imageUrl = imageUrl,
                    foil = foil, quantity = quantity, price = price, currency = currency, note = note,
                )
            )
        }
    }

    suspend fun changeQuantity(entry: TradeEntry, delta: Int) {
        val q = entry.quantity + delta
        if (q <= 0) dao.delete(entry) else dao.update(entry.copy(quantity = q))
    }

    suspend fun setPrice(entry: TradeEntry, price: Double?, currency: String?, note: String) =
        dao.update(entry.copy(price = price, currency = currency, note = note.trim()))

    suspend fun remove(entry: TradeEntry) = dao.delete(entry)
    suspend fun clear(type: TradeListType) = dao.clear(type.name)
}
