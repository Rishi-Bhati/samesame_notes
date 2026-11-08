package org.samesame.notes.feature.notes

import org.samesame.notes.core.model.ViewMode

/**
 * Number of columns of the notes grid for the window width, like the reference's list: narrow windows (phones in portrait,
 * <= 450 dp) keep 3 columns (2 for the large cards), wider ones add columns so a landscape phone or a tablet is not a
 * strip of huge cards. The plain list is one column, two in landscape and on mid-size portrait windows; the simple list stays one.
 */
internal fun notesGridColumns(mode: ViewMode, screenWidthDp: Int, smallestWidthDp: Int, landscape: Boolean): Int {
    val large = mode == ViewMode.GridLarge
    val tablet = smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP
    return when (mode) {
        ViewMode.SimpleList -> 1
        ViewMode.List -> if (landscape || smallestWidthDp in MID_SMALLEST_WIDTH_DP until TABLET_SMALLEST_WIDTH_DP) 2 else 1
        else -> when {
            screenWidthDp <= NARROW_DP -> if (large) 2 else 3
            screenWidthDp <= NORMAL_DP -> if (large) { if (tablet) 3 else 4 } else { if (tablet) 4 else 5 }
            screenWidthDp <= WIDE_DP -> if (large) 5 else 7
            else -> if (large) 6 else 7
        }
    }
}

/**
 * Number of folder cards per row, like the reference's folder span table: 4 on narrow (phone portrait) windows,
 * 5 on a tablet-sized mid window, 7 otherwise and 8 above [WIDE_DP]. The card width then comes from the available
 * width, so a phone keeps 4 cards where the fixed 85 dp card only fitted 3.
 */
internal fun folderColumns(screenWidthDp: Int, smallestWidthDp: Int): Int = when {
    screenWidthDp <= NARROW_DP -> 4
    screenWidthDp <= NORMAL_DP -> if (smallestWidthDp >= TABLET_SMALLEST_WIDTH_DP) 5 else 7
    screenWidthDp <= WIDE_DP -> 7
    else -> 8
}

private const val NARROW_DP = 450
private const val NORMAL_DP = 850
private const val WIDE_DP = 925
private const val MID_SMALLEST_WIDTH_DP = 585
private const val TABLET_SMALLEST_WIDTH_DP = 600
