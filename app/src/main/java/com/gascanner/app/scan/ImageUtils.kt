package com.gascanner.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Long side for cloud upload. On-device OCR uses more pixels so the small footer text stays legible. */
const val UPLOAD_MAX_SIDE = 1280
const val OCR_MAX_SIDE = 2400

/** Converts a CameraX frame to an upright, downscaled JPEG. */
fun ImageProxy.toUploadJpeg(maxSide: Int = UPLOAD_MAX_SIDE): ByteArray {
    val bitmap = toBitmap()
    val rotated = if (imageInfo.rotationDegrees != 0) {
        val m = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    } else bitmap
    return rotated.scaled(maxSide).toJpeg()
}

/** Loads a gallery image and prepares it the same way. */
fun loadUploadJpeg(context: Context, uri: Uri, maxSide: Int = UPLOAD_MAX_SIDE): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, opts)
    } ?: return null
    return bitmap.scaled(maxSide).toJpeg()
}

private fun Bitmap.scaled(maxSide: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxSide) return this
    val f = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(this, (width * f).roundToInt(), (height * f).roundToInt(), true)
}

private fun Bitmap.toJpeg(): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
