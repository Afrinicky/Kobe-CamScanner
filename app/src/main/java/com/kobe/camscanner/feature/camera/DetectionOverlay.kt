package com.kobe.camscanner.feature.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.scanner.StabilityTracker

/**
 * The detected boundary, drawn over the preview.
 *
 * Three things are happening at once and the drawing keeps them legible: the *shape* of the quad
 * shows what will be captured, the *tint* shows whether the detector is confident, and the corner
 * arc shows how much longer the phone must stay still. The scrim outside the quad is what actually
 * sells it — the page appears lit while the desk around it falls away.
 */
@Composable
fun DetectionOverlay(
    state: StabilityTracker.State,
    modifier: Modifier = Modifier,
    accent: Color,
    detectColor: Color,
) {
    val quad = state.quad
    val targetAlpha = if (quad == null) 0f else 1f
    val alpha by animateFloatAsState(targetAlpha, label = "overlayAlpha")
    val progress by animateFloatAsState(state.progress, label = "holdProgress")

    val locked = state.phase == StabilityTracker.Phase.HOLD_STEADY ||
        state.phase == StabilityTracker.Phase.CAPTURE
    val strokeColor = if (locked) detectColor else accent

    Canvas(modifier = modifier) {
        if (quad == null || alpha <= 0.01f) {
            drawSearchFrame(accent.copy(alpha = 0.5f))
            return@Canvas
        }

        val points = quad.points.map { Offset(it.x * size.width, it.y * size.height) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }

        // Darken everything outside the document. Even-odd filling means one draw call handles the
        // "whole screen minus this quad" region without a layer or a mask.
        val scrim = Path().apply {
            addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
            addPath(path)
            fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
        }
        drawPath(scrim, Color.Black.copy(alpha = 0.45f * alpha))

        drawPath(path, strokeColor.copy(alpha = 0.14f * alpha))
        drawPath(
            path = path,
            color = strokeColor.copy(alpha = alpha),
            style = Stroke(width = 3.dp.toPx()),
        )

        points.forEachIndexed { index, point ->
            drawCornerBracket(
                centre = point,
                neighbours = listOf(points[(index + 1) % 4], points[(index + 3) % 4]),
                color = strokeColor.copy(alpha = alpha),
                strokeWidth = 5.dp.toPx(),
                length = 26.dp.toPx(),
            )
            if (progress > 0.02f) {
                drawHoldArc(point, progress, detectColor.copy(alpha = alpha))
            }
        }
    }
}

/** The idle frame: a dashed rectangle hinting where to put the page. */
private fun DrawScope.drawSearchFrame(color: Color) {
    val inset = size.minDimension * 0.12f
    val corner = 18.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset * 1.4f),
        size = Size(size.width - inset * 2, size.height - inset * 2.8f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner),
        style = Stroke(
            width = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 12.dp.toPx())),
        ),
    )
}

/**
 * A short segment along each edge meeting at the corner, rather than a dot. Brackets read as
 * "this is a frame" at a glance and stay visible against both a white page and a dark desk.
 */
private fun DrawScope.drawCornerBracket(
    centre: Offset,
    neighbours: List<Offset>,
    color: Color,
    strokeWidth: Float,
    length: Float,
) {
    neighbours.forEach { neighbour ->
        val dx = neighbour.x - centre.x
        val dy = neighbour.y - centre.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance < 1f) return@forEach
        val scale = (length / distance).coerceAtMost(0.45f)
        drawLine(
            color = color,
            start = centre,
            end = Offset(centre.x + dx * scale, centre.y + dy * scale),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** The countdown ring that fills while the phone is held steady. */
private fun DrawScope.drawHoldArc(centre: Offset, progress: Float, color: Color) {
    val radius = 15.dp.toPx()
    drawArc(
        color = color,
        startAngle = -90f,
        sweepAngle = 360f * progress,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}

/** Rule-of-thirds guides, drawn only when the user turns the grid on. */
@Composable
fun FramingGrid(modifier: Modifier = Modifier, color: Color = Color.White.copy(alpha = 0.22f)) {
    Canvas(modifier = modifier) {
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}
