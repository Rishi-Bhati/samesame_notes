package org.samesame.notes.core.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/**
 * Shared motion tokens. Every duration and curve here was measured on the reference app (clips `anim/01..13`) and
 * cross-checked against the reference's animation resources for the exact numbers; the per-animation table is in
 * `docs/checkpoints/anim-parity.md`. Names are ours.
 */
object SameSameMotion {
    // ---- easings -------------------------------------------------------------------------------------------------

    /** Platform default for an animation without an explicit curve: cosine ease in/out. */
    val AccelerateDecelerate: Easing = Easing { t -> ((cos((t + 1.0) * PI) / 2.0) + 0.5).toFloat() }

    val SineInOut33: Easing = CubicBezierEasing(0.33f, 0f, 0.67f, 1f)
    val SineInOut70: Easing = CubicBezierEasing(0.33f, 0f, 0.3f, 1f)
    val SineInOut80: Easing = CubicBezierEasing(0.33f, 0f, 0.2f, 1f)
    val SineInOut90: Easing = CubicBezierEasing(0.33f, 0f, 0.1f, 1f)
    val SineOut80: Easing = CubicBezierEasing(0.17f, 0.17f, 0.2f, 1f)

    /** The popup-menu grow curve (fast start, long soft tail). */
    val MenuGrow: Easing = CubicBezierEasing(0.22f, 0.25f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Quintic ease-out, the curve of the drawer's settle (`(t-1)^5 + 1`). */
    val QuinticOut: Easing = Easing { t -> val x = t - 1f; x * x * x * x * x + 1f }

    // ---- durations (ms) ------------------------------------------------------------------------------------------

    /** Selection-mode bottom bar slide in and out. */
    const val BottomBarMs = 400

    /** FAB scale/fade when selection mode hides or shows it. */
    const val FabMs = 200
    const val FabStartScale = 0.5f

    /** Cross-fade between the text and pen toolbars. */
    const val ToolbarSwapMs = 150

    /** List item bounds change (sort change, removal, selection padding). */
    const val ListReflowMs = 500

    /** List title swap into and out of selection mode. */
    const val TitleSwapMs = 300

    /** Dialog window: slide 100 px up and fade in; out is the same slide with a faster fade. */
    const val DialogSlideMs = 300

    /** Drawer settle: `(remaining fraction + 1) * 256` ms (max 600), quintic ease-out. */
    fun drawerSettleMs(remaining: Float): Int = ((remaining.coerceIn(0f, 1f) + 1f) * 256f).toInt().coerceAtMost(600)

    /** Scrim of the drawer, black at this alpha when fully open. */
    const val DrawerScrimAlpha = 0x2E / 255f

    /**
     * Editor title header expand/collapse: one fraction over this many ms (scaled by the distance left when it is
     * reversed mid-way), [SineInOut70]. The bar title fades out in [TitleHeaderFadeOutMs] while the header title fades
     * in over [TitleHeaderFadeInMs] after [TitleHeaderFadeInDelayMs] (swapped when collapsing). The folder / info rows
     * fade in for [TitleHeaderInfoInMs] once the header is open and out in [TitleHeaderInfoOutMs] when it starts to
     * close, both [SineInOut33].
     */
    const val TitleHeaderMs = 333
    const val TitleHeaderFadeOutMs = 200
    const val TitleHeaderFadeInMs = 253
    const val TitleHeaderFadeInDelayMs = 80
    const val TitleHeaderInfoInMs = 200
    const val TitleHeaderInfoOutMs = 150

    /** Collapsing title: fade-out speed (alpha = 1 - fraction * k) and how much of the scroll the title follows. */
    const val TitleFadeK = 2.5f
    const val SubtitleFadeK = 4.5f
    const val TitleParallax = 0.63f
}

/** Scale-and-fade enter/exit of a popup, panel or floating button. All fields in ms. */
class PopupMotion(
    val startScale: Float,
    val enterScaleMs: Int,
    val enterScaleEasing: Easing,
    val enterAlphaMs: Int,
    val enterAlphaEasing: Easing,
    val exitScaleMs: Int,
    val exitScaleEasing: Easing,
    val exitAlphaMs: Int,
    val exitAlphaEasing: Easing,
    /** Scale pivot; the top end corner for menus. */
    val origin: TransformOrigin,
) {
    companion object {
        /** Toolbar overflow menu: 80% to 100% over 350 ms, alpha 200 ms; leaves in 200 ms. Pivot top end. */
        val Menu = PopupMotion(
            0.8f, 350, SameSameMotion.MenuGrow, 200, SameSameMotion.AccelerateDecelerate,
            200, SameSameMotion.SineInOut33, 200, SameSameMotion.AccelerateDecelerate, TransformOrigin(1f, 0f),
        )

        /** Dropdown list (sort): same grow, leaves a little slower (250 ms). */
        val DropdownList = PopupMotion(
            0.8f, 350, SameSameMotion.MenuGrow, 200, SameSameMotion.AccelerateDecelerate,
            250, SameSameMotion.SineInOut33, 250, SameSameMotion.AccelerateDecelerate, TransformOrigin(1f, 0f),
        )

        /** Editor menus: 90% to 100% over 220 ms, alpha 150 ms, decelerating both ways. Pivot top end. */
        val EditorMenu = PopupMotion(
            0.9f, 220, SameSameMotion.Decelerate, 150, SameSameMotion.Decelerate,
            220, SameSameMotion.Decelerate, 150, SameSameMotion.Decelerate, TransformOrigin(1f, 0f),
        )

        /** Pen / eraser / selection settings panels: 95% to 100% over 250 ms, alpha 150 ms. Pivot centre. */
        val PenPanel = PopupMotion(
            0.95f, 250, SameSameMotion.AccelerateDecelerate, 150, SameSameMotion.AccelerateDecelerate,
            250, SameSameMotion.AccelerateDecelerate, 150, SameSameMotion.AccelerateDecelerate, TransformOrigin.Center,
        )

        /** FAB: 50% to 100% with alpha, 200 ms both ways. */
        val Fab = PopupMotion(
            SameSameMotion.FabStartScale, SameSameMotion.FabMs, SameSameMotion.SineInOut80, SameSameMotion.FabMs, SameSameMotion.SineInOut80,
            SameSameMotion.FabMs, SameSameMotion.SineInOut80, SameSameMotion.FabMs, SameSameMotion.SineInOut80, TransformOrigin.Center,
        )

        /** No motion: appears and disappears instantly. */
        val None = PopupMotion(1f, 0, SameSameMotion.Decelerate, 0, SameSameMotion.Decelerate, 0, SameSameMotion.Decelerate, 0, SameSameMotion.Decelerate, TransformOrigin.Center)
    }
}

/**
 * Shows [content] with the [motion] enter and keeps it composed until the exit finished, then calls [onExited].
 * The animation runs on a graphics layer, so nothing recomposes or relayouts while it plays; the layout size is the
 * unscaled content size (a popup positions itself from that).
 */
@Composable
fun MotionPopup(
    visible: Boolean,
    motion: PopupMotion,
    modifier: Modifier = Modifier,
    origin: () -> TransformOrigin = { motion.origin },
    onExited: () -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    var composed by remember { mutableStateOf(visible) }
    val scale = remember { Animatable(motion.startScale) }
    val alpha = remember { Animatable(0f) }
    val exited = rememberUpdatedState(onExited)
    LaunchedEffect(visible) {
        if (visible) {
            composed = true
            coroutineScope {
                launch { scale.animateTo(1f, tween(motion.enterScaleMs, easing = motion.enterScaleEasing)) }
                launch { alpha.animateTo(1f, tween(motion.enterAlphaMs, easing = motion.enterAlphaEasing)) }
            }
        } else if (composed) {
            coroutineScope {
                launch { scale.animateTo(motion.startScale, tween(motion.exitScaleMs, easing = motion.exitScaleEasing)) }
                launch { alpha.animateTo(0f, tween(motion.exitAlphaMs, easing = motion.exitAlphaEasing)) }
            }
            composed = false
            exited.value()
        }
    }
    if (composed) {
        Box(
            modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
                transformOrigin = origin()
            },
            content = content,
        )
    }
}
