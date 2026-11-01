package org.samesame.notes.core.designsystem.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import kotlin.math.abs

/** State of [AppPushDrawer]: [fraction] is 0 (closed) to 1 (open). */
@Stable
class PushDrawerState {
    internal val fraction = Animatable(0f)

    /** True when closed or closing. */
    val isClosed: Boolean get() = fraction.targetValue == 0f

    suspend fun open() = settle(1f)
    suspend fun close() = settle(0f)

    internal suspend fun settle(target: Float) {
        val remaining = abs(target - fraction.value)
        if (remaining == 0f) return
        fraction.animateTo(target, tween(SameSameMotion.drawerSettleMs(remaining), easing = SameSameMotion.QuinticOut))
    }
}

@Composable
fun rememberPushDrawerState(): PushDrawerState = remember { PushDrawerState() }

/** Settle rule shared by the edge drag and the whole-content swipe: a fling wins, otherwise the halfway point. */
internal object DrawerSwipe {
    const val FLING_VELOCITY = 700f
    const val COMMIT_FRACTION = 0.5f

    fun shouldOpen(fraction: Float, velocityX: Float): Boolean = when {
        velocityX > FLING_VELOCITY -> true
        velocityX < -FLING_VELOCITY -> false
        else -> fraction > COMMIT_FRACTION
    }
}

/**
 * Drawer that pushes the screen: the content slides right by the drawer width while the drawer slides in from the
 * start edge, and a black scrim fades in over the content. Opening and closing settle in
 * `(remaining + 1) * 256` ms with a quintic ease-out; a drag from the start edge opens it and a drag or tap closes it.
 * Everything animates on graphics layers (no recomposition per frame).
 *
 * While closed, a horizontal swipe to the end anywhere over [content] also opens it: the list keeps vertical scrolling
 * and taps because the gesture only takes over after horizontal touch slop. [openSwipeEndExclusion] keeps that swipe
 * away from the end edge, where screens put edge controls such as a scroll bar.
 */
@Composable
fun AppPushDrawer(
    state: PushDrawerState,
    drawerWidth: Dp,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    openSwipeEndExclusion: Dp = 0.dp,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val widthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    val scope = rememberCoroutineScope()
    val shown by remember { derivedStateOf { state.fraction.value > 0f } }
    val open by remember { derivedStateOf { state.fraction.targetValue > 0f } }
    val drag = rememberDraggableState { delta ->
        scope.launch { state.fraction.snapTo((state.fraction.value + delta / widthPx).coerceIn(0f, 1f)) }
    }
    val dragModifier = Modifier.draggable(
        state = drag,
        orientation = Orientation.Horizontal,
        enabled = gesturesEnabled,
        onDragStopped = { velocity ->
            scope.launch { state.settle(if (DrawerSwipe.shouldOpen(state.fraction.value, velocity)) 1f else 0f) }
        },
    )
    // A horizontal drag anywhere over the content opens the drawer; vertical scrolling and taps are untouched because the
    // drag only starts after horizontal touch slop. Edge controls (the scroll bar strip) sit above the content and take their own touches.
    val contentSwipe = dragModifier
    BackHandler(enabled = open) { scope.launch { state.close() } }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(contentSwipe).graphicsLayer { translationX = state.fraction.value * widthPx }) { content() }
        if (shown) {
            Box(
                Modifier.fillMaxSize()
                    .drawBehind { drawRect(Color.Black.copy(alpha = SameSameMotion.DrawerScrimAlpha * state.fraction.value)) }
                    .then(dragModifier)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        scope.launch { state.close() }
                    },
            )
        } else if (gesturesEnabled) {
            // Closed: a thin strip at the start edge picks up the open drag.
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(20.dp).then(dragModifier))
        }
        // Always composed (off screen while closed) so opening doesn't pay for composing the drawer.
        Box(Modifier.fillMaxHeight().graphicsLayer { translationX = (state.fraction.value - 1f) * widthPx }.then(dragModifier)) {
            drawerContent()
        }
    }
}
