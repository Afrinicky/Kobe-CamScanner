package com.kobe.camscanner.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import kotlin.math.roundToInt

/**
 * The library search field. Local search only — it queries the on-device OCR index (SDS 25).
 */
@Composable
fun KobeSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search documents and text",
    autoFocus: Boolean = false,
    onSearch: () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KobeRadius.chip)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, KobeTheme.extra.hairline), KobeRadius.chip)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = KobeTheme.extra.inkFaint,
            modifier = Modifier.size(20.dp),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KobeTheme.extra.inkFaint,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
        if (value.isNotEmpty()) {
            KobeIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Clear search",
                onClick = { onValueChange("") },
                boxSize = 28.dp,
                container = Color.Transparent,
                tint = KobeTheme.extra.inkMuted,
            )
        }
    }

    if (autoFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}

/**
 * Labelled adjustment slider used by the enhancement controls (SDS 12).
 * The numeric read-out matters: users returning to a scan need to see what they set.
 */
@Composable
fun KobeSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    icon: ImageVector? = null,
    onValueChangeFinished: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = KobeTheme.extra.inkMuted,
                    modifier = Modifier.size(16.dp),
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = KobeTheme.extra.inkMuted,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = percentLabel(value, valueRange),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            interactionSource = interaction,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = KobeTheme.extra.hairline,
            ),
        )
    }
}

private fun percentLabel(value: Float, range: ClosedFloatingPointRange<Float>): String {
    // Symmetric ranges read as a signed offset from neutral; one-sided ranges read as a percentage.
    return if (range.start < 0f) {
        val pct = (value * 100f).roundToInt()
        if (pct > 0) "+$pct" else "$pct"
    } else {
        val span = range.endInclusive - range.start
        val pct = (((value - range.start) / span) * 100f).roundToInt()
        "$pct%"
    }
}
