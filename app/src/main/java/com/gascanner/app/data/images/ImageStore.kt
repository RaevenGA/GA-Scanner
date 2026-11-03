package com.gascanner.app.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.intercept.Interceptor
import coil.request.ImageResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

sealed interface ImageDownloadState {
    data object Idle : ImageDownloadState
    data class Running(val done: Int, val total: Int) : ImageDownloadState
    data class Done(val count: Int, val bytes: Long) : ImageDownloadState
    data class Failed(val message: String) : ImageDownloadState
}

/**
 * Card images saved on the phone for offline use. Originals are ~175 KB (about 790 MB for the
 * whole database), so each is stored re-encoded at [WIDTH] px wide (~40–50 KB each, ~200 MB total).
 */
class ImageStore(context: Context, private val client: OkHttpClient) {
    private val dir = File(context.filesDir, "card-images").apply { mkdirs() }
    private val running = Mutex()

    private val _state = MutableStateFlow<ImageDownloadState>(ImageDownloadState.Idle)
    val state: StateFlow<ImageDownloadState> = _state.asStateFlow()

    /** Local copy for an image URL, if downloaded. */
    fun localFile(url: String): File? {
        if (!url.contains("/cards/images/")) return null
        val f = File(dir, fileName(url))
        return if (f.exists() && f.length() > 0) f else null
    }

    fun stats(): Pair<Int, Long> {
        val files = dir.listFiles().orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    /** Downloads every missing image. Safe to call repeatedly; already saved images are skipped. */
    suspend fun downloadAll(urls: List<String>) {
        if (!running.tryLock()) return
        try {
            val missing = urls.distinct().filter { localFile(it) == null }
            if (missing.isEmpty()) {
                val (n, bytes) = stats()
                _state.value = ImageDownloadState.Done(n, bytes)
                return
            }
            var done = 0
            var failed = 0
            _state.value = ImageDownloadState.Running(0, missing.size)
            val limit = Semaphore(PARALLEL)
            coroutineScope {
                missing.chunked(40).forEach { chunk ->
                    chunk.map { url ->
                        async {
                            limit.withPermit {
                                if (!saveOne(url)) failed++
                            }
                        }
                    }.awaitAll()
                    done += chunk.size
                    _state.value = ImageDownloadState.Running(done, missing.size)
                }
            }
            val (n, bytes) = stats()
            _state.value = if (failed > missing.size / 2) ImageDownloadState.Failed("$failed / ${missing.size}")
            else ImageDownloadState.Done(n, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ImageDownloadState.Failed(e.message ?: "download failed")
        } finally {
            running.unlock()
        }
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
        _state.value = ImageDownloadState.Idle
    }

    private suspend fun saveOne(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext false
                r.body?.bytes()
            } ?: return@withContext false
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext false
            val out = if (src.width > WIDTH) {
                Bitmap.createScaledBitmap(src, WIDTH, (src.height * WIDTH.toFloat() / src.width).roundToInt(), true)
            } else src
            val tmp = File(dir, fileName(url) + ".part")
            FileOutputStream(tmp).use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            tmp.renameTo(File(dir, fileName(url)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun fileName(url: String) = url.substringAfterLast('/').substringBefore('?').filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    private companion object {
        const val WIDTH = 420
        const val QUALITY = 80
        const val PARALLEL = 4
    }
}

/** Makes every image in the app use the saved copy when there is one (works offline). */
class OfflineImageInterceptor(private val store: ImageStore) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (data is String) {
            store.localFile(data)?.let { file ->
                return chain.proceed(chain.request.newBuilder().data(file).build())
            }
        }
        return chain.proceed(chain.request)
    }
}
