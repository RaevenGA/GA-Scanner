package com.gascanner.app.data.update

import com.gascanner.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateInfo(val version: String, val tag: String, val pageUrl: String, val apkUrl: String?)

sealed interface UpdateState {
    data object Unknown : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** Checks the GitHub releases of the app's repository for a newer version. */
class UpdateChecker(private val client: OkHttpClient, private val json: Json) {
    @Serializable
    private data class Asset(val name: String = "", @SerialName("browser_download_url") val url: String = "")

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var checkedAt = 0L

    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun check(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - checkedAt < 6L * 60 * 60 * 1000 && _state.value !is UpdateState.Failed) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val releases = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(RELEASES_URL).header("Accept", "application/vnd.github+json").build()
                ).execute().use { r ->
                    if (!r.isSuccessful) error("GitHub ${r.code}")
                    json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Release.serializer()), r.body?.string().orEmpty())
                }
            }
            checkedAt = System.currentTimeMillis()
            // Newest published release, including "alpha" pre-releases.
            val newest = releases.filter { !it.draft && versionNumbers(it.tag).isNotEmpty() }
                .maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) }
            if (newest != null && compareVersions(newest.tag, currentVersion) > 0) {
                UpdateState.Available(
                    UpdateInfo(
                        version = versionNumbers(newest.tag).joinToString("."),
                        tag = newest.tag,
                        pageUrl = newest.htmlUrl,
                        apkUrl = newest.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }?.url,
                    )
                )
            } else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(e.message ?: "unavailable")
        }
    }

    companion object {
        const val RELEASES_URL = "https://api.github.com/repos/RaevenGA/GA-Scanner/releases?per_page=20"
        const val RELEASES_PAGE = "https://github.com/RaevenGA/GA-Scanner/releases"

        /** "v.0.4.1" -> [0, 4, 1]; "v.0.3.1-alpha" -> [0, 3, 1]. */
        fun versionNumbers(tag: String): List<Int> =
            Regex("\\d+(?:\\.\\d+)*").find(tag)?.value?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()

        fun compareVersions(a: String, b: String): Int {
            val x = versionNumbers(a)
            val y = versionNumbers(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}
