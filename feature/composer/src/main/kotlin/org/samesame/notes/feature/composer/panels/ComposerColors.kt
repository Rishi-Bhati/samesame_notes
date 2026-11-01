package org.samesame.notes.feature.composer.panels

import androidx.compose.ui.graphics.Color

/** Colours of the docked editor toolbar (#26, #32 samples; light values from the colour resources). */
internal class ToolbarColors(
    val background: Color,
    val border: Color,
    val icon: Color,
    val iconDisabled: Color,
    /** Pill behind the active tool or open panel button. */
    val selected: Color,
    val toggleFill: Color,
    val toggleStroke: Color,
)

internal val DarkToolbarColors = ToolbarColors(
    background = Color(0xFF252525), border = Color(0xFF1E1E1E), icon = Color(0xFFEEEEEE), iconDisabled = Color(0xFF757575),
    selected = Color(0x33FFFFFF), toggleFill = Color(0xFF2A2A2A), toggleStroke = Color(0xFF5C5C5C),
)
internal val LightToolbarColors = ToolbarColors(
    background = Color.White, border = Color(0xFFF1F1F1), icon = Color(0xFF252525), iconDisabled = Color(0xFFB0B0B0),
    selected = Color(0x14000000), toggleFill = Color(0xFFFCFCFC), toggleStroke = Color(0xFFBEBEBE),
)

/**
 * Composer-local colours that the design system does not provide yet.
 * TODO: move to core/designsystem once it has editor tokens.
 */
internal object ComposerColors {
    /** Selected pen-tip / tool "pill" behind the active toolbar tool. */
    val toolActiveDark = Color(0xFF3A3A3A)
    val toolActiveLight = Color(0xFFDADADA)

    /** Selection bounding box and handles (red-orange, `#50`). */
    val selectionBox = Color(0xFFF46A4E)

    /** Default highlighter tint shown in the toolbar icon (#FFB426 = `DefaultHighlighters.Marker4.color`). */
    val defaultHighlighter = Color(0xFFFFB426)

    /** Font background strip (#30): the first cell is "no highlight" (black); a single page without dots. */
    val highlightStrip: List<Color> =
        listOf(0xFF010101, 0xFFFF3636, 0xFFFFB426, 0xFFFFF400, 0xFF00EBAD, 0xFF0037FF, 0xFFAE33FF, 0xFFFCFCFC).map { Color(it) }

    /** The four tiles of the "custom colour" entry at the end of each swatch strip. */
    val customTiles = listOf(Color(0xFF00D99F), Color(0xFFAE33FF), Color(0xFFFFF400), Color(0xFFFF3381)) // measured #29

    /** The two greys of the checkered "default" background swatch (`#56`). */
    val defaultBackgrounds = listOf(Color(0xFFEDEDED), Color(0xFFD6D6D6))
}
