package com.kobe.camscanner.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Kobe rounds generously. Cards and sheets use large radii so the chrome reads soft against the
 * hard rectangles of the scanned pages themselves.
 */
val KobeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

object KobeRadius {
    val chip = RoundedCornerShape(percent = 50)
    val card = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val page = RoundedCornerShape(6.dp)
    val button = RoundedCornerShape(16.dp)
}
