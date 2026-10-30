package com.gascanner.app.data.csv

/** Minimal RFC 4180 CSV reading and writing (quotes, commas, newlines inside quotes). */
object Csv {
    fun write(rows: List<List<String>>): String = buildString {
        rows.forEach { row ->
            append(row.joinToString(",") { escape(it) })
            append("\r\n")
        }
    }

    private fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ';' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Parses CSV; detects ';' as the separator when the header uses it (Excel in many EU locales). */
    fun parse(text: String): List<List<String>> {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull().orEmpty()
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                inQuotes && c == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == sep -> { row.add(field.toString()); field.clear() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row)
        }
        return rows
    }
}

/** A row read from an imported file, before it is matched to a card. */
data class ImportRow(
    val name: String,
    val setPrefix: String,
    val number: String,
    val foil: Boolean,
    val quantity: Int,
    val editionUuid: String,
    val price: Double?,
    val currency: String?,
    val note: String,
)

object CardCsv {
    val COLLECTION_HEADER = listOf("name", "set", "number", "foil", "quantity", "set_name", "edition_uuid", "card_slug")
    val TRADE_HEADER = listOf("list", "name", "set", "number", "foil", "quantity", "price", "currency", "note", "set_name", "edition_uuid", "card_slug")

    private val aliases = mapOf(
        "name" to listOf("name", "card", "card_name", "nazwa", "karta"),
        "set" to listOf("set", "set_code", "prefix", "set_prefix", "edition", "dodatek"),
        "number" to listOf("number", "collector_number", "no", "nr", "numer", "#"),
        "foil" to listOf("foil", "finish", "is_foil"),
        "quantity" to listOf("quantity", "qty", "count", "amount", "ilosc", "ilość", "szt"),
        "edition_uuid" to listOf("edition_uuid", "edition_id", "uuid"),
        "price" to listOf("price", "cena"),
        "currency" to listOf("currency", "waluta"),
        "note" to listOf("note", "notes", "comment", "uwagi"),
    )

    /** Reads any CSV with a header row. Unknown columns are ignored. */
    fun readRows(text: String): List<ImportRow> {
        val rows = Csv.parse(text)
        if (rows.size < 2) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        fun col(key: String): Int = aliases.getValue(key).firstNotNullOfOrNull { a -> header.indexOf(a).takeIf { it >= 0 } } ?: -1
        val iName = col("name"); val iSet = col("set"); val iNum = col("number"); val iFoil = col("foil")
        val iQty = col("quantity"); val iUuid = col("edition_uuid"); val iPrice = col("price")
        val iCur = col("currency"); val iNote = col("note")
        if (iName < 0 && iUuid < 0 && (iSet < 0 || iNum < 0)) return emptyList()
        return rows.drop(1).mapNotNull { r ->
            fun get(i: Int) = if (i >= 0 && i < r.size) r[i].trim() else ""
            val name = get(iName)
            val uuid = get(iUuid)
            if (name.isBlank() && uuid.isBlank() && get(iSet).isBlank()) return@mapNotNull null
            ImportRow(
                name = name,
                setPrefix = get(iSet),
                number = get(iNum),
                foil = get(iFoil).lowercase().let { it in setOf("1", "true", "yes", "y", "tak", "foil", "x") },
                quantity = get(iQty).toIntOrNull()?.coerceAtLeast(1) ?: 1,
                editionUuid = uuid,
                price = get(iPrice).replace(",", ".").toDoubleOrNull(),
                currency = get(iCur).ifBlank { null },
                note = get(iNote),
            )
        }
    }
}
