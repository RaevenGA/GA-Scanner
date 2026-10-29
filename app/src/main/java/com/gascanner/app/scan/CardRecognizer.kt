package com.gascanner.app.scan

import android.util.Base64
import com.gascanner.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** What the recognizer read off the card. */
@Serializable
data class ScanGuess(
    val name: String = "",
    @SerialName("set_prefix") val setPrefix: String? = null,
    @SerialName("collector_number") val collectorNumber: String? = null,
    val foil: Boolean? = null,
    val confidence: Double? = null,
    /** On-device OCR: candidate name lines, best first. */
    val nameLines: List<String> = emptyList(),
    /** On-device OCR: set code + number candidates read from the footer, best first. */
    val footerCodes: List<FooterCode> = emptyList(),
)

@Serializable
data class FooterCode(
    @SerialName("set_prefix") val setPrefix: String,
    @SerialName("collector_number") val number: String,
)

enum class ScanMode(val label: String) {
    ON_DEVICE("On-device (free)"),
    CLAUDE("Claude (API key)"),
}

class MissingApiKeyException : IOException("Add your Anthropic API key in Settings to scan cards.")

/** Swappable so another vision provider can be added later. */
interface CardRecognizer {
    suspend fun recognize(jpeg: ByteArray): ScanGuess
}

/**
 * Identifies a card photo with Claude's vision model via the Anthropic Messages API.
 * The key is the user's own, entered in Settings.
 */
class ClaudeVisionRecognizer(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) : CardRecognizer {

    override suspend fun recognize(jpeg: ByteArray): ScanGuess {
        val s = settings.current()
        if (s.anthropicApiKey.isBlank()) throw MissingApiKeyException()

        val body = buildJsonObject {
            put("model", s.visionModel)
            put("max_tokens", 300)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                            }
                        }
                        addJsonObject {
                            put("type", "text")
                            put("text", PROMPT)
                        }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", s.anthropicApiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                    }.getOrNull()
                    throw IOException("Recognition failed (${response.code})" + (message?.let { ": $it" } ?: ""))
                }
                text
            }
        }

        val reply = json.parseToJsonElement(responseText).jsonObject["content"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
            ?.joinToString("\n")
            .orEmpty()
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("Could not read the card. Try again with better light.")
        return json.decodeFromString(ScanGuess.serializer(), reply.substring(start, end + 1))
    }

    private companion object {
        val PROMPT = """
            This photo shows a card from the Grand Archive trading card game.
            Read it and reply with ONLY a JSON object, no other text:
            {"name": "<card name exactly as printed at the top>",
             "set_prefix": "<set code printed in the footer, e.g. AMB or DOA 1st, or null>",
             "collector_number": "<collector number printed in the footer, e.g. 010, or null>",
             "foil": <true if the card looks foil, else false>,
             "confidence": <0 to 1>}
            If no Grand Archive card is visible, reply {"name": ""}.
        """.trimIndent()
    }
}
