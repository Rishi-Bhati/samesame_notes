package org.samesame.notes.feature.composer.panels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/**
 * Shared state of the font-size button's press-and-glide gesture. The button's pointer input starts the glide with the
 * finger's position and the screen limits ([startGlide]), feeds the finger ([glideTo]) and ends it ([endGlide]); the
 * glide popup reads the placement, runs the frame loop ([frame]) and reports its bounds ([popupBounds]).
 *
 * Direct pointing: the highlighted row is the row under the finger ([GlidePointer]); the popup is placed so the current
 * row starts under the finger ([GlideLayout]).
 */
class FontSizeHoldState {

    /**
     * Body and the plain "17" row apply the same style, so the last pick tells which of the two rows the next glide
     * starts on (one step from Body is then H3 or 6, from "17" it is 16 or 18).
     */
    internal var lastPickBody by mutableStateOf(false)
        private set

    fun notePick(pick: FontSizePick) {
        lastPickBody = pick == FontSizePick.Body
    }

    /** True while a glide drives the popup (the glide popup is shown and runs its frame loop then). */
    internal var gliding by mutableStateOf(false)
        private set

    /** Sizes of the list this glide runs over (set at [startGlide]). */
    internal var sizes: List<Int> = emptyList()
        private set

    /** Where the glide popup goes (set at [startGlide]). */
    internal var placement: GlideLayout.Placement? by mutableStateOf(null)
        private set

    /** First visible row, fractional while the list auto-scrolls (the popup translates its rows by it). */
    internal var scroll by mutableFloatStateOf(0f)
        private set

    /** Row highlighted by the glide (whole list), or -1 (no glide, or the finger is far off the popup sideways). */
    var highlighted by mutableIntStateOf(-1)
        private set

    /** Root y of the highlighted row's centre (for the sample chip), NaN when nothing is highlighted. */
    internal var highlightedY by mutableFloatStateOf(Float.NaN)
        private set

    /** Bounds of the glide popup in root coordinates, reported by the popup once laid out. */
    internal var popupBounds: Rect? = null

    /**
     * Called synchronously whenever [highlighted] changes during a glide, with the row's pick (null = nothing
     * highlighted): the live preview in the note. [onGlideEnd] runs synchronously when the glide ends, before the
     * release applies a pick, so a preview can restore the text first.
     */
    var onHighlight: ((FontSizePick?) -> Unit)? = null
    var onGlideEnd: (() -> Unit)? = null

    private var pointer: GlidePointer? = null
    private var lastFrameNanos = -1L

    /** The pick of the highlighted row, or null. */
    val highlightedPick: FontSizePick? get() = FontSizeRows.pick(highlighted, sizes)

    /**
     * A glide starts with the finger at root [fingerY] on the current row [anchor] of the list of [sizes]; the popup
     * may use the screen from [minTop] down to [maxBottom] (root px). [rowPx] is the glide row height, [topPad] and
     * [bottomPad] the popup's paddings, [pxPerDp] scales the pointer's dp constants.
     */
    fun startGlide(
        sizes: List<Int>,
        anchor: Int,
        fingerY: Float,
        minTop: Float,
        maxBottom: Float,
        rowPx: Float,
        topPad: Float,
        bottomPad: Float,
        pxPerDp: Float,
    ) {
        this.sizes = sizes
        val count = FontSizeRows.count(sizes)
        val p = GlideLayout.place(count, anchor, rowPx, topPad, bottomPad, fingerY, minTop, maxBottom)
        placement = p
        val ptr = GlidePointer(count, p.visible, p.scroll, rowPx, p.listTop(topPad), anchor, fingerY, pxPerDp)
        pointer = ptr
        scroll = ptr.scroll
        lastFrameNanos = -1L
        popupBounds = null
        gliding = true
        publish(ptr)
    }

    /** The finger is at root [y]; [suppressed] hides the highlight (finger far off the popup sideways). */
    fun glideTo(y: Float, suppressed: Boolean = false) {
        val ptr = pointer ?: return
        ptr.pointer(y, suppressed)
        publish(ptr)
    }

    /** One display frame at [frameNanos] (the frame clock): integrates the edge-band auto-scroll. */
    fun frame(frameNanos: Long) {
        val ptr = pointer ?: return
        val dt = if (lastFrameNanos < 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameNanos
        if (ptr.advance(dt)) {
            scroll = ptr.scroll
            publish(ptr)
        }
    }

    /** True once the finger moved from the press point (the release then applies, a stationary hold keeps the popup open). */
    val moved: Boolean get() = pointer?.armed == true

    fun endGlide() {
        if (!gliding) return
        gliding = false
        pointer = null
        placement = null
        if (highlighted != -1) highlighted = -1
        highlightedY = Float.NaN
        onGlideEnd?.invoke()
    }

    private fun publish(ptr: GlidePointer) {
        val h = ptr.highlighted
        highlightedY = if (h < 0) Float.NaN else ptr.rowCentreY(h)
        if (h != highlighted) {
            highlighted = h
            onHighlight?.invoke(FontSizeRows.pick(h, sizes))
        }
    }
}
