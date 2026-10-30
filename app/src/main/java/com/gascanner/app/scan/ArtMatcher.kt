package com.gascanner.app.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.gascanner.app.data.api.EditionDto
import com.gascanner.app.data.cache.ArtHash
import com.gascanner.app.data.cache.ArtHashDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToInt

/**
 * Tells printings of the same card apart by their artwork, without reading any text.
 * Uses a 64-bit difference hash (dHash) of the art box; the hashes of the database images
 * are computed once and cached on the phone.
 */
class ArtMatcher(private val client: OkHttpClient, private val dao: ArtHashDao) {

    data class Match(val edition: EditionDto, val distance: Int)

    /** Returns the edition whose artwork best matches [cardImage], or null when unsure. */
    suspend fun bestEdition(cardImage: Bitmap, editions: List<EditionDto>): Match? = withContext(Dispatchers.Default) {
        val candidates = editions.filter { it.imageUrl != null }.take(MAX_CANDIDATES)
        // Nothing to decide if every printing shares the same image.
        if (candidates.map { it.imageUrl }.distinct().size < 2) return@withContext null

        val target = dHash(artBox(cardImage))
        val known = dao.get(candidates.map { it.uuid }).associate { it.editionUuid to it.hash }
        val scored = candidates.mapNotNull { e ->
            val h = known[e.uuid] ?: computeAndStore(e) ?: return@mapNotNull null
            Match(e, java.lang.Long.bitCount(h xor target))
        }.sortedBy { it.distance }

        val best = scored.firstOrNull() ?: return@withContext null
        val runnerUp = scored.firstOrNull { it.edition.imageUrl != best.edition.imageUrl }
        val clearWinner = runnerUp == null || runnerUp.distance - best.distance >= MIN_GAP
        if (best.distance <= MAX_DISTANCE && clearWinner) best else null
    }

    private suspend fun computeAndStore(e: EditionDto): Long? {
        val url = e.imageUrl ?: return null
        return try {
            val bytes = withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null else r.body?.bytes()
                }
            } ?: return null
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val h = dHash(artBox(bmp))
            dao.put(ArtHash(e.uuid, h))
            h
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_CANDIDATES = 12
        private const val MAX_DISTANCE = 22
        private const val MIN_GAP = 4

        /** Art area of a Grand Archive card (between the name bar and the text box). */
        fun artBox(card: Bitmap): Bitmap {
            val l = (card.width * 0.08f).roundToInt()
            val t = (card.height * 0.12f).roundToInt()
            val w = (card.width * 0.84f).roundToInt().coerceAtLeast(1)
            val h = (card.height * 0.43f).roundToInt().coerceAtLeast(1)
            return Bitmap.createBitmap(card, l, t, w.coerceAtMost(card.width - l), h.coerceAtMost(card.height - t))
        }

        fun dHash(bitmap: Bitmap): Long {
            val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val a = luminance(small.getPixel(x, y))
                    val b = luminance(small.getPixel(x + 1, y))
                    if (a > b) hash = hash or (1L shl bit)
                    bit++
                }
            }
            return hash
        }

        private fun luminance(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
    }
}
