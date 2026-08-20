package com.kobe.camscanner.feature.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.core.ui.components.KobeDivider
import com.kobe.camscanner.core.ui.theme.KobeMotion
import com.kobe.camscanner.core.ui.theme.KobeTheme

/** The four places the app can be, plus the scan action that sits between them. */
enum class ShellTab(val label: String, val icon: ImageVector) {
    DOCUMENTS("Docs", Icons.Rounded.Folder),
    TOOLS("Tools", Icons.Rounded.Build),
    SEARCH("Search", Icons.Rounded.Search),
    SETTINGS("Settings", Icons.Rounded.Tune),
}

/**
 * The home bar.
 *
 * Scanning is the reason the app exists, so it gets a raised disc in the middle rather than a tab
 * of its own — the arrangement every scanner app converges on, because it keeps the one action a
 * user came for under their thumb from anywhere in the app.
 *
 * The tabs either side are deliberately quiet: no filled pills, no badges, just a colour change, so
 * the red disc never has to compete for attention.
 */
@Composable
fun KobeBottomBar(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        KobeDivider(modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabItem(ShellTab.DOCUMENTS, selected, onSelect, Modifier.weight(1f))
                TabItem(ShellTab.TOOLS, selected, onSelect, Modifier.weight(1f))
                // The scan disc floats over this gap; the spacer reserves the room for it.
                Spacer(Modifier.width(72.dp))
                TabItem(ShellTab.SEARCH, selected, onSelect, Modifier.weight(1f))
                TabItem(ShellTab.SETTINGS, selected, onSelect, Modifier.weight(1f))
            }

            ScanButton(
                onClick = onScan,
                brush = KobeTheme.extra.brandBrush,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    // Lifted clear of the bar so it reads as the primary action, not a fifth tab.
                    .offset(y = (-22).dp),
            )
        }
    }
}

@Composable
private fun TabItem(
    tab: ShellTab,
    selectedTab: ShellTab,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = tab == selectedTab
    val extra = KobeTheme.extra
    val tint by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else extra.inkFaint,
        animationSpec = KobeMotion.normal(),
        label = "tabTint",
    )

    Column(
        modifier = modifier
            .semantics {
                selected = isSelected
                contentDescription = tab.label
                role = Role.Tab
            }
            .clip(RoundedCornerShape(14.dp))
            .clickable(indication = null, interactionSource = null) { onSelect(tab) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(Modifier.height(4.dp))
        Text(text = tab.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** The raised capture disc. */
@Composable
private fun ScanButton(
    onClick: () -> Unit,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = KobeMotion.snappySpring(),
        label = "scanScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(62.dp)
            .shadow(14.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.5f))
            .clip(CircleShape)
            .background(brush)
            .semantics { contentDescription = "Scan a document" }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.DocumentScanner,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(27.dp),
        )
    }
}
