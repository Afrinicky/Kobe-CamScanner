package com.kobe.camscanner.feature.watermark

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.kobe.camscanner.core.common.DefaultDispatcher
import com.kobe.camscanner.core.storage.ImageStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/** Where a watermark sits on the page (SDS 32). */
enum class WatermarkPosition(val label: String) {
    CENTRE("Centre"),
    TILED("Tiled"),
    TOP("Top"),
    BOTTOM("Bottom"),
    DIAGONAL("Diagonal"),
}

data class WatermarkSpec(
    val text: String,
    val opacity: Float = 0.22f,
    val rotationDegrees: Float = -30f,
    val position: WatermarkPosition = WatermarkPosition.DIAGONAL,
    val relativeSize: Float = 0.12f,
    val colorArgb: Int = android.graphics.Color.BLACK,
    val bold: Boolean = true,
)

/**
 * Text watermarks (SDS 32).
 *
 * The size is expressed as a fraction of the page's diagonal rather than in points, so "CONFIDENTIAL"
 * covers the same proportion of an A5 receipt as of an A3 drawing. Composites happen on a copy —
 * the stored page is never overwritten, so a watermark can always be removed by re-rendering.
 */
@Singleton
class WatermarkEngine @Inject constructor(
    private val imageStore: ImageStore,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** Draws [spec] onto a copy of [source] and returns the new bitmap. */
    fun apply(source: Bitmap, spec: WatermarkSpec): Bitmap {
        if (spec.text.isBlank()) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val canvas = Canvas(output)

        val diagonal = hypot(output.width.toDouble(), output.height.toDouble()).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.colorArgb
            alpha = (spec.opacity.coerceIn(0f, 1f) * 255).toInt()
            textSize = diagonal * spec.relativeSize.coerceIn(0.02f, 0.4f)
            typeface = Typeface.create(
                Typeface.DEFAULT,
                if (spec.bold) Typeface.BOLD else Typeface.NORMAL,
            )
            textAlign = Paint.Align.CENTER
        }

        when (spec.position) {
            WatermarkPosition.TILED -> drawTiled(canvas, output, spec, paint)
            else -> drawSingle(canvas, output, spec, paint)
        }
        return output
    }

    /** Applies a watermark to a page file in place, writing the result back as JPEG. */
    suspend fun applyToFile(
        source: File,
        target: File,
        spec: WatermarkSpec,
    ): File? = withContext(dispatcher) {
        val bitmap = imageStore.decodeFile(source) ?: return@withContext null
        val marked = apply(bitmap, spec)
        imageStore.writeJpeg(marked, target, quality = 93)
        if (marked !== bitmap) marked.recycle()
        bitmap.recycle()
        target
    }

    private fun drawSingle(canvas: Canvas, bitmap: Bitmap, spec: WatermarkSpec, paint: Paint) {
        val x = bitmap.width / 2f
        val y = when (spec.position) {
            WatermarkPosition.TOP -> bitmap.height * 0.16f
            WatermarkPosition.BOTTOM -> bitmap.height * 0.88f
            else -> bitmap.height / 2f
        }
        canvas.save()
        canvas.rotate(spec.rotationDegrees, x, y)
        canvas.drawText(spec.text, x, y, paint)
        canvas.restore()
    }

    /**
     * Tiles the text across a rotated canvas. Rotating the *canvas* rather than each string means
     * the tiling grid stays regular at any angle, which is what stops a tiled watermark looking
     * like scattered text.
     */
    private fun drawTiled(canvas: Canvas, bitmap: Bitmap, spec: WatermarkSpec, paint: Paint) {
        val textWidth = paint.measureText(spec.text)
        val stepX = textWidth * 1.6f
        val stepY = paint.textSize * 3.2f
        // Oversize the grid so the rotated canvas still covers every corner of the page.
        val span = hypot(bitmap.width.toDouble(), bitmap.height.toDouble()).toFloat()

        canvas.save()
        canvas.rotate(spec.rotationDegrees, bitmap.width / 2f, bitmap.height / 2f)
        var y = -span / 2f
        while (y < bitmap.height + span / 2f) {
            var x = -span / 2f
            while (x < bitmap.width + span / 2f) {
                canvas.drawText(spec.text, x, y, paint)
                x += stepX
            }
            y += stepY
        }
        canvas.restore()
    }
}
