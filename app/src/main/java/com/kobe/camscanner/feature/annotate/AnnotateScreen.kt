package com.kobe.camscanner.feature.annotate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kobe.camscanner.core.ui.components.KobeChip
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeProgressVeil
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeCanvasTheme
import com.kobe.camscanner.core.ui.theme.KobePalette
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.feature.signature.InkCanvas
import com.kobe.camscanner.feature.signature.InkControls
import com.kobe.camscanner.feature.signature.InkStroke

/** The drawing tools offered by SDS 30. */
enum class AnnotationTool(val label: String) {
    PEN("Pen"),
    HIGHLIGHTER("Highlighter"),
}

/**
 * Page annotation (SDS 30).
 *
 * The ink layer sits over the page at the page's own aspect ratio, so what the user draws lands
 * exactly where they drew it when the strokes are rasterised at full resolution on save. The
 * highlighter is the same pen with a wide, translucent stroke — which is what a highlighter is.
 */
@Composable
fun AnnotateScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: AnnotateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tool by remember { mutableStateOf(AnnotationTool.PEN) }
    var inkColor by remember { mutableStateOf(PEN_COLOURS.first()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val strokeWidth = if (tool == AnnotationTool.HIGHLIGHTER) 26f else 6f
    val effectiveColor = if (tool == AnnotationTool.HIGHLIGHTER) inkColor.copy(alpha = 0.35f) else inkColor

    KobeCanvasTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KobePalette.Viewfinder),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                KobeTopBar(title = "Annotate", compact = true, onBack = onCancel)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(KobeRadius.page)
                            .onSizeChanged { canvasSize = it },
                    ) {
                        AsyncImage(
                            model = state.pageFile,
                            contentDescription = "Page being annotated",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        InkCanvas(
                            strokes = state.strokes,
                            onStrokeFinished = viewModel::addStroke,
                            strokeColor = effectiveColor,
                            strokeWidth = strokeWidth,
                            background = Color.Transparent,
                            contentDescription = "Annotation layer",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KobeChip(
                            label = AnnotationTool.PEN.label,
                            icon = Icons.Rounded.Draw,
                            selected = tool == AnnotationTool.PEN,
                            onClick = { tool = AnnotationTool.PEN },
                            accent = Color.White,
                        )
                        KobeChip(
                            label = AnnotationTool.HIGHLIGHTER.label,
                            icon = Icons.Rounded.Highlight,
                            selected = tool == AnnotationTool.HIGHLIGHTER,
                            onClick = { tool = AnnotationTool.HIGHLIGHTER },
                            accent = Color.White,
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                    ) {
                        items(PEN_COLOURS) { colour ->
                            ColourDot(
                                colour = colour,
                                selected = colour == inkColor,
                                onClick = { inkColor = colour },
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    InkControls(
                        canUndo = state.strokes.isNotEmpty(),
                        onUndo = viewModel::undo,
                        onClear = viewModel::clear,
                    )

                    Spacer(Modifier.height(12.dp))
                    KobePrimaryButton(
                        text = "Apply",
                        onClick = { viewModel.apply(canvasSize, onDone) },
                        enabled = state.strokes.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            KobeProgressVeil(visible = state.isApplying, label = "Applying annotations")
        }
    }
}

@Composable
private fun ColourDot(colour: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 38.dp else 32.dp)
            .clip(CircleShape)
            .background(colour)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.3f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/** A small, deliberately limited palette — enough to mark up a page, not enough to make a mess. */
private val PEN_COLOURS = listOf(
    Color(0xFFD2261C),
    Color(0xFF12193A),
    Color(0xFF0E9384),
    Color(0xFFE8971A),
    Color(0xFF3B5BDB),
    Color(0xFF12805C),
)

/** Re-exported so callers can build their own toolbars from the same stroke type. */
typealias AnnotationStroke = InkStroke
