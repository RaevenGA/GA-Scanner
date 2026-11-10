package com.gascanner.app.data.scan

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

/**
 * A scanned card waiting to be checked and sent somewhere (collection, a list or a deck).
 * Kept in its own database so a long scanning session survives the app being closed.
 */
@Entity(tableName = "scan_items", indices = [Index(value = ["editionUuid", "foil"], unique = true)])
data class ScanItem(
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
    /** Last time this line was scanned or changed; newest first in the list. */
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface ScanItemDao {
    @Query("SELECT * FROM scan_items ORDER BY updatedAt DESC")
    fun observe(): Flow<List<ScanItem>>

    @Query("SELECT * FROM scan_items ORDER BY updatedAt DESC")
    suspend fun all(): List<ScanItem>

    @Query("SELECT * FROM scan_items WHERE editionUuid = :editionUuid AND foil = :foil LIMIT 1")
    suspend fun find(editionUuid: String, foil: Boolean): ScanItem?

    @Query("DELETE FROM scan_items")
    suspend fun clear()

    @Insert
    suspend fun insert(item: ScanItem): Long

    @Update
    suspend fun update(item: ScanItem)

    @Delete
    suspend fun delete(item: ScanItem)
}

@Database(entities = [ScanItem::class], version = 1, exportSchema = false)
abstract class ScanDatabase : RoomDatabase() {
    abstract fun items(): ScanItemDao
}

class ScanTray(private val dao: ScanItemDao) {
    val items: Flow<List<ScanItem>> = dao.observe()

    suspend fun all(): List<ScanItem> = dao.all()

    /** Adds copies; the same printing and finish is counted on one line. */
    suspend fun add(card: CardDto, edition: EditionDto, foilIn: Boolean, quantity: Int = 1) {
        val foil = edition.fixFoil(foilIn)
        val existing = dao.find(edition.uuid, foil)
        if (existing != null) {
            dao.update(existing.copy(quantity = existing.quantity + quantity, updatedAt = System.currentTimeMillis()))
        } else {
            dao.insert(
                ScanItem(
                    cardSlug = card.slug, cardName = card.name, editionUuid = edition.uuid, setPrefix = edition.setPrefix,
                    setName = edition.set?.name.orEmpty(), collectorNumber = edition.collectorNumber,
                    imageUrl = edition.imageUrl, foil = foil, quantity = quantity,
                )
            )
        }
    }

    /** Removes copies (used by Undo and the quantity stepper); the line goes at 0. */
    suspend fun remove(editionUuid: String, foil: Boolean, count: Int = 1) {
        val item = dao.find(editionUuid, foil) ?: return
        setQuantity(item, item.quantity - count)
    }

    suspend fun setQuantity(item: ScanItem, quantity: Int) {
        if (quantity <= 0) dao.delete(item) else dao.update(item.copy(quantity = quantity))
    }

    /** Change printing / foil / quantity; merges with a line that already has that printing. */
    suspend fun edit(item: ScanItem, card: CardDto, edition: EditionDto, foilIn: Boolean, quantity: Int) {
        val foil = edition.fixFoil(foilIn)
        if (quantity <= 0) {
            dao.delete(item)
            return
        }
        if (item.editionUuid == edition.uuid && item.foil == foil) {
            dao.update(item.copy(quantity = quantity))
            return
        }
        dao.delete(item)
        add(card, edition, foil, quantity)
    }

    suspend fun clear() = dao.clear()
}
