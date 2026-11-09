package org.samesame.notes.feature.composer.panels

/**
 * Decisions of the font-size button's gesture (owner-sanctioned press-and-glide picker), kept pure so they are
 * JVM-testable. The button's pointer input feeds them; the pointer that pressed the button keeps driving the popup
 * until it lifts (Compose keeps delivering a pointer to the node it went down on, even outside its bounds).
 */
internal object FontSizeHoldGesture {

    /** How a press on the button starts. */
    enum class Start {
        /** Lifted before the long-press timeout without moving past the touch slop: toggle the popup (as before). */
        Tap,

        /** Held to the long-press timeout, or moved past the touch slop while still down: open the popup and glide. */
        Glide,

        /** The pointer was cancelled (another node took it, or the system cancelled the stream): do nothing. */
        Cancelled,
    }

    /** What lifting the finger does once the press became a glide. */
    enum class Release {
        /** A row is highlighted: apply it and close the popup. */
        Apply,

        /** Released without choosing (still on the button, or between rows inside the popup): leave the popup open. */
        KeepOpen,

        /** Released outside the popup after gliding away from the button: close the popup, nothing applied. */
        Cancel,
    }

    /**
     * One step of the start phase: [pressed] is false once the finger lifted, [distance] is how far it moved from the
     * press point (px), [timedOut] is true when the long-press timeout ran out. Returns null while undecided.
     */
    fun start(pressed: Boolean, distance: Float, touchSlop: Float, timedOut: Boolean): Start? = when {
        !pressed -> Start.Tap
        distance > touchSlop -> Start.Glide
        timedOut -> Start.Glide
        else -> null
    }

    /**
     * Lifting during a glide: [highlighted] is the flat row under the finger (-1 for none), [insidePopup] whether the
     * finger is over the popup now, [enteredPopup] whether it was over the popup at any time in this glide, and
     * [overButton] whether it is over the font-size button.
     */
    fun release(highlighted: Int, insidePopup: Boolean, enteredPopup: Boolean, overButton: Boolean): Release = when {
        highlighted >= 0 -> Release.Apply
        insidePopup -> Release.KeepOpen
        !enteredPopup && overButton -> Release.KeepOpen
        else -> Release.Cancel
    }
}
