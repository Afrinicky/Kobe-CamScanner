package com.kobe.camscanner.feature.filter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Deblur
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RotateLeft
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kobe.camscanner.core.ui.components.KobeIconButton
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeProgressVeil
import com.kobe.camscanner.core.ui.components.KobeSlider
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeCanvasTheme
import com.kobe.camscanner.core.ui.theme.KobePalette
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.ScanFilter

/**
 * Filters and enhancement (SDS 12).
 *
 * The layout puts the page first and everything else beneath it: a filmstrip of real previews, then
 * — only if asked for — the five numeric controls. Most scans never need the sliders, so they stay
 * collapsed rather than crowding the eight decisions that actually matter.
 */
@Composable
fun FilterScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: FilterViewModel = hiltViewModel(),
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
                    title = "Enhance",
                    compact = true,
                    onBack = onCancel,
                    actions = {
                        KobeIconButton(
                            icon = Icons.Rounded.RotateLeft,
                            contentDescription = "Rotate left",
                            onClick = { viewModel.rotate(-90) },
                            container = Color.White.copy(alpha = 0.10f),
                            tint = Color.White,
                        )
                        KobeIconButton(
                            icon = Icons.Rounded.RotateRight,
                            contentDescription = "Rotate right",
                            onClick = { viewModel.rotate(90) },
                            container = Color.White.copy(alpha = 0.10f),
                            tint = Color.White,
                        )
                        KobeIconButton(
                            icon = Icons.Rounded.Tune,
                            contentDescription = "Adjustments",
                            onClick = viewModel::toggleAdjustments,
                            container = if (state.showAdjustments) Color.White
                            else Color.White.copy(alpha = 0.10f),
                            tint = if (state.showAdjustments) Color.Black else Color.White,
                        )
                    },
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = state.livePreview
                    if (preview != null) {
                        androidx.compose.foundation.Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "Enhanced page preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(KobeRadius.page),
                        )
                    } else {
                        AsyncImage(
                            model = state.page?.processedFile,
                            contentDescription = "Page preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(KobeRadius.page),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = state.showAdjustments,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    AdjustmentPanel(
                        adjustments = state.adjustments,
                        onChange = viewModel::setAdjustments,
                        onReset = viewModel::resetAdjustments,
                    )
                }

                FilterStrip(
                    selected = state.filter,
                    previews = state.previews,
                    onSelect = viewModel::selectFilter,
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    KobePrimaryButton(
                        text = "Apply",
                        onClick = { viewModel.apply(onDone) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            KobeProgressVeil(
                visible = state.isApplying,
                label = "Enhancing",
                detail = "Rendering at full resolution",
            )
        }
    }
}

/**
 * The filmstrip. Each tile is the user's own page under that filter, not a stock swatch — the whole
 * point is being able to see which one suits *this* document.
 */
@Composable
private fun FilterStrip(
    selected: ScanFilter,
    previews: Map<ScanFilter, android.graphics.Bitmap>,
    onSelect: (ScanFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ScanFilter.entries.toList()) { filter ->
            val isSelected = filter == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(74.dp)
                    .semantics {
                        this.selected = isSelected
                        contentDescription = filter.label
                    }
                    .clickable { onSelect(filter) },
            ) {
                Box(
                    modifier = Modifier
                        .size(74.dp, 96.dp)
                        .clip(KobeRadius.tile)
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.16f),
                            shape = KobeRadius.tile,
                        ),
                ) {
                    previews[filter]?.let { bitmap ->
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(KobeRadius.tile),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = filter.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The five manual controls from SDS 12. */
@Composable
private fun AdjustmentPanel(
    adjustments: Adjustments,
    onChange: (Adjustments) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Adjustments",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Reset",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onReset).padding(8.dp),
            )
        }
        KobeSlider(
            label = "Brightness",
            icon = Icons.Rounded.Brightness6,
            value = adjustments.brightness,
            onValueChange = { onChange(adjustments.copy(brightness = it)) },
        )
        KobeSlider(
            label = "Contrast",
            icon = Icons.Rounded.Contrast,
            value = adjustments.contrast,
            onValueChange = { onChange(adjustments.copy(contrast = it)) },
        )
        KobeSlider(
            label = "Sharpness",
            icon = Icons.Rounded.Deblur,
            value = adjustments.sharpness,
            onValueChange = { onChange(adjustments.copy(sharpness = it)) },
        )
        KobeSlider(
            label = "Saturation",
            icon = Icons.Rounded.Palette,
            value = adjustments.saturation,
            onValueChange = { onChange(adjustments.copy(saturation = it)) },
        )
        KobeSlider(
            label = "Exposure",
            icon = Icons.Rounded.Exposure,
            value = adjustments.exposure,
            onValueChange = { onChange(adjustments.copy(exposure = it)) },
        )
    }
}
