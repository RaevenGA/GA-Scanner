package com.gascanner.app.util

import java.text.Normalizer

/** Lowercase, accents removed, only letters/digits separated by single spaces. */
fun normalizeName(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

/** "DOA 1st" -> "DOA1ST", "amb" -> "AMB". */
fun normalizePrefix(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }

/** "010" -> "10", "P01" -> "P01". */
fun normalizeNumber(s: String): String =
    s.uppercase().filter { it.isLetterOrDigit() }.trimStart('0').ifEmpty { "0" }

/** Similarity 0..1 based on edit distance, tolerant to OCR typos. */
fun nameSimilarity(a: String, b: String): Double {
    val x = normalizeName(a).replace(" ", "")
    val y = normalizeName(b).replace(" ", "")
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    // An OCR line may contain extra text after the name, or be cut short.
    if (x.startsWith(y) || y.startsWith(x)) {
        return 0.6 + 0.4 * minOf(x.length, y.length) / maxOf(x.length, y.length)
    }
    return 1.0 - levenshtein(x, y).toDouble() / maxOf(x.length, y.length)
}

fun levenshtein(x: String, y: String): Int {
    val prev = IntArray(y.length + 1) { it }
    val cur = IntArray(y.length + 1)
    for (i in 1..x.length) {
        cur[0] = i
        for (j in 1..y.length) {
            val cost = if (x[i - 1] == y[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        cur.copyInto(prev)
    }
    return prev[y.length]
}
