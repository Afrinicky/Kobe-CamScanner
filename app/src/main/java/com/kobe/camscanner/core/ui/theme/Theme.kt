package com.kobe.camscanner.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = KobePalette.Red600,
    onPrimary = KobePalette.White,
    primaryContainer = KobePalette.Red50,
    onPrimaryContainer = KobePalette.Red900,
    secondary = KobePalette.Ink800,
    onSecondary = KobePalette.White,
    secondaryContainer = KobePalette.SurfaceMuted,
    onSecondaryContainer = KobePalette.Ink,
    tertiary = KobePalette.Indigo,
    onTertiary = KobePalette.White,
    background = KobePalette.Paper,
    onBackground = KobePalette.Ink,
    surface = KobePalette.White,
    onSurface = KobePalette.Ink,
    surfaceVariant = KobePalette.SurfaceMuted,
    onSurfaceVariant = KobePalette.Ink400,
    surfaceContainerLowest = KobePalette.White,
    surfaceContainerLow = KobePalette.Paper,
    surfaceContainer = KobePalette.SurfaceMuted,
    surfaceContainerHigh = Color(0xFFEDE8E5),
    surfaceContainerHighest = Color(0xFFE7E1DE),
    outline = KobePalette.Ink200,
    outlineVariant = KobePalette.Hairline,
    error = Color(0xFFB3261E),
    onError = KobePalette.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    inverseSurface = KobePalette.Ink,
    inverseOnSurface = KobePalette.Paper,
    inversePrimary = KobePalette.Red400,
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = KobePalette.Red400,
    onPrimary = Color(0xFF3B0703),
    primaryContainer = Color(0xFF3A1512),
    onPrimaryContainer = KobePalette.Red100,
    secondary = Color(0xFFD9D0CE),
    onSecondary = KobePalette.Ink,
    secondaryContainer = KobePalette.NightSurfaceMuted,
    onSecondaryContainer = KobePalette.NightInk,
    tertiary = Color(0xFF6C8BFF),
    onTertiary = Color(0xFF0A1642),
    background = KobePalette.NightPaper,
    onBackground = KobePalette.NightInk,
    surface = KobePalette.NightSurface,
    onSurface = KobePalette.NightInk,
    surfaceVariant = KobePalette.NightSurfaceMuted,
    onSurfaceVariant = KobePalette.NightInkMuted,
    surfaceContainerLowest = Color(0xFF0A0909),
    surfaceContainerLow = KobePalette.NightSurface,
    surfaceContainer = KobePalette.NightSurfaceMuted,
    surfaceContainerHigh = Color(0xFF2A2526),
    surfaceContainerHighest = Color(0xFF332D2E),
    outline = Color(0xFF534C4D),
    outlineVariant = KobePalette.NightHairline,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = KobePalette.NightInk,
    inverseOnSurface = KobePalette.Ink,
    inversePrimary = KobePalette.Red600,
    scrim = Color(0xFF000000),
)

/**
 * Root theme.
 *
 * Dynamic colour is intentionally *not* used. Kobe's red is the product — letting the wallpaper
 * recolour it would cost the app its identity, and the neutral paper tones are tuned so scans look
 * correct rather than tinted.
 */
@Composable
fun KobeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val extra = if (darkTheme) DarkExtraColors else LightExtraColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalKobeExtraColors provides extra) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KobeTypography,
            shapes = KobeShapes,
            content = content,
        )
    }
}

/**
 * Full-bleed dark theme for the viewfinder and the image editors, regardless of system setting.
 * Editing surfaces are always dark so the page being worked on is the brightest thing on screen.
 */
@Composable
fun KobeCanvasTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKobeExtraColors provides DarkExtraColors) {
        MaterialTheme(
            colorScheme = DarkScheme.copy(
                background = KobePalette.Viewfinder,
                surface = KobePalette.Viewfinder,
            ),
            typography = KobeTypography,
            shapes = KobeShapes,
            content = content,
        )
    }
}

object KobeTheme {
    val extra: KobeExtraColors
        @Composable @ReadOnlyComposable get() = LocalKobeExtraColors.current

    val typography: Typography
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography
}
