package com.kobe.camscanner.feature.watermark

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import com.kobe.camscanner.core.ui.components.KobeChip
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeProgressVeil
import com.kobe.camscanner.core.ui.components.KobeSlider
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme

/**
 * Text watermarking (SDS 32).
 *
 * The preview is the actual composite at reduced size, not an approximation drawn in Compose — what
 * the user sees is exactly what gets written, including how the tiling falls and where the text is
 * clipped.
 */
@Composable
fun WatermarkScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: WatermarkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extra = KobeTheme.extra

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            KobeTopBar(title = "Watermark", compact = true, onBack = onCancel)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val preview = state.preview
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Watermarked page preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(KobeRadius.page),
                    )
                } else {
                    AsyncImage(
                        model = state.firstPageFile,
                        contentDescription = "Page preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(KobeRadius.page),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KobeRadius.button)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(BorderStroke(1.dp, extra.hairline), KobeRadius.button)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (state.spec.text.isEmpty()) {
                        Text(
                            text = "CONFIDENTIAL",
                            style = MaterialTheme.typography.bodyLarge,
                            color = extra.inkFaint,
                        )
                    }
                    BasicTextField(
                        value = state.spec.text,
                        onValueChange = viewModel::setText,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(14.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(SUGGESTED_TEXTS) { suggestion ->
                        KobeChip(
                            label = suggestion,
                            selected = state.spec.text == suggestion,
                            onClick = { viewModel.setText(suggestion) },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(WatermarkPosition.entries.toList()) { position ->
                        KobeChip(
                            label = position.label,
                            selected = state.spec.position == position,
                            onClick = { viewModel.setPosition(position) },
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                KobeSlider(
                    label = "Opacity",
                    value = state.spec.opacity,
                    valueRange = 0.05f..0.6f,
                    onValueChange = viewModel::setOpacity,
                )
                KobeSlider(
                    label = "Size",
                    value = state.spec.relativeSize,
                    valueRange = 0.04f..0.24f,
                    onValueChange = viewModel::setSize,
                )
                KobeSlider(
                    label = "Rotation",
                    value = state.spec.rotationDegrees,
                    valueRange = -90f..90f,
                    onValueChange = viewModel::setRotation,
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.kobe.camscanner.core.ui.components.KobeOutlineButton(
                        text = "Cancel",
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                    KobePrimaryButton(
                        text = "Apply to all pages",
                        onClick = { viewModel.apply(onDone) },
                        enabled = state.spec.text.isNotBlank(),
                        modifier = Modifier.weight(1.4f),
                    )
                }
            }
        }

        KobeProgressVeil(
            visible = state.isApplying,
            label = "Adding watermark",
            detail = state.progressLabel,
        )
    }
}

/** The four marks that cover almost every real request. */
private val SUGGESTED_TEXTS = listOf("CONFIDENTIAL", "COPY", "DRAFT", "ORIGINAL", "PAID")
