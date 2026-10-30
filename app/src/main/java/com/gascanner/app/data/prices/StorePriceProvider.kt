package com.gascanner.app.data.prices

import com.gascanner.app.data.settings.PriceSourceConfig
import com.gascanner.app.data.settings.StorePlatform
import com.gascanner.app.util.normalizeName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

data class StoreHit(
    val title: String,
    val price: Double,
    val currency: String,
    val url: String?,
    val inStock: Boolean?,
    /** Shop's product code; Caduko puts set, number and finish here ("AMB • 10 • C • NF"). */
    val sku: String = "",
) {
    /** Text used for matching: title plus product code. */
    val matchText: String get() = "$title $sku"
}

class StoreUnreadableException(store: String) :
    IOException("$store: prices can't be read automatically — use the store link")

/**
 * Reads live prices from shops built on Shopify or WooCommerce, the two most common
 * platforms for European card shops. Both publish product search as public JSON
 * (no login, no key). Other shops still work as plain search links.
 */
class StorePriceProvider(private val client: OkHttpClient, private val json: Json) {

    private val detected = ConcurrentHashMap<String, StorePlatform>()

    fun detectedPlatform(source: PriceSourceConfig): StorePlatform? =
        if (source.platform != StorePlatform.AUTO) source.platform else detected[source.effectiveBaseUrl]

    suspend fun search(source: PriceSourceConfig, query: String): List<StoreHit> {
        val base = source.effectiveBaseUrl
        if (base.isBlank()) throw StoreUnreadableException(source.name)
        val platform = if (source.platform == StorePlatform.AUTO) detected[base] else source.platform
        return when (platform) {
            StorePlatform.SHOPIFY -> shopify(base, query, source.currency) ?: throw StoreUnreadableException(source.name)
            StorePlatform.WOOCOMMERCE -> woo(base, query, source.currency) ?: throw StoreUnreadableException(source.name)
            StorePlatform.NONE -> throw StoreUnreadableException(source.name)
            StorePlatform.AUTO, null -> {
                shopify(base, query, source.currency)?.also { detected[base] = StorePlatform.SHOPIFY }
                    ?: woo(base, query, source.currency)?.also { detected[base] = StorePlatform.WOOCOMMERCE }
                    ?: run {
                        detected[base] = StorePlatform.NONE
                        throw StoreUnreadableException(source.name)
                    }
            }
        }
    }

    /** Re-run detection (e.g. after the user edits a store). */
    fun forget(source: PriceSourceConfig) {
        detected.remove(source.effectiveBaseUrl)
    }

    // Shopify: /search/suggest.json — price is a decimal string in the shop's currency.
    private suspend fun shopify(base: String, query: String, currency: String): List<StoreHit>? {
        val url = "$base/search/suggest.json".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("resources[type]", "product")
            ?.addQueryParameter("resources[limit]", "10")
            ?.addQueryParameter("resources[options][unavailable_products]", "last")
            ?.build()?.toString() ?: return null
        val root = getJson(url) as? JsonObject ?: return null
        val products = root["resources"]?.jsonObject?.get("results")?.jsonObject?.get("products") as? JsonArray
            ?: return null
        return products.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val title = o.str("title") ?: return@mapNotNull null
            val price = o.str("price")?.replace(",", ".")?.toDoubleOrNull()
                ?: o.str("price_min")?.replace(",", ".")?.toDoubleOrNull()
                ?: return@mapNotNull null
            if (price <= 0.0) return@mapNotNull null
            StoreHit(
                title = stripHtml(title),
                price = price,
                currency = currency,
                url = o.str("url")?.let { if (it.startsWith("http")) it else base + it.substringBefore("?_pos") },
                inStock = (o["available"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }

    // WooCommerce Store API: prices in minor units with the currency code included.
    private suspend fun woo(base: String, query: String, fallbackCurrency: String): List<StoreHit>? {
        val url = "$base/wp-json/wc/store/v1/products".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("search", query)
            ?.addQueryParameter("per_page", "20")
            ?.build()?.toString() ?: return null
        val root = getJson(url) as? JsonArray ?: return null
        return root.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val title = o.str("name")?.let(::stripHtml) ?: return@mapNotNull null
            val prices = o["prices"] as? JsonObject ?: return@mapNotNull null
            val minor = (prices["currency_minor_unit"] as? JsonPrimitive)?.intOrNull ?: 2
            val raw = prices.str("price")?.toDoubleOrNull() ?: return@mapNotNull null
            if (raw <= 0.0) return@mapNotNull null // 0 = no price set (sold out listings)
            StoreHit(
                title = title,
                price = raw / 10.0.pow(minor),
                currency = prices.str("currency_code") ?: fallbackCurrency,
                url = o.str("permalink"),
                inStock = (o["is_in_stock"] as? JsonPrimitive)?.booleanOrNull,
                sku = o.str("sku")?.let(::stripHtml).orEmpty(),
            )
        }
    }

    private suspend fun getJson(url: String): JsonElement? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val type = r.header("Content-Type").orEmpty()
                val body = r.body?.string().orEmpty()
                if (!type.contains("json") && !body.trimStart().let { it.startsWith("{") || it.startsWith("[") }) return@use null
                runCatching { json.parseToJsonElement(body) }.getOrNull()
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        fun stripHtml(s: String): String = s.replace(Regex("<[^>]+>"), "")
            .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .trim()

        fun looksFoil(title: String): Boolean {
            val t = title.lowercase()
            if (Regex("\\bnf\\b").containsMatchIn(t)) return false
            return t.contains("foil") && !t.contains("non-foil") && !t.contains("nonfoil") && !t.contains("non foil")
        }

        /**
         * Does this shop listing look like this card? 0 = no, 1 = name matches, 2 = name + set or number.
         * Requires the card name to appear as a whole phrase in the title.
         */
        fun matchScore(title: String, cardName: String, setPrefix: String?, collectorNumber: String?): Int {
            val t = " " + normalizeName(title) + " "
            val n = normalizeName(cardName)
            if (n.isEmpty() || !t.contains(" $n ")) return 0
            val prefix = setPrefix?.substringBefore(' ')?.lowercase().orEmpty()
            val number = collectorNumber?.trimStart('0').orEmpty()
            val hasSet = prefix.isNotEmpty() && t.contains(" $prefix ")
            val hasNumber = number.isNotEmpty() && Regex("\\b0*$number\\b").containsMatchIn(t)
            return if (hasSet || hasNumber) 2 else 1
        }
    }
}
