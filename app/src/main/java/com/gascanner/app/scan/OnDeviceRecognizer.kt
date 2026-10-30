package com.gascanner.app.scan

import android.graphics.Bitmap
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
 * [CardResolver] then matches them against the card database.
 */
class OnDeviceRecognizer : CardRecognizer {

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun recognize(jpeg: ByteArray): ScanGuess = CardTextParser.parse(readLines(decode(jpeg)))

    override suspend fun recognizeMany(jpeg: ByteArray): List<ScanGuess> = CardTextParser.parseMany(readLines(decode(jpeg)))

    /** Also used by the live scanner for camera frames. */
    suspend fun readLines(bitmap: Bitmap, rotationDegrees: Int = 0): List<OcrLine> {
        val text: Text = suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, rotationDegrees))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(IOException("Text recognition failed: ${it.message}", it)) }
        }
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, left = box.left, top = box.top, right = box.right, bottom = box.bottom)
        }
    }

    private suspend fun decode(jpeg: ByteArray): Bitmap =
        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
            ?: throw IOException("Could not read the photo.")
}

data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
}

/** Pure text logic, kept separate from ML Kit so it is easy to tune and unit-test. */
object CardTextParser {

    // Set code (2–5 chars, e.g. AMB, DOA, ALC), optional edition marker, then a 1–3 digit number.
    // O/I/L are accepted in the number because OCR often confuses them with 0/1.
    private val footerRegex =
        Regex("""\b([A-Z][A-Z0-9]{1,4})\s*(1ST|ALT)?\s*[·•\-#:/|.]?\s*([0-9OIL]{1,3})\b""")

    private val noiseWords = setOf(
        "LEVEL", "LV", "POWER", "LIFE", "DUR", "COST", "SPEED", "CLASS", "ILLUS", "ILLUSTRATED",
        "ART", "BY", "THE", "OF", "TO", "AND", "ON", "IN", "WOTS", "WEEBS", "SHORE", "TM", "MAY", "UP",
        "DRAW", "DEAL", "GAIN", "PUT", "GET", "PAY", "ITS", "ALL", "EACH", "WITH", "FOR", "TURN",
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
            .filter { (_, t) -> looksLikeName(t) }
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

    /**
     * Several cards in one photo: each footer code found is one card; name-like lines are
     * passed along too so the resolver can catch cards whose footer wasn't legible.
     */
    fun parseMany(lines: List<OcrLine>): List<ScanGuess> {
        val clean = lines.filter { it.text.isNotBlank() }
        val byFooter = clean.flatMap { line -> footerCodesIn(line.text) }
            .distinct()
            .map { ScanGuess(setPrefix = it.setPrefix, collectorNumber = it.number, footerCodes = listOf(it)) }
        val names = clean.map { cleanName(it.text) }.filter { looksLikeName(it) }.distinct()
            .map { ScanGuess(name = it, nameLines = listOf(it)) }
        return byFooter + names
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

    private fun looksLikeName(t: String): Boolean {
        val letters = t.count { it.isLetter() }
        return letters >= 3 && letters >= t.length * 0.6 && t.length <= 48
    }

    private fun cleanName(s: String): String =
        s.replace(Regex("""[^\p{L}0-9,'’\- ]"""), " ").replace(Regex("""\s+"""), " ").trim()
}
