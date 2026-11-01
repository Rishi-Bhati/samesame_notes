package org.samesame.notes.feature.composer.menus

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * Where the selection pill and its more menu go. The reference shows its object context menu as a platform floating
 * action mode, so this follows the platform floating toolbar's rules: the content rect (the selection, grown by
 * [Metrics.contentInflate] for the handles and rotate knob, and clipped to the visible canvas) gets the pill above it
 * when there is room in the window, otherwise below it, otherwise as high as the window allows; it is centred on the
 * selection and kept [Metrics.marginH] / [Metrics.marginV] inside the window. A selection scrolled completely out of
 * the canvas has no pill. All values are px in the coordinate space of [window].
 */
internal object ContextMenuPlacement {

    data class Metrics(
        /** Grows the selection on the top and bottom before placing (handles, rotate knob). */
        val contentInflate: Float,
        val marginH: Float,
        val marginV: Float,
        /** How far the clipped content rect may reach below the canvas bottom (platform: 20 dp). */
        val bottomAllowance: Float,
    )

    data class Pill(val position: Offset, val above: Boolean)

    /**
     * Places a pill of [pillWidth] x [pillHeight] for a selection [selection] shown in [canvas] (the visible part of
     * the editing surface), inside [window] (the area free of system bars and keyboard). Null when the selection is
     * not visible at all.
     */
    fun placePill(selection: Rect, canvas: Rect, window: Rect, pillWidth: Float, pillHeight: Float, m: Metrics): Pill? {
        if (selection.right < canvas.left || selection.left > canvas.right ||
            selection.bottom < canvas.top || selection.top > canvas.bottom
        ) return null
        val top = maxOf(selection.top - m.contentInflate, canvas.top)
        val bottom = minOf(selection.bottom + m.contentInflate, canvas.bottom + m.bottomAllowance)
        val x = clampX((selection.left + selection.right) / 2f - pillWidth / 2f, pillWidth, window, m)
        val withMargin = pillHeight + 2f * m.marginV
        val above = top - window.top
        val below = window.bottom - bottom
        return when {
            above >= withMargin -> Pill(Offset(x, top - m.marginV - pillHeight), above = true)
            below >= withMargin -> Pill(Offset(x, bottom + m.marginV), above = false)
            below >= pillHeight -> Pill(Offset(x, bottom), above = false)
            // No room either side: as high as possible, over the selection.
            else -> Pill(Offset(x, window.top + m.marginV), above = true)
        }
    }

    /**
     * The more menu replaces the pill: its right edge stays on the pill's right edge. It opens away from the
     * selection (upwards, bottom on the pill's bottom, when the pill is above; downwards, top on the pill's top, when
     * below), flips when that runs out of the window, and is finally clamped inside it ([menuHeight] is expected to be
     * at most the window height minus the margins; the caller limits it and lets the menu scroll).
     */
    fun placeMenu(pill: Pill, pillWidth: Float, pillHeight: Float, menuWidth: Float, menuHeight: Float, window: Rect, m: Metrics): Offset {
        val x = clampX(pill.position.x + pillWidth - menuWidth, menuWidth, window, m)
        val minY = window.top + m.marginV
        val maxY = window.bottom - m.marginV - menuHeight
        val up = pill.position.y + pillHeight - menuHeight
        val down = pill.position.y
        val y = if (pill.above) {
            if (up >= minY) up else if (down <= maxY) down else up
        } else {
            if (down <= maxY) down else if (up >= minY) up else down
        }
        return Offset(x, if (maxY < minY) minY else y.coerceIn(minY, maxY))
    }

    private fun clampX(x: Float, width: Float, window: Rect, m: Metrics): Float {
        val minX = window.left + m.marginH
        val maxX = window.right - m.marginH - width
        return if (maxX < minX) (window.left + window.right - width) / 2f else x.coerceIn(minX, maxX)
    }
}
