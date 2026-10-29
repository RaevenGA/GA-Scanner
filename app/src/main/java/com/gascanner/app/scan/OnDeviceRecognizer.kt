package com.gascanner.app.scan

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Free, offline scanning with Google ML Kit text recognition (the model ships inside the app).
 * Reads the card name at the top and the set code + collector number in the footer;
 * [com.gascanner.app.ui.scan.ScanViewModel] then resolves them against the card database.
 */
class OnDeviceRecognizer : CardRecognizer {

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(jpeg: ByteArray): ScanGuess {
        val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
            ?: throw IOException("Could not read the photo.")
        val text: Text = suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(IOException("Text recognition failed: ${it.message}", it)) }
        }
        val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, top = box.top, bottom = box.bottom)
        }
        return CardTextParser.parse(lines)
    }
}

data class OcrLine(val text: String, val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** Pure text logic, kept separate from ML Kit so it is easy to tune and unit-test. */
object CardTextParser {

    // Set code (2–5 chars, e.g. AMB, DOA, ALC), optional edition marker, then a 1–3 digit number.
    // O/I/L are accepted in the number because OCR often confuses them with 0/1.
    private val footerRegex =
        Regex("""\b([A-Z][A-Z0-9]{1,4})\s*(1ST|ALT)?\s*[·•\-#:/|.]?\s*([0-9OIL]{1,3})\b""")

    private val noiseWords = setOf(
        "LEVEL", "LV", "POWER", "LIFE", "DUR", "COST", "SPEED", "CLASS", "ILLUS", "ILLUSTRATED",
        "ART", "BY", "THE", "OF", "TO", "AND", "ON", "IN", "WOTS", "WEEBS", "SHORE", "TM",
    )

    fun parse(lines: List<OcrLine>): ScanGuess {
        val clean = lines.filter { it.text.isNotBlank() }
        if (clean.isEmpty()) return ScanGuess()
        val minTop = clean.minOf { it.top }
        val maxBottom = clean.maxOf { it.bottom }
        val span = (maxBottom - minTop).coerceAtLeast(1)

        // Footer: search bottom-most lines first.
        val footerCodes = clean.sortedByDescending { it.bottom }
            .flatMap { footerCodesIn(it.text) }
            .distinct()
            .take(6)

        // Name: lines in the top third of the text area, mostly letters.
        val nameLines = clean
            .filter { it.top < minTop + span * 0.33 }
            .map { it to cleanName(it.text) }
            .filter { (_, t) -> t.count { c -> c.isLetter() } >= 3 && t.count { c -> c.isLetter() } >= t.length * 0.6 }
            .sortedBy { it.first.top }
            .map { it.second }
            .distinct()
            .take(4)

        return ScanGuess(
            name = nameLines.firstOrNull().orEmpty(),
            setPrefix = footerCodes.firstOrNull()?.setPrefix,
            collectorNumber = footerCodes.firstOrNull()?.number,
            nameLines = nameLines,
            footerCodes = footerCodes,
        )
    }

    fun footerCodesIn(raw: String): List<FooterCode> {
        val text = raw.uppercase()
        return footerRegex.findAll(text).mapNotNull { m ->
            val code = m.groupValues[1]
            if (code in noiseWords || code.all { it.isDigit() }) return@mapNotNull null
            val number = m.groupValues[3]
                .replace('O', '0').replace('I', '1').replace('L', '1')
            if (!number.all { it.isDigit() }) return@mapNotNull null
            val edition = if (m.groupValues[2] == "1ST") " 1st" else ""
            FooterCode(setPrefix = code + edition, number = number.padStart(3, '0'))
        }.toList()
    }

    private fun cleanName(s: String): String =
        s.replace(Regex("""[^\p{L}0-9,'’\- ]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

/** Similarity 0..1 based on edit distance, tolerant to OCR typos. */
fun nameSimilarity(a: String, b: String): Double {
    val x = a.lowercase().filter { it.isLetterOrDigit() }
    val y = b.lowercase().filter { it.isLetterOrDigit() }
    if (x.isEmpty() || y.isEmpty()) return 0.0
    if (x == y) return 1.0
    // OCR line may contain extra text after the name (e.g. an epithet line merged in).
    if (x.startsWith(y) || y.startsWith(x)) return 0.9 * minOf(x.length, y.length) / maxOf(x.length, y.length) + 0.1
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
    return 1.0 - prev[y.length].toDouble() / maxOf(x.length, y.length)
}
