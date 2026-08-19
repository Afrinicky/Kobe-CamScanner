package com.kobe.camscanner.core.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Click with a ripple that is bounded to the caller's own clip. The interaction source is passed in
 * so the caller can drive its own press animation from the same gesture.
 */
fun Modifier.pressableClick(
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        interactionSource = interactionSource,
        indication = ripple(color = Color.White.copy(alpha = 0.35f)),
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
    )
}

/**
 * Click that carries its label in semantics rather than on a child Icon, so TalkBack announces the
 * action once instead of announcing a decorative glyph.
 */
fun Modifier.accessibleClick(
    label: String?,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = composed {
    this
        .semantics { if (label != null) contentDescription = label }
        .clickable(
            interactionSource = interactionSource,
            indication = ripple(bounded = false, radius = 24.dp),
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        )
}

/**
 * A one-pixel top highlight plus a soft vertical wash. Used on cards to give them a sense of being
 * lit from above without resorting to heavy drop shadows, which look muddy on dark themes.
 */
fun Modifier.softSheen(
    highlight: Color = Color.White.copy(alpha = 0.55f),
    strength: Float = 1f,
): Modifier = drawWithCache {
    val wash = Brush.verticalGradient(
        colors = listOf(
            highlight.copy(alpha = highlight.alpha * 0.35f * strength),
            Color.Transparent,
        ),
        startY = 0f,
        endY = size.height * 0.55f,
    )
    onDrawWithContent {
        drawContent()
        drawRect(wash)
        drawLine(
            color = highlight.copy(alpha = highlight.alpha * strength),
            start = Offset(0f, 0.5f),
            end = Offset(size.width, 0.5f),
            strokeWidth = 1f,
        )
    }
}

/**
 * Tap plus optional long-press, sharing one interaction source with the caller's press animation.
 * Long-press is how multi-select starts in the library (SDS 15).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedPress(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier = composed {
    combinedClickable(
        interactionSource = interactionSource,
        indication = ripple(),
        enabled = enabled,
        onLongClick = onLongClick,
        onClick = onClick,
    )
}
