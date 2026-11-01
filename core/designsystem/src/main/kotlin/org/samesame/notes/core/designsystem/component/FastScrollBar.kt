package org.samesame.notes.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import org.samesame.notes.core.designsystem.theme.fixedSp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Track geometry of the [FastScrollBar] (pure, unit-tested). Lengths are px along the bar's axis.
 */
object FastScrollBarGeometry {
    /** px the thumb can travel along a [trackLength] px track, after [endMargin] at its far end. */
    fun travel(trackLength: Float, thumbLength: Float, startMargin: Float, endMargin: Float): Float =
        (trackLength - startMargin - endMargin - thumbLength).coerceAtLeast(0f)

    /** Thumb position along the track (from the start margin) for a 0..1 [fraction]. */
    fun along(travel: Float, fraction: Float): Float = travel * fraction.coerceIn(0f, 1f)

    /**
     * True when a press at [pointer] (px along the track, measured like [thumbStart]) is on the thumb: its touch zone is
     * centred on the thumb and at least [minTouch] long (a 40 dp thumb gets the 48 dp minimum target).
     */
    fun isOnThumb(pointer: Float, thumbStart: Float, thumbLength: Float, minTouch: Float): Boolean {
        val zone = max(thumbLength, minTouch)
        val centre = thumbStart + thumbLength / 2f
        return pointer >= centre - zone / 2f && pointer <= centre + zone / 2f
    }

    /**
     * The edge strip takes a press only once it moves past [slop] mostly along the bar: a tap or a sideways swipe stays
     * with the content under it (cards, the drawer swipe).
     */
    fun stripTakesOver(dx: Float, dy: Float, slop: Float, horizontalBar: Boolean = false): Boolean {
        val along = if (horizontalBar) abs(dx) else abs(dy)
        val across = if (horizontalBar) abs(dy) else abs(dx)
        return along > slop && along > across
    }
}

/**
 * The one fast scroll bar of the app, shared by the note editor and the home lists so both look and move the same (the
 * reference editor's fast scroll bar): a fixed-size thumb (19 x 40 dp vertical, 36 x 15 dp horizontal, not proportional
 * to the content) with an up and a down chevron (our own glyph); idle tint #E64D4D4D, [accentColor] while held. It fades
 * in / out over 333 ms (SineInOut70) and hides 2.5 s after the last scroll or touch. Vertical: 10 dp from the end edge,
 * travelling from [topMargin] to 20 dp above the bottom. Horizontal: 16 dp side margins, 20 dp above the bottom.
 * Dragging is relative: the thumb follows the finger from where it was, with no jump.
 *
 * Touch: the thumb itself; with [edgeStrip] (vertical home lists) also a 48 dp strip along the end edge while the bar is
 * shown. The strip shares its touches with the content below and only takes a press once it moves along the bar past
 * the touch slop, so taps on the cards under it and sideways swipes still work. No full-size node takes pointer input.
 *
 * While dragging, [label] (if any) shows in a bubble before the thumb (vertical) or above it (horizontal): 15 sp #FAFAFA
 * on the accent at 90 %, 15 dp corners, min 56 x 30 dp, 16 dp side padding, 26 / 20 dp from the thumb.
 *
 * Position and need are lambdas read in the layout / draw phases and in effects only, so scrolling recomposes nothing.
 *
 * @param scrollFraction 0..1 position of the viewport in the content.
 * @param onScrub the new scroll fraction while the thumb is dragged.
 * @param needed false hides the bar and its touch strip (content that fits).
 * @param showOnStart show the bar when it first appears (the editor); the home lists only show it on a scroll.
 */
