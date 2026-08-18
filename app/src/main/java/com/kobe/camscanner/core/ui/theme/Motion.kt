package com.kobe.camscanner.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * One motion vocabulary for the whole app.
 *
 * Everything the finger drives is a spring (it should track and settle, never "play"). Everything
 * the app drives on its own is a short eased tween. Nothing runs longer than 320 ms — the scanner
 * has to feel instant (SDS 45).
 */
object KobeMotion {
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val Fast = 140
    const val Normal = 220
    const val Slow = 320

    fun <T> quick(): FiniteAnimationSpec<T> = tween(Fast, easing = Standard)
    fun <T> normal(): FiniteAnimationSpec<T> = tween(Normal, easing = Standard)
    fun <T> enter(): FiniteAnimationSpec<T> = tween(Slow, easing = Decelerate)

    fun <T> gentleSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    fun <T> snappySpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
}
