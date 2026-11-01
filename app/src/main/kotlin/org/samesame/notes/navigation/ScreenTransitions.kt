package org.samesame.notes.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation3.scene.Scene

/**
 * Screen-to-screen motion. The reference app opens the editor and settings as separate activities with the theme's
 * default window animations, so on the reference device (Pixel, Android 17) it plays the platform's default activity
 * open/close animations (clip `anim/03-open-close-note`, about 400 ms open / 370 ms close). These reproduce the
 * platform values (AOSP `activity_{open,close}_{enter,exit}.xml`): the incoming screen slides 96 dp with
 * `fast_out_extra_slow_in` over 450 ms; on open it fades in over 83 ms after 50 ms, on close the leaving screen fades
 * out over 83 ms after 35 ms. The covered screen only slides, it doesn't fade.
 */
internal object ScreenTransitions {
    private const val SLIDE_MS = 450
    private val SLIDE_DP = 96.dp

    /** `@android:interpolator/fast_out_extra_slow_in` (two-segment path, not expressible as one cubic). */
    private val FastOutExtraSlowIn: Easing = PathEasing(
        Path().apply {
            moveTo(0f, 0f)
            cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
            cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
        },
    )

    private fun slide() = tween<androidx.compose.ui.unit.IntOffset>(SLIDE_MS, easing = FastOutExtraSlowIn)

    fun <T : Any> open(density: Density): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
        val d = with(density) { SLIDE_DP.roundToPx() }
        val enter: EnterTransition = slideInHorizontally(slide()) { d } +
            fadeIn(tween(durationMillis = 83, delayMillis = 50, easing = LinearEasing))
        val exit: ExitTransition = slideOutHorizontally(slide()) { -d } +
            fadeOut(tween(SLIDE_MS), targetAlpha = 1f) // keeps the covered screen composed for the whole slide
        enter togetherWith exit
    }

    fun <T : Any> close(density: Density): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
        val d = with(density) { SLIDE_DP.roundToPx() }
        val enter: EnterTransition = slideInHorizontally(slide()) { -d }
        val exit: ExitTransition = slideOutHorizontally(slide()) { d } +
            fadeOut(tween(durationMillis = 83, delayMillis = 35, easing = LinearEasing))
        enter togetherWith exit
    }
}
