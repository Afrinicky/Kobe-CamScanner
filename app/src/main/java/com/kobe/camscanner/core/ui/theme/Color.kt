package com.kobe.camscanner.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Kobe's palette.
 *
 * The reds are drawn from the Adobe Acrobat family — a deep, saturated signal red that reads as
 * "PDF" instantly — and are used sparingly: brand, primary action, and active state only. Every
 * other surface is a warm neutral so scanned pages (which are almost always white) sit on the
 * screen without fighting the chrome.
 */
object KobePalette {
    // --- Acrobat reds ---------------------------------------------------
    val Red900 = Color(0xFF6E0D07)
    val Red800 = Color(0xFF96150E)
    val Red700 = Color(0xFFA81810)
    val Red600 = Color(0xFFD2261C)   // primary
    val Red500 = Color(0xFFE8402F)
    val Red400 = Color(0xFFFF5B4A)   // primary on dark
    val Red200 = Color(0xFFF6B4AB)
    val Red100 = Color(0xFFFBDCD7)
    val Red50 = Color(0xFFFDF0EE)

    // --- Warm neutrals (paper) ------------------------------------------
    val Ink = Color(0xFF141011)
    val Ink800 = Color(0xFF2A2324)
    val Ink600 = Color(0xFF4A4243)
    val Ink400 = Color(0xFF6B6260)
    val Ink300 = Color(0xFF9A918E)
    val Ink200 = Color(0xFFC7BFBC)
    val Hairline = Color(0xFFE7E1DE)
    val SurfaceMuted = Color(0xFFF3EFED)
    val Paper = Color(0xFFFBF9F8)
    val White = Color(0xFFFFFFFF)

    // --- Dark surfaces ---------------------------------------------------
    val NightPaper = Color(0xFF0E0C0D)
    val NightSurface = Color(0xFF171415)
    val NightSurfaceMuted = Color(0xFF211D1E)
    val NightHairline = Color(0xFF2E2829)
    val NightInk = Color(0xFFF5F1F0)
    val NightInkMuted = Color(0xFFA79E9C)

    // --- Functional accents (document types, filter chips, states) -------
    val Amber = Color(0xFFE8971A)
    val Teal = Color(0xFF0E9384)
    val Indigo = Color(0xFF3B5BDB)
    val Violet = Color(0xFF7048E8)
    val Emerald = Color(0xFF12805C)
    val Slate = Color(0xFF5B6472)

    // --- Camera / canvas -------------------------------------------------
    val Viewfinder = Color(0xFF080708)
    val DetectGreen = Color(0xFF2ED573)
    val Scrim = Color(0x99000000)
}

/**
 * Brand values that have no Material role. Reached through [KobeTheme.extra].
 */
@Immutable
data class KobeExtraColors(
    val paper: Color,
    val hairline: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val surfaceMuted: Color,
    val brandStart: Color,
    val brandMid: Color,
    val brandEnd: Color,
    val amber: Color,
    val teal: Color,
    val indigo: Color,
    val violet: Color,
    val emerald: Color,
    val slate: Color,
    val detect: Color,
    val scrim: Color,
    val isDark: Boolean,
) {
    /** The Kobe wordmark / primary-action gradient. */
    val brandBrush: Brush
        get() = Brush.linearGradient(listOf(brandStart, brandMid, brandEnd))

    val brandBrushSoft: Brush
        get() = Brush.linearGradient(listOf(brandStart.copy(alpha = 0.18f), brandEnd.copy(alpha = 0.06f)))
}

internal val LightExtraColors = KobeExtraColors(
    paper = KobePalette.Paper,
    hairline = KobePalette.Hairline,
    inkMuted = KobePalette.Ink400,
    inkFaint = KobePalette.Ink300,
    surfaceMuted = KobePalette.SurfaceMuted,
    brandStart = KobePalette.Red500,
    brandMid = KobePalette.Red600,
    brandEnd = KobePalette.Red800,
    amber = KobePalette.Amber,
    teal = KobePalette.Teal,
    indigo = KobePalette.Indigo,
    violet = KobePalette.Violet,
    emerald = KobePalette.Emerald,
    slate = KobePalette.Slate,
    detect = KobePalette.DetectGreen,
    scrim = KobePalette.Scrim,
    isDark = false,
)

internal val DarkExtraColors = KobeExtraColors(
    paper = KobePalette.NightPaper,
    hairline = KobePalette.NightHairline,
    inkMuted = KobePalette.NightInkMuted,
    inkFaint = Color(0xFF7C7472),
    surfaceMuted = KobePalette.NightSurfaceMuted,
    brandStart = KobePalette.Red400,
    brandMid = KobePalette.Red500,
    brandEnd = KobePalette.Red700,
    amber = Color(0xFFF5B547),
    teal = Color(0xFF2FBFAE),
    indigo = Color(0xFF6C8BFF),
    violet = Color(0xFF9B79FF),
    emerald = Color(0xFF35B587),
    slate = Color(0xFF8D96A5),
    detect = KobePalette.DetectGreen,
    scrim = KobePalette.Scrim,
    isDark = true,
)

internal val LocalKobeExtraColors = staticCompositionLocalOf { LightExtraColors }
