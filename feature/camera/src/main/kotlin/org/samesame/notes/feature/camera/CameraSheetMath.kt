package org.samesame.notes.feature.camera

/**
 * Pure rules of the in-app camera sheet (unit-tested). Geometry follows the captured reference sheet (device-r19:
 * full width, top edge at 342.9 of 891.4 dp = the bottom 61.5 % of the screen, 18 dp top corners, no scrim, slide up /
 * down in about 300 ms, controls on one row whose centres sit 61 dp above the bottom edge).
 */
object CameraSheetMath {
    /** Share of the window height the sheet covers. */
    const val HEIGHT_FRACTION = 0.615f
    const val CORNER_DP = 18f
    const val SLIDE_MS = 300
    /** Centre of the control row above the sheet's bottom edge. */
    const val CONTROLS_CENTER_FROM_BOTTOM_DP = 61f

    /** A drag down past this share of the sheet height dismisses it on release... */
    const val DISMISS_FRACTION = 0.25f
    /** ...or a downward fling faster than this. */
    const val DISMISS_VELOCITY_DP_PER_S = 1000f

    /** Sheet height in px for a window [windowHeight] px tall. */
    fun sheetHeight(windowHeight: Float): Float = (windowHeight * HEIGHT_FRACTION).coerceAtLeast(0f)

    /** The sheet's downward offset while dragged by [drag] px: only downwards, never past its height. */
    fun dragOffset(drag: Float, sheetHeight: Float): Float = drag.coerceIn(0f, sheetHeight.coerceAtLeast(0f))

    /** Whether releasing a drag of [offset] px at [velocityDpPerS] (down = +) closes the sheet. */
    fun dismissOnRelease(offset: Float, sheetHeight: Float, velocityDpPerS: Float): Boolean =
        velocityDpPerS >= DISMISS_VELOCITY_DP_PER_S || (sheetHeight > 0f && offset >= sheetHeight * DISMISS_FRACTION)

    /** File name of a capture taken at [timeMs] (unique per millisecond, JPEG). */
    fun captureFileName(timeMs: Long): String = "capture-$timeMs.jpg"

    /**
     * How to turn a JPEG's EXIF orientation tag (1..8) into upright pixels: clockwise degrees to rotate and whether to
     * mirror horizontally first. Unknown values mean "as is".
     */
    fun orientationFix(exifOrientation: Int): Pair<Int, Boolean> = when (exifOrientation) {
        2 -> 0 to true
        3 -> 180 to false
        4 -> 180 to true
        5 -> 90 to true
        6 -> 90 to false
        7 -> 270 to true
        8 -> 270 to false
        else -> 0 to false
    }

    /** Power-of-two decode sample size that brings the longer side of a [w] x [h] picture to at most [maxSide]. */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        val longer = maxOf(w, h)
        while (longer / (s * 2) >= maxSide && maxSide > 0) s *= 2
        if (longer / s > maxSide && maxSide > 0) s *= 2
        return s
    }
}