@Composable
fun FastScrollBar(
    scrollFraction: () -> Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
    needed: () -> Boolean = { true },
    edgeStrip: Boolean = false,
    showOnStart: Boolean = true,
    topMargin: Dp = 0.dp,
    label: (() -> String)? = null,
    accentColor: Color = DEFAULT_ACCENT,
    idleColor: Color = IDLE,
) {
    val fraction by rememberUpdatedState(scrollFraction)
    val scrub by rememberUpdatedState(onScrub)
    val neededNow by rememberUpdatedState(needed)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val alpha = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    var touchTick by remember { mutableIntStateOf(0) }
    // The thumb position while dragged (px along the travel), so it follows the finger exactly.
    var dragAlong by remember { mutableFloatStateOf(-1f) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // Where the thumb was last placed along its axis (px from the area start): thumb-local pointer positions add to it.
    val placedHolder = remember { FloatArray(1) }
    val thumbW = if (horizontal) 36.dp else 19.dp
    val thumbH = if (horizontal) 15.dp else 40.dp

    val timer = remember { ScrollBarHideTimer(SHOW_MS) }
    LaunchedEffect(timer) {
        // Every scroll (or the end of a drag) shows the bar and pushes the hide deadline back. One timer loop for the
        // bar's lifetime: a scroll frame only moves the deadline (no coroutine cancelled and restarted per frame).
        launch {
            var first = true
            snapshotFlow { fraction() to touchTick }.collect {
                val skip = first && !showOnStart
                first = false
                if (skip) return@collect
                if (alpha.targetValue < 1f) launch { alpha.animateTo(1f, tween(FADE_MS, easing = SameSameMotion.SineInOut70)) }
                if (dragging) timer.cancel() else timer.poke()
            }
        }
        timer.run { launch { alpha.animateTo(0f, tween(FADE_MS, easing = SameSameMotion.SineInOut70)) } }
    }

    fun px(v: Dp) = with(density) { v.toPx() }
    fun startMargin() = if (horizontal) px(SIDE_MARGIN) else px(topMargin)
    fun travel(): Float = if (horizontal) {
        FastScrollBarGeometry.travel(area.width.toFloat(), px(thumbW), px(SIDE_MARGIN), px(SIDE_MARGIN))
    } else {
        FastScrollBarGeometry.travel(area.height.toFloat(), px(thumbH), px(topMargin), px(BOTTOM_MARGIN))
    }
    fun thumbAlong(): Float = if (dragging && dragAlong >= 0f) dragAlong else FastScrollBarGeometry.along(travel(), fraction())
    fun thumbTopLeft(): IntOffset {
        val along = startMargin() + thumbAlong()
        return if (horizontal) {
            IntOffset(along.roundToInt(), (area.height - px(BOTTOM_MARGIN) - px(thumbH)).roundToInt())
        } else {
            val x = if (rtl) px(END_MARGIN) else area.width - px(END_MARGIN) - px(thumbW)
            IntOffset(x.roundToInt(), along.roundToInt())
        }
    }

    /** One relative drag: the thumb starts where it is; [fingerStart] / [position] are px along the bar's axis. */
    suspend fun AwaitPointerEventScope.relativeDrag(
        pointerId: androidx.compose.ui.input.pointer.PointerId,
        fingerStart: Float,
        pass: PointerEventPass,
        position: (PointerEvent) -> Float?,
    ) {
        val track = travel()
        val start = FastScrollBarGeometry.along(track, fraction())
        dragAlong = start
        dragging = true
        try {
            while (true) {
                val ev = awaitPointerEvent(pass)
                val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                if (!ch.pressed) {
                    ch.consume()
                    break
                }
                ch.consume()
                val at = position(ev) ?: break
                val delta = at - fingerStart
                dragAlong = (start + delta).coerceIn(0f, track)
                scrub(ScrollBarGeometry.dragFraction(track, 0f, start, delta))
            }
        } finally {
            dragging = false
            dragAlong = -1f
            touchTick++
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { area = it }) {
        if (edgeStrip && !horizontal) {
            // The edge strip (and the thumb within it): shares touches with the content below; see the KDoc.
            Box(
                Modifier
                    .align(if (rtl) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(STRIP)
                    .then(
                        SharedPointerInputElement(rtl) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                // A hidden (or unneeded) bar is not a target: the press belongs to the content.
                                if (down.isConsumed || !neededNow() || alpha.value < 0.5f) return@awaitEachGesture
                                val thumbStart = startMargin() + thumbAlong()
                                var fingerStart = down.position.y
                                if (FastScrollBarGeometry.isOnThumb(down.position.y, thumbStart, px(thumbH), px(STRIP))) {
                                    down.consume()
                                } else {
                                    val slop = viewConfiguration.touchSlop
                                    while (true) {
                                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                                        if (ev.changes.size > 1) return@awaitEachGesture
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                        if (!ch.pressed || ch.isConsumed) return@awaitEachGesture
                                        val d = ch.position - down.position
                                        if (FastScrollBarGeometry.stripTakesOver(d.x, d.y, slop)) {
                                            ch.consume()
                                            // Relative from here: the thumb does not jump by the slop distance.
                                            fingerStart = ch.position.y
                                            break
                                        }
                                        if (d.getDistance() > slop) return@awaitEachGesture // sideways: not ours
                                    }
                                }
                                relativeDrag(down.id, fingerStart, PointerEventPass.Initial) { ev ->
                                    ev.changes.firstOrNull { it.id == down.id }?.position?.y
                                }
                            }
                        },
                    ),
            )
        }
        Canvas(
            Modifier
                .offset { thumbTopLeft() }
                .size(thumbW, thumbH)
                .graphicsLayer { this.alpha = if (dragging) 1f else alpha.value }
                .onPlaced { placedHolder[0] = if (horizontal) it.positionInParent().x else it.positionInParent().y }
                .then(
                    if (edgeStrip && !horizontal) Modifier
                    else Modifier.pointerInput(horizontal) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (alpha.value < 0.5f || !neededNow()) return@awaitEachGesture // a hidden bar is not a target
                            down.consume()
                            // Finger position along the axis = where the thumb was placed + the local touch point.
                            fun along(local: Offset) = placedHolder[0] + if (horizontal) local.x else local.y
                            relativeDrag(down.id, along(down.position), PointerEventPass.Main) { ev ->
                                ev.changes.firstOrNull { it.id == down.id }?.position?.let(::along)
                            }
                        }
                    },
                ),
        ) {
            if (!neededNow()) return@Canvas
            val tint = if (dragging) accentColor else idleColor
            if (horizontal) {
                rotate(90f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                    // Draw the vertical glyph into the rotated box (swap the sides).
                    val w = size.height; val h = size.width
                    translate((size.width - w) / 2f, (size.height - h) / 2f) { drawPath(fastScrollThumbGlyph(w, h), tint) }
                }
            } else {
                drawPath(fastScrollThumbGlyph(size.width, size.height), tint)
            }
        }
        if (dragging && label != null) {
            var bubble by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .offset {
                        val t = thumbTopLeft()
                        if (horizontal) {
                            val x = t.x + px(thumbW) / 2f - bubble.width / 2f
                            IntOffset(
                                x.coerceIn(0f, (area.width - bubble.width).toFloat().coerceAtLeast(0f)).roundToInt(),
                                (t.y - px(HORIZONTAL_GAP) - bubble.height).roundToInt(),
                            )
                        } else {
                            val x = if (rtl) t.x + px(thumbW) + px(VERTICAL_GAP) else t.x - px(VERTICAL_GAP) - bubble.width
                            val y = (t.y + px(thumbH) / 2f - bubble.height / 2f)
                                .coerceIn(0f, (area.height - px(BOTTOM_MARGIN) - bubble.height).coerceAtLeast(0f))
                            IntOffset(x.roundToInt(), y.roundToInt())
                        }
                    }
                    .onSizeChanged { bubble = it }
                    .background(accentColor.copy(alpha = BUBBLE_ALPHA), RoundedCornerShape(15.dp))
                    .widthIn(min = 56.dp, max = 300.dp)
                    .heightIn(min = 30.dp)
                    .padding(horizontal = SIDE_MARGIN),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(), color = Color(0xFFFAFAFA), fontSize = fixedSp(15f), maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * `pointerInput` that also lets the siblings below it get the same touches (the edge strip lies over list cards: it
 * must not swallow their taps). Handler changes only with [key].
 */
private class SharedPointerInputElement(
    private val key: Any?,
    private val handler: PointerInputEventHandler,
) : ModifierNodeElement<SharedPointerInputNode>() {
    override fun create() = SharedPointerInputNode(handler)
    override fun update(node: SharedPointerInputNode) = node.update(handler)
    override fun equals(other: Any?): Boolean = other is SharedPointerInputElement && other.key == key
    override fun hashCode(): Int = key?.hashCode() ?: 0
}

private class SharedPointerInputNode(handler: PointerInputEventHandler) : DelegatingNode(), PointerInputModifierNode {
    private val input = delegate(SuspendingPointerInputModifierNode(handler))

    fun update(handler: PointerInputEventHandler) {
        input.pointerInputEventHandler = handler
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        input.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = input.onCancelPointerInput()

    override fun sharePointerInputWithSiblings(): Boolean = true
}

/** Our own thumb glyph: a stadium (0.79 of the box wide, 0.91 tall) with an up and a down chevron cut out (even-odd). */
internal fun fastScrollThumbGlyph(w: Float, h: Float): Path {
    val p = Path().apply { fillType = PathFillType.EvenOdd }
    val pw = w * 0.79f; val ph = h * 0.91f
    val l = (w - pw) / 2f; val t = (h - ph) / 2f
    val r = pw / 2f
    p.addRoundRect(RoundRect(l, t, l + pw, t + ph, r, r))
    val cx = w / 2f
    val arm = pw * 0.21f // half width of a chevron
    val rise = arm * 0.99f
    val th = pw * 0.065f // stroke thickness
    fun chevron(apexY: Float, up: Boolean) {
        val s = if (up) 1f else -1f
        p.moveTo(cx, apexY)
        p.lineTo(cx + arm, apexY + s * rise)
        p.lineTo(cx + arm - th, apexY + s * (rise + th))
        p.lineTo(cx, apexY + s * th * 1.4f)
        p.lineTo(cx - arm + th, apexY + s * (rise + th))
        p.lineTo(cx - arm, apexY + s * rise)
        p.close()
    }
    chevron(h * 0.29f, up = true)
    chevron(h * 0.71f, up = false)
    return p
}

private const val FADE_MS = 333
private const val SHOW_MS = 2_500L
private const val BUBBLE_ALPHA = 0xE6 / 255f
private val END_MARGIN = 10.dp
private val BOTTOM_MARGIN = 20.dp
private val SIDE_MARGIN = 16.dp
private val VERTICAL_GAP = 26.dp
private val HORIZONTAL_GAP = 20.dp
private val STRIP = 48.dp
private val IDLE = Color(0xE64D4D4D)
private val DEFAULT_ACCENT = Color(0xFFF46A4E)
