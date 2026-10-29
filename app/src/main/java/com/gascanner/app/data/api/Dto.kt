package com.gascanner.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Models for https://api.gatcg.com (see https://api.gatcg.com/openapi.json).
 * Fields whose exact JSON type is not pinned down in the spec are kept as JsonElement
 * so an unexpected type never breaks parsing.
 */

@Serializable
data class SearchResponse(
    val data: List<CardDto> = emptyList(),
    val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_cards") val totalCards: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class CardDto(
    val uuid: String = "",
    val slug: String = "",
    val name: String = "",
    val types: List<String> = emptyList(),
    val subtypes: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    val elements: List<String> = emptyList(),
    val element: String? = null,
    val cost: CostDto? = null,
    @SerialName("effect_raw") val effectRaw: JsonElement? = null,
    val level: JsonElement? = null,
    val power: JsonElement? = null,
    val life: JsonElement? = null,
    val durability: JsonElement? = null,
    val speed: JsonElement? = null,
    val editions: List<EditionDto> = emptyList(),
    @SerialName("result_editions") val resultEditions: List<EditionDto> = emptyList(),
) {
    val allEditions: List<EditionDto>
        get() = (editions + resultEditions).distinctBy { it.uuid }

    val elementLabel: String
        get() = elements.ifEmpty { listOfNotNull(element) }.joinToString(" / ")
}

@Serializable
data class CostDto(
    val type: String? = null,
    val value: JsonElement? = null,
)

@Serializable
data class EditionDto(
    val uuid: String = "",
    val slug: String = "",
    @SerialName("card_id") val cardId: String = "",
    @SerialName("collector_number") val collectorNumber: String = "",
    val image: String? = null,
    val rarity: Double? = null,
    val illustrator: JsonElement? = null,
    @SerialName("effect_raw") val effectRaw: JsonElement? = null,
    val set: SetDto? = null,
    val circulations: List<CirculationDto> = emptyList(),
    val circulationTemplates: List<CirculationDto> = emptyList(),
) {
    val imageUrl: String? get() = GatcgApi.imageUrl(image)
    val setPrefix: String get() = set?.prefix.orEmpty()
    val label: String get() = "$setPrefix $collectorNumber".trim()
    val hasFoil: Boolean get() = (circulations + circulationTemplates).any { it.foil == true }
}

@Serializable
data class SetDto(
    val id: String = "",
    val name: String = "",
    val prefix: String = "",
    val language: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
)

@Serializable
data class CirculationDto(
    val foil: Boolean? = null,
    val kind: String? = null,
    val name: String? = null,
)

/** Turns a loosely-typed JSON value into display text. */
fun JsonElement?.asText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> toString()
}
