package org.samesame.notes.feature.lock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.PathEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity

/** True while a lock page plays its close animation: it ignores input and Back (the result was already delivered). */
internal val LocalLockFlowLeaving = compositionLocalOf { false }

/**
 * Shows the lock pages ([content], e.g. [LockAuthFlow]) for [target] with the motion of the reference, which opens its
 * password page as a separate activity: the platform's default activity open / close animation (the same values as the
 * app's screen transitions: a 96 dp slide over 450 ms with `fast_out_extra_slow_in`, an 83 ms fade in after 50 ms on
 * open and an 83 ms fade out after 35 ms on close). Setting [target] to null plays the close animation over the last
 * target while the keyboard goes away, instead of the page vanishing in one frame.
 */
@Composable
fun <T : Any> LockFlowTransition(target: T?, content: @Composable (T) -> Unit) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (target != null) last[0] = target
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = target != null
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(target == null) {
        if (target == null) {
            keyboard?.hide()
            focus.clearFocus(force = true)
        }
    }
    if (visible.isIdle && !visible.currentState) {
        last[0] = null
        if (target == null) return
    }
    @Suppress("UNCHECKED_CAST")
    val shown = (target ?: last[0]) as T? ?: return
    val slide = with(LocalDensity.current) { SLIDE_DP.dp.roundToPx() }
    AnimatedVisibility(
        visibleState = visible,
        enter = enter(slide),
        exit = exit(slide),
    ) {
        val leaving = target == null
        CompositionLocalProvider(LocalLockFlowLeaving provides leaving) {
            Box(
                Modifier.fillMaxSize().then(
                    if (leaving) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
                    else Modifier,
                ),
            ) { content(shown) }
        }
    }
}

/** [LockFlowTransition] for a page shown while [visible] is true. */
@Composable
fun LockFlowTransition(visible: Boolean, content: @Composable () -> Unit) =
    LockFlowTransition(if (visible) Unit else null) { content() }

private const val SLIDE_MS = 450
private const val SLIDE_DP = 96

/** `@android:interpolator/fast_out_extra_slow_in` (two cubic segments). */
private val FastOutExtraSlowIn: Easing = PathEasing(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    },
)

private fun enter(slidePx: Int): EnterTransition =
    slideInHorizontally(tween<IntOffset>(SLIDE_MS, easing = FastOutExtraSlowIn)) { slidePx } +
        fadeIn(tween(durationMillis = 83, delayMillis = 50, easing = LinearEasing))

private fun exit(slidePx: Int): ExitTransition =
    slideOutHorizontally(tween<IntOffset>(SLIDE_MS, easing = FastOutExtraSlowIn)) { slidePx } +
        fadeOut(tween(durationMillis = 83, delayMillis = 35, easing = LinearEasing))
