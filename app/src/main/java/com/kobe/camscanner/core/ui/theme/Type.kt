package com.kobe.camscanner.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * A single-family scale. Kobe ships no font binaries — the platform sans is used everywhere so the
 * app inherits the user's own font-size preference (SDS 50) and stays small on disk.
 *
 * The scale is deliberately tight at the top end: display sizes get negative tracking so headings
 * read as set type rather than as enlarged UI text.
 */
private val Sans = FontFamily.SansSerif

private val TrimBoth = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = Sans,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeightStyle = TrimBoth,
)

val KobeTypography = Typography(
    displayLarge = style(44, 48, FontWeight.Bold, -1.4),
    displayMedium = style(36, 40, FontWeight.Bold, -1.1),
    displaySmall = style(30, 36, FontWeight.Bold, -0.8),

    headlineLarge = style(26, 32, FontWeight.Bold, -0.6),
    headlineMedium = style(22, 28, FontWeight.SemiBold, -0.4),
    headlineSmall = style(19, 26, FontWeight.SemiBold, -0.2),

    titleLarge = style(18, 24, FontWeight.SemiBold, -0.2),
    titleMedium = style(16, 22, FontWeight.SemiBold, 0.0),
    titleSmall = style(14, 20, FontWeight.SemiBold, 0.1),

    bodyLarge = style(16, 24, FontWeight.Normal, 0.0),
    bodyMedium = style(14, 21, FontWeight.Normal, 0.05),
    bodySmall = style(12, 18, FontWeight.Normal, 0.1),

    labelLarge = style(14, 18, FontWeight.SemiBold, 0.2),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.3),
    labelSmall = style(11, 14, FontWeight.Medium, 0.5),
)

/** Small-caps-ish section eyebrow used above grouped content. */
val EyebrowStyle: TextStyle = style(11, 14, FontWeight.Bold, 1.2)
