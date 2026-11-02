package com.gascanner.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Long side for cloud upload. On-device OCR uses more pixels so the small footer text stays legible. */
const val UPLOAD_MAX_SIDE = 1280
const val OCR_MAX_SIDE = 2400
const val MULTI_MAX_SIDE = 3200

/** Card-shaped guide in the camera view: 68% of the view width, card aspect ratio, centred. */
const val GUIDE_WIDTH_FRACTION = 0.68f
const val CARD_ASPECT = 0.716f

/** A photo ready for recognition, plus the card area (used to compare artwork). */
class Capture(val jpeg: ByteArray, val card: Bitmap?, val footer: Bitmap? = null)

/** Upright bitmap of what the user saw in the preview (crop rect + rotation applied). */
fun ImageProxy.toUprightBitmap(): Bitmap {
    var bitmap = toBitmap()
    val crop = cropRect
    val alreadyCropped = bitmap.width == crop.width() && bitmap.height == crop.height()
    if (!alreadyCropped && !crop.isEmpty && crop != Rect(0, 0, bitmap.width, bitmap.height)) {
        val r = Rect(
            crop.left.coerceIn(0, bitmap.width - 1), crop.top.coerceIn(0, bitmap.height - 1),
            crop.right.coerceIn(1, bitmap.width), crop.bottom.coerceIn(1, bitmap.height),
        )
        if (r.width() > 0 && r.height() > 0) bitmap = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
    }
    val rotation = imageInfo.rotationDegrees
    if (rotation != 0) {
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
    return bitmap
}

fun ImageProxy.toCapture(maxSide: Int): Capture {
    val upright = toUprightBitmap()
    // The card area and its footer come from the full-resolution photo, before any downscaling.
    val region = guideRegion(upright)
    return Capture(upright.scaled(maxSide).toJpeg(), region?.scaled(600), region?.let(::footerStrip))
}

/** The part of a preview-shaped image inside the on-screen card guide. */
fun guideCrop(view: Bitmap): Bitmap? = guideRegion(view)?.scaled(600)

/** The card guide area at full resolution, with a little margin for imperfect framing. */
fun guideRegion(view: Bitmap): Bitmap? {
    val w = min((view.width * GUIDE_WIDTH_FRACTION * 1.08f).roundToInt(), view.width)
    val h = min((view.width * GUIDE_WIDTH_FRACTION / CARD_ASPECT * 1.06f).roundToInt(), view.height)
    if (w <= 0 || h <= 0) return null
    val left = (view.width - w) / 2
    val top = (view.height - h) / 2
    return Bitmap.createBitmap(view, left, top, w, h)
}

/**
 * Bottom strip of a card (set code, collector number, rarity), enlarged so the small print is
 * big enough for text recognition. The strip is generous so a slightly off-centre card still fits.
 */
fun footerStrip(card: Bitmap): Bitmap {
    val top = (card.height * 0.80f).roundToInt().coerceIn(0, card.height - 1)
    val strip = Bitmap.createBitmap(card, 0, top, card.width, card.height - top)
    val target = 1800f
    val factor = (target / strip.width).coerceIn(1f, 3f)
    return if (factor <= 1.01f) strip
    else Bitmap.createScaledBitmap(strip, (strip.width * factor).roundToInt(), (strip.height * factor).roundToInt(), true)
}

/** Loads a gallery image and prepares it the same way. The whole image is treated as the card. */
fun loadCapture(context: Context, uri: Uri, maxSide: Int): Capture? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    return Capture(bitmap.scaled(maxSide).toJpeg(), bitmap.scaled(600), footerStrip(bitmap))
}

fun Bitmap.scaled(maxSide: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxSide) return this
    val f = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * f).roundToInt().coerceAtLeast(1), (height * f).roundToInt().coerceAtLeast(1), true)
}

fun Bitmap.toJpeg(quality: Int = 90): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
