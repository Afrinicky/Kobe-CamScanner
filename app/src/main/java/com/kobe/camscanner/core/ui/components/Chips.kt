package com.kobe.camscanner.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.core.ui.theme.KobeMotion
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme

/**
 * Selectable pill. Selection is carried by fill *and* by a border weight change, never by colour
 * alone — colour-only state is called out as a defect in SDS 50.
 */
@Composable
fun KobeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val container by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.surface,
        animationSpec = KobeMotion.normal(),
        label = "chipContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        animationSpec = KobeMotion.normal(),
        label = "chipContent",
    )
    Row(
        modifier = modifier
            .semantics {
                this.selected = selected
                this.contentDescription = label
            }
            .clip(KobeRadius.chip)
            .background(container)
            .border(
                BorderStroke(
                    width = if (selected) 0.dp else 1.dp,
                    color = if (selected) Color.Transparent else KobeTheme.extra.hairline,
                ),
                KobeRadius.chip,
            )
            .clickable(role = Role.Tab, onClick = onClick)
            .defaultMinSize(minHeight = 40.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** Small non-interactive count/type badge. */
@Composable
fun KobeBadge(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .clip(KobeRadius.chip)
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = accent)
    }
}
