package com.kobe.camscanner.feature.signature

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme

/** One continuous pen stroke. */
data class InkStroke(val points: List<Offset>, val widthPx: Float, val color: Color)

/**
 * Freehand drawing surface, shared by the signature pad and the annotation pen (SDS 30, 31).
 *
 * Strokes are stored as point lists rather than as a bitmap so they can be undone individually and
 * re-rasterised at whatever resolution the page needs. Rendering uses quadratic segments through
 * the midpoints of consecutive samples, which is what turns a jagged finger trail into a smooth
 * line without any smoothing pass.
 */
@Composable
fun InkCanvas(
    strokes: List<InkStroke>,
    onStrokeFinished: (InkStroke) -> Unit,
    modifier: Modifier = Modifier,
    strokeColor: Color = Color.Black,
    strokeWidth: Float = 6f,
    background: Color = Color.White,
    contentDescription: String = "Drawing area",
) {
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier = modifier
            .background(background)
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(strokeColor, strokeWidth) {
                detectDragGestures(
                    onDragStart = { current = listOf(it) },
                    onDragEnd = {
                        if (current.size > 1) {
                            onStrokeFinished(InkStroke(current, strokeWidth, strokeColor))
                        }
                        current = emptyList()
                    },
                    onDragCancel = { current = emptyList() },
                ) { change, _ ->
                    change.consume()
                    current = current + change.position
                }
            },
    ) {
        strokes.forEach { drawInk(it.points, it.widthPx, it.color) }
        if (current.size > 1) drawInk(current, strokeWidth, strokeColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInk(
    points: List<Offset>,
    width: Float,
    color: Color,
) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val point = points[i]
            // Curve through the midpoint so each sample smooths its own corner.
            quadraticTo(
                previous.x,
                previous.y,
                (previous.x + point.x) / 2f,
                (previous.y + point.y) / 2f,
            )
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Rasterises strokes onto a transparent bitmap, trimmed to the ink's own bounds.
 *
 * Trimming matters: a signature dropped onto a page should be the signature, not a rectangle of
 * empty space with a signature somewhere inside it.
 */
fun renderStrokesToBitmap(
    strokes: List<InkStroke>,
    canvasSize: IntSize,
    targetWidth: Int = 1200,
    padding: Float = 12f,
): Bitmap? {
    if (strokes.isEmpty() || canvasSize.width <= 0 || canvasSize.height <= 0) return null

    val allPoints = strokes.flatMap { it.points }
    val minX = (allPoints.minOf { it.x } - padding).coerceAtLeast(0f)
    val minY = (allPoints.minOf { it.y } - padding).coerceAtLeast(0f)
    val maxX = (allPoints.maxOf { it.x } + padding).coerceAtMost(canvasSize.width.toFloat())
    val maxY = (allPoints.maxOf { it.y } + padding).coerceAtMost(canvasSize.height.toFloat())

    val width = (maxX - minX).coerceAtLeast(1f)
    val height = (maxY - minY).coerceAtLeast(1f)
    val scale = targetWidth / width

    val bitmap = Bitmap.createBitmap(
        targetWidth,
        (height * scale).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    strokes.forEach { stroke ->
        paint.color = stroke.color.toArgb()
        paint.strokeWidth = stroke.widthPx * scale
        val path = AndroidPath()
        stroke.points.forEachIndexed { index, point ->
            val x = (point.x - minX) * scale
            val y = (point.y - minY) * scale
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                val previous = stroke.points[index - 1]
                path.quadTo(
                    (previous.x - minX) * scale,
                    (previous.y - minY) * scale,
                    ((previous.x + point.x) / 2f - minX) * scale,
                    ((previous.y + point.y) / 2f - minY) * scale,
                )
            }
        }
        canvas.drawPath(path, paint)
    }
    return bitmap
}

/** The signature pad proper: a wide, paper-white strip with a baseline to sign on. */
@Composable
fun SignaturePad(
    strokes: List<InkStroke>,
    onStrokeFinished: (InkStroke) -> Unit,
    onSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = KobeTheme.extra
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2.1f)
            .clip(KobeRadius.tile)
            .onSizeChanged(onSizeChanged),
    ) {
        InkCanvas(
            strokes = strokes,
            onStrokeFinished = onStrokeFinished,
            modifier = Modifier.fillMaxSize(),
            strokeColor = Color(0xFF12193A),
            strokeWidth = 7f,
            background = Color.White,
            contentDescription = "Sign here with your finger",
        )
        // The signing line, drawn under the ink so a signature can cross it naturally.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = size.height * 0.76f
            drawLine(
                color = extra.hairline,
                start = Offset(size.width * 0.08f, y),
                end = Offset(size.width * 0.92f, y),
                strokeWidth = 1.5f,
            )
        }
    }
}

/** Undo / clear controls shared by the pad and the annotation toolbar. */
@Composable
fun InkControls(
    canUndo: Boolean,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        com.kobe.camscanner.core.ui.components.KobeOutlineButton(
            text = "Undo",
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.weight(1f),
        )
        com.kobe.camscanner.core.ui.components.KobeOutlineButton(
            text = "Clear",
            onClick = onClear,
            enabled = canUndo,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Convenience holder so callers do not have to manage two parallel lists. */
@Composable
fun rememberInkStrokes(): androidx.compose.runtime.snapshots.SnapshotStateList<InkStroke> =
    remember { mutableStateListOf() }
