package org.samesame.notes.core.designsystem.component

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * Pure geometry of a vertical scroll bar, kept out of the composable so the proportions can be unit-tested.
 * All lengths are in the same unit (px in the UI, anything in tests).
 */
object ScrollBarGeometry {
    /** Visible share of the content, clamped to (0, 1]; content that fits the viewport gives 1. */
    fun thumbFraction(contentLength: Float, viewportLength: Float): Float =
        if (contentLength <= 0f || viewportLength <= 0f) 1f
        else (viewportLength / contentLength).coerceIn(0f, 1f)

    fun thumbLength(trackLength: Float, fraction: Float, minimum: Float): Float =
        (trackLength * fraction).coerceIn(minimum.coerceAtMost(trackLength), trackLength)

    /** Top of the thumb for [scrollFraction] in 0..1 given the travel left after the thumb itself. */
    fun thumbOffset(trackLength: Float, thumbLength: Float, scrollFraction: Float): Float {
        val travel = (trackLength - thumbLength).coerceAtLeast(0f)
        return travel * scrollFraction.coerceIn(0f, 1f)
    }

    /** Scroll fraction whose thumb centre lands under [pointerY]. */
    fun scrollFractionFor(trackLength: Float, thumbLength: Float, pointerY: Float): Float {
        val travel = (trackLength - thumbLength).coerceAtLeast(0f)
        if (travel <= 0f) return 0f
        return ((pointerY - thumbLength / 2f) / travel).coerceIn(0f, 1f)
    }

    /**
     * Relative drag of a thumb (the editor's fast scroll bar): the thumb that started at [startOffset] follows the finger by
     * [delta]; returns the scroll fraction. No jump when the drag starts off-centre.
     */
    fun dragFraction(trackLength: Float, thumbLength: Float, startOffset: Float, delta: Float): Float {
        val travel = (trackLength - thumbLength).coerceAtLeast(0f)
        if (travel <= 0f) return 0f
        return ((startOffset + delta) / travel).coerceIn(0f, 1f)
    }

    /** The bar is only useful when the content is longer than the viewport. */
    fun isNeeded(contentLength: Float, viewportLength: Float): Boolean = contentLength > viewportLength

    /** The scroll fraction for a document-space scroll offset. */
    fun scrollFraction(scroll: Float, contentLength: Float, viewportLength: Float): Float {
        val max = (contentLength - viewportLength).coerceAtLeast(0f)
        return if (max <= 0f) 0f else (scroll / max).coerceIn(0f, 1f)
    }
}

/**
 * Hide timer of a scroll bar: [poke] pushes the deadline back, [run] is ONE loop for the bar's lifetime that calls
 * `onElapsed` once the deadline passes. A scroll frame therefore only moves a Long; nothing is cancelled and relaunched
 * per frame. Main thread only ([poke] / [cancel] and [run] on the same dispatcher).
 */
class ScrollBarHideTimer(
    private val delayMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var deadline = NONE
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** True while a hide is pending. */
    val pending: Boolean get() = deadline != NONE

    /** (Re)starts the countdown from now. */
    fun poke() {
        deadline = now() + delayMs
        wake.trySend(Unit)
    }

    /** No hide until the next [poke] (e.g. while the thumb is held). */
    fun cancel() {
        deadline = NONE
    }

    suspend fun run(onElapsed: () -> Unit): Nothing {
        while (true) {
            if (deadline == NONE) {
                wake.receive()
                continue
            }
            val wait = deadline - now()
            if (wait > 0) {
                delay(wait) // the deadline may have moved meanwhile: check again
                continue
            }
            deadline = NONE
            onElapsed()
        }
    }

    private companion object {
        const val NONE = Long.MAX_VALUE
    }
}
