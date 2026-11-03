package com.gascanner.app.scan

import android.util.Base64
import com.gascanner.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
) {
    val isEmpty: Boolean get() = name.isBlank() && nameLines.isEmpty() && collectorNumber.isNullOrBlank() && footerCodes.isEmpty()
}

@Serializable
data class FooterCode(
    @SerialName("set_prefix") val setPrefix: String,
    @SerialName("collector_number") val number: String,
    /** Rarity as the database numbers it (1 = C … 9 = CPR), when printed after the number. */
    val rarity: Int? = null,
)

/** Rarity letters printed on cards, mapped to the database's rarity numbers. */
val RARITY_CODES = mapOf("C" to 1, "U" to 2, "R" to 3, "SR" to 4, "UR" to 5, "PR" to 6, "CSR" to 7, "CUR" to 8, "CPR" to 9)
fun rarityLabel(r: Int?): String? = RARITY_CODES.entries.firstOrNull { it.value == r }?.key

private val RARITY_NAMES = mapOf(
    "COMMON" to 1, "UNCOMMON" to 2, "RARE" to 3, "SUPER RARE" to 4, "ULTRA RARE" to 5,
    "PROMO" to 6, "PROMO RARE" to 6, "PROMOTIONAL RARE" to 6,
    "COLLECTOR SUPER RARE" to 7, "COLLECTOR ULTRA RARE" to 8, "COLLECTOR PROMO RARE" to 9,
)

/** "Collector Super Rare" / "CSR" -> 7. */
fun rarityFromText(text: String?): Int? = text?.trim()?.uppercase()?.let { RARITY_CODES[it] ?: RARITY_NAMES[it] }

/** Rarity marked in a product title or code: "(CSR)", "[CUR]", "AMB • 10 • C • NF". */
fun rarityInListing(text: String): Int? {
    val t = text.uppercase()
    Regex("""[(\[]\s*(CSR|CUR|CPR|SR|UR|PR)\s*[)\]]""").find(t)?.let { return RARITY_CODES[it.groupValues[1]] }
    Regex("""[•|]\s*(CSR|CUR|CPR|SR|UR|PR|C|U|R)\s*(?=[•|]|$)""").find(t)?.let { return RARITY_CODES[it.groupValues[1]] }
    return null
}

enum class ScanMode { ON_DEVICE, CLAUDE }

class MissingApiKeyException : IOException("Add your Anthropic API key in Settings to scan cards.")

/** Swappable so another vision provider can be added later. */
interface CardRecognizer {
    suspend fun recognize(jpeg: ByteArray): ScanGuess

    /** Several cards in one photo (e.g. a binder page or cards laid out on a table). */
    suspend fun recognizeMany(jpeg: ByteArray): List<ScanGuess>
}

/**
 * Identifies card photos with Claude's vision model via the Anthropic Messages API.
 * Optional: the key is the user's own, entered in Settings.
 */
class ClaudeVisionRecognizer(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) : CardRecognizer {

    override suspend fun recognize(jpeg: ByteArray): ScanGuess {
        val reply = ask(jpeg, SINGLE_PROMPT, maxTokens = 300)
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("Could not read the card. Try again with better light.")
        return json.decodeFromString(ScanGuess.serializer(), reply.substring(start, end + 1))
    }

    override suspend fun recognizeMany(jpeg: ByteArray): List<ScanGuess> {
        val reply = ask(jpeg, MULTI_PROMPT, maxTokens = 2000)
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return json.decodeFromString(ListSerializer(ScanGuess.serializer()), reply.substring(start, end + 1))
            .filter { !it.isEmpty }
    }

    private suspend fun ask(jpeg: ByteArray, prompt: String, maxTokens: Int): String {
        val s = settings.current()
        if (s.anthropicApiKey.isBlank()) throw MissingApiKeyException()

        val body = buildJsonObject {
            put("model", s.visionModel)
            put("max_tokens", maxTokens)
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
                            put("text", prompt)
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

        return json.parseToJsonElement(responseText).jsonObject["content"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }
            ?.joinToString("\n")
            .orEmpty()
    }

    private companion object {
        val SINGLE_PROMPT = """
            This photo shows a card from the Grand Archive trading card game.
            Read it and reply with ONLY a JSON object, no other text:
            {"name": "<card name exactly as printed at the top>",
             "set_prefix": "<set code printed in the footer, e.g. AMB or DOA 1st, or null>",
             "collector_number": "<collector number printed in the footer, e.g. 010, or null>",
             "foil": <true if the card looks foil, else false>,
             "confidence": <0 to 1>}
            If no Grand Archive card is visible, reply {"name": ""}.
        """.trimIndent()

        val MULTI_PROMPT = """
            This photo shows one or more cards from the Grand Archive trading card game.
            For EVERY card you can see, read its name and footer. Reply with ONLY a JSON array, no other text:
            [{"name": "<card name as printed>",
              "set_prefix": "<set code from the footer, e.g. AMB or DOA 1st, or null>",
              "collector_number": "<collector number from the footer, or null>",
              "foil": <true/false>}]
            If there are no Grand Archive cards, reply [].
        """.trimIndent()
    }
}
