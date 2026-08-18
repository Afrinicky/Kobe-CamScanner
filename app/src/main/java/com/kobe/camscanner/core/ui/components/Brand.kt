package com.kobe.camscanner.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.core.ui.theme.EyebrowStyle
import com.kobe.camscanner.core.ui.theme.KobeTheme

/**
 * The KOBE wordmark: heavy, tightly tracked, filled with the Acrobat-red gradient.
 * The gradient is painted through the glyphs with [BlendMode.SrcIn] rather than by tinting text,
 * so the sweep runs across the whole word instead of per-letter.
 */
@Composable
fun KobeWordmark(
    modifier: Modifier = Modifier,
    showTagline: Boolean = false,
) {
    val extra = KobeTheme.extra
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = "Kobe CamScanner" },
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "KOBE",
            style = MaterialTheme.typography.displaySmall,
            color = Color.Black,
            modifier = Modifier.drawWithCache {
                val brush = extra.brandBrush
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.SrcIn)
                }
            },
        )
        if (showTagline) {
            Text(
                text = "SCAN · ENHANCE · KEEP IT PRIVATE",
                style = EyebrowStyle,
                color = extra.inkFaint,
            )
        }
    }
}

/**
 * The compact app mark used in top bars and empty states: a sheet inside scanner brackets,
 * drawn rather than shipped as a bitmap so it stays crisp at any size and follows the theme.
 */
@Composable
fun KobeMark(
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 28.dp,
) {
    val extra = KobeTheme.extra
    val sheet = MaterialTheme.colorScheme.onSurface
    Spacer(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val s = this.size.minDimension
                val bracket = s * 0.26f
                val thickness = s * 0.10f
                val inset = s * 0.06f
                val brush = extra.brandBrush
                onDrawBehind {
                    // Page
                    val pageInset = s * 0.26f
                    drawRoundRect(
                        color = sheet.copy(alpha = 0.90f),
                        topLeft = Offset(pageInset, pageInset * 0.72f),
                        size = Size(s - pageInset * 2f, s - pageInset * 1.44f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.06f),
                    )
                    // Corner brackets
                    val corners = listOf(
                        Offset(inset, inset) to Offset(1f, 1f),
                        Offset(s - inset, inset) to Offset(-1f, 1f),
                        Offset(inset, s - inset) to Offset(1f, -1f),
                        Offset(s - inset, s - inset) to Offset(-1f, -1f),
                    )
                    corners.forEach { (origin, dir) ->
                        drawLine(
                            brush = brush,
                            start = origin,
                            end = Offset(origin.x + bracket * dir.x, origin.y),
                            strokeWidth = thickness,
                        )
                        drawLine(
                            brush = brush,
                            start = origin,
                            end = Offset(origin.x, origin.y + bracket * dir.y),
                            strokeWidth = thickness,
                        )
                    }
                }
            },
    )
}

/** A hairline rule that matches the theme's outline colour. */
@Composable
fun KobeDivider(modifier: Modifier = Modifier, alpha: Float = 1f) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = KobeTheme.extra.hairline.copy(alpha = alpha),
    )
}

/** Section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
        )
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
