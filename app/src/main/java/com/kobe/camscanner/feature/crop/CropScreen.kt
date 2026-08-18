package com.kobe.camscanner.feature.crop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kobe.camscanner.R
import com.kobe.camscanner.core.ui.components.KobeOutlineButton
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeProgressVeil
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeCanvasTheme
import com.kobe.camscanner.core.ui.theme.KobePalette
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import kotlin.math.hypot

/**
 * Manual corner adjustment (SDS 10).
 *
 * Dragging a corner on a phone means the fingertip covers exactly the thing being positioned, so
 * the screen shows a magnifier: a circular loupe near the opposite side of the screen, showing the
 * area under the finger at 2.5x with a crosshair. That is the difference between "roughly on the
 * edge" and "on the edge".
 */
@Composable
fun CropScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: CropViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    KobeCanvasTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KobePalette.Viewfinder),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                KobeTopBar(
                    title = "Adjust corners",
                    compact = true,
                    onBack = onCancel,
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CornerEditor(
                        imageFile = state.originalFile,
                        quad = state.quad,
                        onCornerMoved = viewModel::moveCorner,
                    )
                }

                CropActions(
                    onReset = viewModel::reset,
                    onWholePage = viewModel::selectWholePage,
                    onApply = { viewModel.apply(onDone) },
                )
            }

            KobeProgressVeil(visible = state.isApplying, label = "Applying")
        }
    }
}

/**
 * The image with a draggable quad on top.
 *
 * All corner state is normalised, so the same values work whatever the view is scaled to — which
 * matters because the same quad then drives the full-resolution warp.
 */
@Composable
private fun CornerEditor(
    imageFile: java.io.File?,
    quad: Quad,
    onCornerMoved: (Int, PointN) -> Unit,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var activeCorner by remember { mutableStateOf(-1) }
    var fingerPosition by remember { mutableStateOf(Offset.Unspecified) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .onSizeChanged { canvasSize = it },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageFile,
            contentDescription = "Page being cropped",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Four draggable corner handles" }
                .pointerInput(canvasSize) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            activeCorner = nearestCorner(quad, offset, size.width.toFloat(), size.height.toFloat())
                            fingerPosition = offset
                        },
                        onDragEnd = {
                            activeCorner = -1
                            fingerPosition = Offset.Unspecified
                        },
                        onDragCancel = {
                            activeCorner = -1
                            fingerPosition = Offset.Unspecified
                        },
                    ) { change, _ ->
                        change.consume()
                        if (activeCorner >= 0) {
                            fingerPosition = change.position
                            onCornerMoved(
                                activeCorner,
                                PointN(
                                    change.position.x / size.width,
                                    change.position.y / size.height,
                                ),
                            )
                        }
                    }
                },
        ) {
            val points = quad.points.map { Offset(it.x * size.width, it.y * size.height) }
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }

            val scrim = Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                addPath(path)
                fillType = PathFillType.EvenOdd
            }
            drawPath(scrim, Color.Black.copy(alpha = 0.55f))

            // A one-third grid inside the selection makes it obvious when an edge is off-parallel.
            for (i in 1..2) {
                val topEdge = lerp(points[0], points[1], i / 3f)
                val bottomEdge = lerp(points[3], points[2], i / 3f)
                drawLine(Color.White.copy(alpha = 0.18f), topEdge, bottomEdge, 1.dp.toPx())
                val leftEdge = lerp(points[0], points[3], i / 3f)
                val rightEdge = lerp(points[1], points[2], i / 3f)
                drawLine(Color.White.copy(alpha = 0.18f), leftEdge, rightEdge, 1.dp.toPx())
            }

            drawPath(path, Color.White, style = Stroke(width = 2.dp.toPx()))

            points.forEachIndexed { index, point ->
                val active = index == activeCorner
                val radius = if (active) 20.dp.toPx() else 14.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.22f), radius + 8.dp.toPx(), point)
                drawCircle(Color.White, radius, point, style = Stroke(width = 3.dp.toPx()))
                drawCircle(KobePalette.Red600, radius * 0.42f, point)
            }
        }

        if (activeCorner >= 0 && fingerPosition != Offset.Unspecified && canvasSize != IntSize.Zero) {
            Magnifier(
                imageFile = imageFile,
                focus = fingerPosition,
                canvasSize = canvasSize,
                alignToStart = fingerPosition.x > canvasSize.width / 2f,
            )
        }
    }
}

/**
 * The loupe.
 *
 * Implemented as a graphics-layer transform on a second copy of the same image: scale about the
 * top-left, then translate so the point under the finger lands in the centre of a circular clip.
 * No pixel copying and no second bitmap — it simply re-draws what the loader already decoded.
 */
@Composable
private fun BoxScope.Magnifier(
    imageFile: java.io.File?,
    focus: Offset,
    canvasSize: IntSize,
    alignToStart: Boolean,
) {
    val diameter = 116.dp
    val density = LocalDensity.current
    val radiusPx = with(density) { diameter.toPx() / 2f }

    Box(
        modifier = Modifier
            .align(if (alignToStart) Alignment.TopStart else Alignment.TopEnd)
            .padding(12.dp)
            .size(diameter)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = imageFile,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(
                    with(density) { canvasSize.width.toDp() },
                    with(density) { canvasSize.height.toDp() },
                )
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = MAGNIFIER_ZOOM
                    scaleY = MAGNIFIER_ZOOM
                    translationX = radiusPx - focus.x * MAGNIFIER_ZOOM
                    translationY = radiusPx - focus.y * MAGNIFIER_ZOOM
                },
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cross = 10.dp.toPx()
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawLine(
                KobePalette.Red600,
                centre.copy(x = centre.x - cross),
                centre.copy(x = centre.x + cross),
                2.dp.toPx(),
            )
            drawLine(
                KobePalette.Red600,
                centre.copy(y = centre.y - cross),
                centre.copy(y = centre.y + cross),
                2.dp.toPx(),
            )
            drawCircle(
                Color.White.copy(alpha = 0.6f),
                size.minDimension / 2f - 1.dp.toPx(),
                style = Stroke(2.dp.toPx()),
            )
        }
    }
}

@Composable
private fun CropActions(
    onReset: () -> Unit,
    onWholePage: () -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KobeOutlineButton(
                text = "Reset",
                icon = Icons.Rounded.Refresh,
                onClick = onReset,
                modifier = Modifier.weight(1f),
            )
            KobeOutlineButton(
                text = "Whole page",
                icon = Icons.Rounded.CropFree,
                onClick = onWholePage,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        KobePrimaryButton(
            text = "Apply",
            onClick = onApply,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.cd_corner_handle),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------ geometry helpers

/**
 * Picks the corner a touch was aimed at. The search is unbounded on purpose: a tap anywhere grabs
 * the nearest corner, which is far more forgiving than requiring a hit inside a small handle.
 */
private fun nearestCorner(quad: Quad, touch: Offset, width: Float, height: Float): Int {
    var best = -1
    var bestDistance = Float.MAX_VALUE
    quad.points.forEachIndexed { index, point ->
        val distance = hypot(point.x * width - touch.x, point.y * height - touch.y)
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    return best
}

private fun lerp(a: Offset, b: Offset, t: Float) =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private const val MAGNIFIER_ZOOM = 2.5f
