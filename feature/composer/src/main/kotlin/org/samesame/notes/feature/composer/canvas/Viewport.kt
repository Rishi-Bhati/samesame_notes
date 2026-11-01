package org.samesame.notes.feature.composer.canvas

import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.samesame.notes.core.model.Limits

/**
 * Scroll and zoom state of the canvas (docs 2.1).
 *
 * Zoom 1.0 is "fit width": the 1080 pu page width equals the viewport width. [scroll] is the document
 * point (pu) shown at the viewport's top-left, so zooming keeps the focus point stable and the stored
 * `ViewState` is independent of the device. On an axis where the content is smaller than the viewport it
 * is centred and [scroll] is ignored (that is why `#26` and `#61` differ in their margins).
 */
@Stable
class Viewport(initialScroll: Offset = Offset.Zero, initialZoom: Float = 1f) {
    var scroll: Offset by mutableStateOf(initialScroll)
        private set
    var zoom: Float by mutableFloatStateOf(initialZoom)
        private set
    var size: IntSize by mutableStateOf(IntSize.Zero)
        private set
    /** Content extent in pu, set from the [DocumentLayout]. */
    var content: Size by mutableStateOf(Size.Zero)
        private set

    val minZoom: Float get() = MIN_ZOOM
    val maxZoom: Float get() = Limits.MAX_ZOOM

    /** Pixels per page unit. */
    val scale: Float get() = if (size.width == 0) 1f else size.width / Limits.PAGE_WIDTH_PU * zoom

    /** View-space position (px) of document point (0,0), including the centring offset. */
    val origin: Offset get() = Offset(axisOrigin(size.width.toFloat(), content.width * scale, scroll.x * scale),
        axisOrigin(size.height.toFloat(), content.height * scale, scroll.y * scale))

    fun docToView(doc: Offset): Offset = origin + doc * scale
    fun viewToDoc(view: Offset): Offset = (view - origin) / scale

    /** The visible area in document space. */
    val visibleDoc: Rect get() {
        val tl = viewToDoc(Offset.Zero)
        return Rect(tl, Size(size.width / scale, size.height / scale))
    }

    fun updateSize(newSize: IntSize) { if (newSize != size) { size = newSize; clamp() } }

    fun updateContent(newContent: Size) { if (newContent != content) { content = newContent; clamp() } }

    fun scrollBy(deltaPx: Offset) { scroll = clampScroll(scroll - deltaPx / scale, zoom) }

    fun scrollTo(docTopLeft: Offset) { scroll = clampScroll(docTopLeft, zoom) }

    /** Zooms by [factor] (clamped to the limits) keeping the document point under [focusPx] fixed. */
    fun zoomBy(factor: Float, focusPx: Offset) {
        val docFocus = viewToDoc(focusPx)
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        val newScale = size.width / Limits.PAGE_WIDTH_PU * newZoom
        // Solve origin' + docFocus * newScale = focusPx for scroll, on axes that are not centred.
        val desired = Offset(docFocus.x - focusPx.x / newScale, docFocus.y - focusPx.y / newScale)
        zoom = newZoom
        scroll = clampScroll(desired, newZoom)
    }

    /** Combined pinch step: pan by [panPx] and zoom by [factor] around [focusPx] (current finger centroid). */
    fun transformBy(panPx: Offset, factor: Float, focusPx: Offset, allowZoom: Boolean = true) {
        if (allowZoom && factor != 1f) zoomBy(factor, focusPx)
        if (panPx != Offset.Zero) scrollBy(panPx)
    }

    fun restore(scroll: Offset, zoom: Float) {
        this.zoom = zoom.coerceIn(minZoom, maxZoom)
        this.scroll = clampScroll(scroll, this.zoom)
    }

    /**
     * Flings with [velocityPx] (px/s); stops at the bounds. Cancelling the coroutine stops it (touch down).
     * The same exponential decay curve as `exponentialDecay(FLING_FRICTION)` per axis, evaluated straight from the frame
     * clock with plain floats: no animation state or boxed values per frame.
     */
    suspend fun fling(velocityPx: Offset) {
        if (velocityPx.getDistance() < MIN_FLING_PX_PER_S) return
        val decay = FlingDecay(velocityPx.x, velocityPx.y, FLING_FRICTION)
        val durationScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        var lastX = 0f
        var lastY = 0f
        var start = Long.MIN_VALUE
        // One frame callback for the whole fling (not a new lambda per frame); true when the fling is over.
        val onFrame: (Long) -> Boolean = { now ->
            if (start == Long.MIN_VALUE) start = now
            val play = decay.playTime(now - start, durationScale)
            val x = decay.valueX(play)
            val y = decay.valueY(play)
            val dx = x - lastX
            val dy = y - lastY
            lastX = x
            lastY = y
            val before = scroll
            if (dx != 0f || dy != 0f) scrollBy(Offset(dx, dy))
            (scroll == before && (dx != 0f || dy != 0f)) || decay.isFinished(play)
        }
        while (!withFrameNanos(onFrame)) Unit
    }

    private fun clamp() { scroll = clampScroll(scroll, zoom) }

    private fun clampScroll(s: Offset, z: Float): Offset {
        if (size.width == 0) return s
        val sc = size.width / Limits.PAGE_WIDTH_PU * z
        return Offset(
            s.x.coerceIn(0f, maxOf(0f, content.width - size.width / sc)),
            s.y.coerceIn(0f, maxOf(0f, content.height - size.height / sc)),
        )
    }

    private fun axisOrigin(view: Float, contentPx: Float, scrollPx: Float) =
        if (contentPx <= view) (view - contentPx) / 2f else -scrollPx

    companion object {
        const val MIN_ZOOM = 1f
        private const val MIN_FLING_PX_PER_S = 200f
        private const val FLING_FRICTION = 1.6f
    }
}

/**
 * Exponential fling decay per axis ([FloatExponentialDecaySpec], the spec behind `exponentialDecay`): offsets from the
 * start for a play time. Pure, allocation-free per frame.
 */
internal class FlingDecay(private val vx: Float, private val vy: Float, friction: Float) {
    private val spec = FloatExponentialDecaySpec(friction)
    private val durationNanos = maxOf(spec.getDurationNanos(0f, vx), spec.getDurationNanos(0f, vy))

    /** Play time for an elapsed frame time, honouring the animator duration scale (0 = jump to the end). */
    fun playTime(elapsedNanos: Long, durationScale: Float): Long =
        if (durationScale <= 0f) durationNanos else (elapsedNanos / durationScale).toLong()

    fun valueX(playNanos: Long): Float = spec.getValueFromNanos(minOf(playNanos, durationNanos), 0f, vx)

    fun valueY(playNanos: Long): Float = spec.getValueFromNanos(minOf(playNanos, durationNanos), 0f, vy)

    fun isFinished(playNanos: Long): Boolean = playNanos >= durationNanos
}
