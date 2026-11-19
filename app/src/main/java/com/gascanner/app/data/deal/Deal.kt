package com.gascanner.app.data.deal

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

/** The two sides of a trade, seen from this phone. */
enum class DealSide { GIVE, GET }

/** One printing on one side of the trade in progress. */
@Entity(tableName = "deal_items", indices = [Index(value = ["side", "editionUuid", "foil"], unique = true)])
data class DealItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val side: String,
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

@Dao
interface DealDao {
    @Query("SELECT * FROM deal_items ORDER BY addedAt DESC")
    fun observe(): Flow<List<DealItem>>

    @Query("SELECT * FROM deal_items WHERE side = :side")
    suspend fun side(side: String): List<DealItem>

    @Query("SELECT * FROM deal_items WHERE side = :side AND editionUuid = :editionUuid AND foil = :foil LIMIT 1")
    suspend fun find(side: String, editionUuid: String, foil: Boolean): DealItem?

    @Query("DELETE FROM deal_items")
    suspend fun clear()

    @Query("DELETE FROM deal_items WHERE side = :side")
    suspend fun clearSide(side: String)

    @Insert
    suspend fun insert(item: DealItem): Long

    @Update
    suspend fun update(item: DealItem)

    @Delete
    suspend fun delete(item: DealItem)
}

@Database(entities = [DealItem::class], version = 1, exportSchema = false)
abstract class DealDatabase : RoomDatabase() {
    abstract fun items(): DealDao
}

/** The trade in progress (one at a time); kept if the app closes. */
class DealRepository(private val dao: DealDao) {
    val items: Flow<List<DealItem>> = dao.observe()

    suspend fun side(side: DealSide): List<DealItem> = dao.side(side.name)

    suspend fun add(side: DealSide, card: CardDto, edition: EditionDto, foilIn: Boolean, quantity: Int = 1) {
        val foil = edition.fixFoil(foilIn)
        val existing = dao.find(side.name, edition.uuid, foil)
        if (existing != null) {
            dao.update(existing.copy(quantity = existing.quantity + quantity))
        } else {
            dao.insert(
                DealItem(
                    side = side.name, cardSlug = card.slug, cardName = card.name, editionUuid = edition.uuid,
                    setPrefix = edition.setPrefix, setName = edition.set?.name.orEmpty(), collectorNumber = edition.collectorNumber,
                    imageUrl = edition.imageUrl, foil = foil, quantity = quantity,
                )
            )
        }
    }

    suspend fun setQuantity(item: DealItem, quantity: Int) {
        if (quantity <= 0) dao.delete(item) else dao.update(item.copy(quantity = quantity))
    }

    /** Change printing / foil / quantity; merges with a line that already has that printing. */
    suspend fun edit(item: DealItem, card: CardDto, edition: EditionDto, foilIn: Boolean, quantity: Int) {
        val foil = edition.fixFoil(foilIn)
        if (quantity <= 0) return dao.delete(item)
        if (item.editionUuid == edition.uuid && item.foil == foil) return dao.update(item.copy(quantity = quantity))
        dao.delete(item)
        add(DealSide.valueOf(item.side), card, edition, foil, quantity)
    }

    suspend fun clearSide(side: DealSide) = dao.clearSide(side.name)
    suspend fun clear() = dao.clear()
}
