package com.gascanner.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class HttpStatusException(val code: Int, message: String) : IOException(message)

/** Client for the public Grand Archive card database API (no key required). */
class GatcgApi(private val client: OkHttpClient, private val json: Json) {

    private val base: HttpUrl = BASE.toHttpUrl()

    suspend fun search(name: String, page: Int = 1): SearchResponse {
        val url = base.newBuilder()
            .addPathSegments("cards/search")
            .addQueryParameter("name", name)
            .addQueryParameter("page", page.toString())
            .build()
        return json.decodeFromString(SearchResponse.serializer(), get(url))
    }

    suspend fun cardBySlug(slug: String): CardDto {
        val url = base.newBuilder().addPathSegment("cards").addPathSegment(slug).build()
        return json.decodeFromString(CardDto.serializer(), get(url))
    }

    /** Looks a card up by the set prefix and collector number printed in its footer. */
    suspend fun cardBySetAndNumber(setPrefix: String, collectorNumber: String): CardDto? {
        val url = base.newBuilder()
            .addPathSegment("cards")
            .addPathSegment(setPrefix.trim())
            .addPathSegment(collectorNumber.trim())
            .build()
        return try {
            json.decodeFromString(CardDto.serializer(), get(url))
        } catch (e: HttpStatusException) {
            if (e.code == 404) null else throw e
        }
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "Card database returned ${response.code}")
            }
            body
        }
    }

    companion object {
        const val BASE = "https://api.gatcg.com"

        fun imageUrl(path: String?): String? = when {
            path.isNullOrBlank() -> null
            path.startsWith("http") -> path
            path.startsWith("/") -> BASE + path
            else -> "$BASE/$path"
        }
    }
}
