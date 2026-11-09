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
        /** Lifted away from the toolbar with a row highlighted: apply it and close the popup. */
        Apply,

        /** Lifted without the finger ever leaving the button (a hold without moving): leave the popup open. */
        KeepOpen,

        /** Glided away and lifted back over the toolbar (a change of mind), or nothing to apply: close, unchanged. */
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
     * Lifting during a glide: [highlighted] is the glide's flat row (-1 for none), [leftButton] whether the finger
     * left the font-size button at any time in this glide, and [overToolbar] whether it is over the toolbar strip now.
     */
    fun release(highlighted: Int, leftButton: Boolean, overToolbar: Boolean): Release = when {
        !leftButton -> Release.KeepOpen
        overToolbar -> Release.Cancel
        highlighted >= 0 -> Release.Apply
        else -> Release.Cancel
    }
}
