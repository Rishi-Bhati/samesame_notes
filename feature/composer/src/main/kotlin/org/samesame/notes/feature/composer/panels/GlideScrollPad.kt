package org.samesame.notes.feature.composer.panels

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

/**
 * Press-and-glide on the font-size button: once the finger glides into the popup, the whole popup window is a scroll
 * pad for the size list. The finger's height in the window maps linearly to the list's scroll position (top of the
 * window = list start, bottom = list end), so a fast slide travels fast. Slow, small moves get fine control: only part
 * of the absolute mapping is applied below [FAST_DP_PER_S] (none below [SLOW_DP_PER_S]), the rest is kept as an offset
 * so the highlight steps row by row; the offset fades out near the window's top and bottom edges ([EDGE_FADE]) so the
 * ends always line up. The list itself animates toward the target at a fixed rate per frame ([smoothStep]), so nothing
 * jumps. All functions are pure (px in one coordinate space).
 */
internal object GlideScrollPad {
    /** Below this finger speed the list does not follow (the highlight moves row by row). */
    const val SLOW_DP_PER_S = 120f
    /** From this finger speed on the list follows the absolute mapping fully. */
    const val FAST_DP_PER_S = 600f
    /** Fraction of the window height at the top and bottom over which the fine-control offset fades to nothing. */
    const val EDGE_FADE = 0.1f
    /** Time constant of the scroll animation toward the target. */
    const val SMOOTHING_MS = 45f

    /** Scroll offset for a finger at [y] with the window spanning [windowTop]..[windowBottom]: linear, clamped. */
    fun absoluteTarget(y: Float, windowTop: Float, windowBottom: Float, maxScroll: Float): Float {
        val h = windowBottom - windowTop
        if (h <= 0f || maxScroll <= 0f) return 0f
        return ((y - windowTop) / h).coerceIn(0f, 1f) * maxScroll
    }

    /**
     * Index within the scrolling list of the row under [y] (rows of [rowHeight] stacked from the content top, the
     * viewport spanning [listTop]..[listBottom] and scrolled by [scroll]), or -1 outside the viewport / past [count].
     */
    fun listIndexAt(y: Float, listTop: Float, listBottom: Float, scroll: Float, rowHeight: Float, count: Int): Int {
        if (rowHeight <= 0f || y < listTop || y >= listBottom) return -1
        val i = floor((y - listTop + scroll) / rowHeight).toInt()
        return if (i in 0 until count) i else -1
    }

    /** Share (0..1) of the absolute mapping applied for a finger moving at [speed] px/s. */
    fun absoluteShare(speed: Float, slow: Float, fast: Float): Float =
        if (fast <= slow) 1f else ((speed - slow) / (fast - slow)).coerceIn(0f, 1f)

    /** 0 at the window's top/bottom edge, 1 from [EDGE_FADE] of its height inward. */
    fun edgeFactor(y: Float, windowTop: Float, windowBottom: Float): Float {
        val zone = (windowBottom - windowTop) * EDGE_FADE
        if (zone <= 0f) return 0f
        return (minOf(y - windowTop, windowBottom - y) / zone).coerceIn(0f, 1f)
    }

    /** The scroll value one frame ([dtMs]) later, moving from [current] toward [target]; snaps when within half a px. */
    fun smoothStep(current: Float, target: Float, dtMs: Float): Float {
        val d = target - current
        if (abs(d) < 0.5f) return target
        val k = 1f - exp(-dtMs.coerceAtLeast(0f) / SMOOTHING_MS)
        return current + d * k
    }
}

/**
 * The stateful part of [GlideScrollPad]: the fine-control offset and the finger speed. Feed it the finger once per
 * frame while it is inside the popup; [reset] when the glide ends or the finger leaves.
 */
internal class GlideScrollPadTracker(private val slowPxPerS: Float, private val fastPxPerS: Float) {
    private var active = false
    private var offset = 0f
    private var lastY = 0f
    private var lastMs = 0L
    private var speed = 0f

    fun reset() {
        active = false; offset = 0f; speed = 0f
    }

    /**
     * Scroll target for the finger at [y] at [nowMs]. The first call after [reset] starts from [currentScroll] (the list
     * doesn't jump when the finger enters the middle of the window).
     */
    fun target(y: Float, nowMs: Long, windowTop: Float, windowBottom: Float, maxScroll: Float, currentScroll: Float): Float {
        val mapped = GlideScrollPad.absoluteTarget(y, windowTop, windowBottom, maxScroll)
        if (!active) {
            active = true
            offset = currentScroll - mapped
            lastY = y; lastMs = nowMs; speed = 0f
        } else {
            val dt = (nowMs - lastMs).coerceAtLeast(1L)
            val instant = abs(y - lastY) * 1000f / dt
            speed = speed * 0.5f + instant * 0.5f
            val prevAbs = GlideScrollPad.absoluteTarget(lastY, windowTop, windowBottom, maxScroll)
            val share = GlideScrollPad.absoluteShare(speed, slowPxPerS, fastPxPerS)
            offset -= (mapped - prevAbs) * (1f - share)
            lastY = y; lastMs = nowMs
        }
        offset = offset.coerceIn(-mapped, maxOf(-mapped, maxScroll - mapped))
        val faded = offset * GlideScrollPad.edgeFactor(y, windowTop, windowBottom)
        return (mapped + faded).coerceIn(0f, maxOf(0f, maxScroll))
    }
}
