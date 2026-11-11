package org.samesame.notes.feature.composer.panels

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * Shared state of the font-size button's press-and-glide gesture. The popup reports its applied row, the length of
 * its size list and its vertical centre (the joystick's neutral line); the button reports the finger. The popup's
 * frame loop calls [frame] while [gliding], which integrates the rate-controlled highlight ([GlideJoystick]).
 */
class FontSizeHoldState {
    /** Flat row the popup shows as applied (the glide's anchor), -1 while the popup is not shown. */
    internal var selectedRow by mutableIntStateOf(-1)

    /** Rows in the popup's scrolling size list (0 while the popup is not shown). */
    internal var listCount by mutableIntStateOf(0)

    /** Root y of the popup's vertical centre, NaN while it is not laid out. Read only by [frame]. */
    internal var neutralY: Float = Float.NaN

    /**
     * Body and the plain "17" row apply the same style, so the last pick tells which of the two rows the next glide
     * starts on (one step from Body is then H3 or 6, from "17" it is 16 or 18).
     */
    internal var lastPickBody by mutableStateOf(false)
        private set

    fun notePick(pick: FontSizePick) {
        lastPickBody = pick == FontSizePick.Body
    }

    /** True while a glide drives the popup (the popup runs its frame loop then). */
    internal var gliding by mutableStateOf(false)
        private set
    private var suppressed by mutableStateOf(false)
    private var fingerY = 0f
    private var pressY = 0f
    private var neutralAtPress = false
    private var lastFrameNanos = -1L
    private var joystick = GlideJoystick(1f)

    /** Fractional flat row of the glide's highlight, -1 until the glide knows its anchor. The list follows it. */
    internal var position by mutableFloatStateOf(-1f)
        private set

    /** Flat row highlighted by the glide, or -1 (no glide, popup not shown yet, or finger back over the toolbar). */
    val highlighted: Int by derivedStateOf {
        val p = position
        val count = FontSizeRows.SIZE_START + listCount
        if (!gliding || suppressed || p < 0f || listCount <= 0) -1 else p.roundToInt().coerceIn(0, count - 1)
    }

    /**
     * A glide starts with the finger at root [y]; [pxPerDp] scales the joystick's dp constants. With [neutralAtPress]
     * (keyboard up: the toolbar floats mid-screen and there is room both ways) the neutral line is the press point
     * itself; otherwise it is the popup's vertical centre.
     */
    fun startGlide(y: Float, pxPerDp: Float, neutralAtPress: Boolean = false) {
        joystick = GlideJoystick(pxPerDp)
        pressY = y; this.neutralAtPress = neutralAtPress
        fingerY = y; suppressed = false; lastFrameNanos = -1L; position = -1f; gliding = true
    }

    /** The finger is at [y]; [suppressed] hides the highlight and stops the travel (finger back over the toolbar). */
    fun glideTo(y: Float, suppressed: Boolean = false) {
        fingerY = y; this.suppressed = suppressed
    }

    /** One display frame at [frameNanos] (the frame clock). The first frame after the anchor is known moves nothing. */
    fun frame(frameNanos: Long) {
        if (!gliding) return
        if (!joystick.started) {
            if (selectedRow < 0 || listCount <= 0) return
            joystick.start(selectedRow, FontSizeRows.SIZE_START + listCount)
            lastFrameNanos = frameNanos
            position = joystick.position
            return
        }
        val dt = if (lastFrameNanos < 0L) 0f else (frameNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameNanos
        joystick.advance(fingerY - if (neutralAtPress) pressY else neutralY, dt, held = suppressed)
        position = joystick.position
    }

    fun endGlide() {
        gliding = false; suppressed = false; position = -1f; joystick.reset()
    }

    /** The popup went away. */
    fun clear() {
        selectedRow = -1
        listCount = 0
        neutralY = Float.NaN
    }
}
