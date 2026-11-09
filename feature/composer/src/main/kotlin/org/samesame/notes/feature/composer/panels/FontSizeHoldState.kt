package org.samesame.notes.feature.composer.panels

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared state of the font-size button's press-and-glide gesture. The popup reports its applied row and the length
 * of its size list; the button reports the glide (anchor y, finger y). The highlighted row is derived from those
 * alone ([GlideScrollPad.glideIndex]), so it is a pure function of the finger's displacement since the glide began.
 */
class FontSizeHoldState {
    /** Flat row the popup shows as applied (the glide's anchor), -1 while the popup is not shown. */
    internal var selectedRow by mutableIntStateOf(-1)

    /** Rows in the popup's scrolling size list (0 while the popup is not shown). */
    internal var listCount by mutableIntStateOf(0)

    /**
     * Body and the plain "17" row apply the same style, so the last pick tells which of the two rows the next glide
     * starts on (one step from Body is then H3 or 6, from "17" it is 16 or 18).
     */
    internal var lastPickBody by mutableStateOf(false)
        private set

    fun notePick(pick: FontSizePick) {
        lastPickBody = pick == FontSizePick.Body
    }

    private var gliding by mutableStateOf(false)
    private var anchorY by mutableFloatStateOf(0f)
    private var fingerY by mutableFloatStateOf(0f)
    private var stepPx by mutableFloatStateOf(1f)
    private var suppressed by mutableStateOf(false)

    /** Flat row highlighted by the glide, or -1 (no glide, popup not shown yet, or finger back over the toolbar). */
    val highlighted: Int by derivedStateOf {
        if (!gliding || suppressed) -1
        else GlideScrollPad.glideIndex(selectedRow, anchorY, fingerY, stepPx, FontSizeRows.SIZE_START + listCount)
    }

    /** A glide starts with the finger at [y]: the highlight sits on the applied row until the finger moves a step. */
    fun startGlide(y: Float, stepPx: Float) {
        anchorY = y; fingerY = y; this.stepPx = stepPx; suppressed = false; gliding = true
    }

    /** The finger is at [y]; [suppressed] hides the highlight (finger back over the toolbar: lifting there cancels). */
    fun glideTo(y: Float, suppressed: Boolean = false) {
        fingerY = y; this.suppressed = suppressed
    }

    fun endGlide() {
        gliding = false; suppressed = false
    }

    /** The popup went away. */
    fun clear() {
        selectedRow = -1
        listCount = 0
    }
}
