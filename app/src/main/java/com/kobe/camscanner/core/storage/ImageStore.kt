package com.kobe.camscanner.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bitmap I/O.
 *
 * Two rules govern this class, both from SDS 45: never decode more pixels than are needed, and
 * release large bitmaps as soon as possible. Callers get back exactly the size they asked for,
 * already rotated upright according to EXIF.
 */
@Singleton
class ImageStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Decodes a file, downsampling so the long edge is at most [maxLongEdge] px. */
    fun decodeFile(file: File, maxLongEdge: Int = 0): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val upright = applyExifRotation(decoded, file)
        return if (maxLongEdge > 0) fitTo(upright, maxLongEdge) else upright
    }

    /** Decodes from a content Uri (gallery / SAF import), downsampled the same way. */
    fun decodeUri(uri: Uri, maxLongEdge: Int = 0): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            exifRotation(ExifInterface(stream))
        } ?: 0
        val upright = if (rotation == 0) decoded else rotate(decoded, rotation.toFloat())
        return if (maxLongEdge > 0) fitTo(upright, maxLongEdge) else upright
    }

    fun writeJpeg(bitmap: Bitmap, target: File, quality: Int = 92): File {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            out.flush()
        }
        return target
    }

    fun writePng(bitmap: Bitmap, target: File): File {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
        }
        return target
    }

    /** Writes the small square-ish bitmap the library grid renders. */
    fun writeThumbnail(bitmap: Bitmap, target: File, edge: Int = 512): File {
        val thumb = fitTo(bitmap, edge)
        val written = writeJpeg(thumb, target, quality = 82)
        if (thumb !== bitmap) thumb.recycle()
        return written
    }

    fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Scales down so the long edge is [maxLongEdge]; never scales up. */
    fun fitTo(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (maxLongEdge <= 0 || longEdge <= maxLongEdge) return bitmap
        val scale = maxLongEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        val degrees = runCatching { exifRotation(ExifInterface(file.absolutePath)) }.getOrDefault(0)
        return if (degrees == 0) bitmap else rotate(bitmap, degrees.toFloat())
    }

    private fun exifRotation(exif: ExifInterface): Int = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    private fun sampleSizeFor(width: Int, height: Int, maxLongEdge: Int): Int {
        if (maxLongEdge <= 0) return 1
        var sample = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= maxLongEdge) {
            longEdge /= 2
            sample *= 2
        }
        return sample
    }
}
