package org.samesame.notes.feature.composer.panels

import org.samesame.notes.core.model.Limits
import kotlin.math.roundToInt

/**
 * The font-size popup: full point sizes plus paragraph heading looks. A heading is only a font size + bold
 * character style, so it needs no document format change; picking any plain size (or Body) clears it.
 */
object TextSizeCatalog {

    val SIZES: List<Int> = listOf(
        6, 7, 8, 9, 10, 11, 12, 14, 16, 17, 18, 20, 22, 24, 26, 28, 32, 36, 40, 44, 48, 54, 60, 72, 84, 96,
    )

    val BODY_SIZE: Int get() = Limits.DEFAULT_FONT_SIZE.roundToInt()

    enum class Heading(val size: Int) { H1(32), H2(24), H3(20) }

    /** The heading look of a character style, or null for ordinary body text. */
    fun headingFor(fontSize: Float?, bold: Boolean): Heading? {
        if (!bold) return null
        val size = (fontSize ?: Limits.DEFAULT_FONT_SIZE).roundToInt()
        return Heading.entries.firstOrNull { it.size == size }
    }

    /** Size shown in the toolbar button / highlighted in the list (a custom size is offered too). */
    fun selectedSize(fontSize: Float?): Int = (fontSize ?: Limits.DEFAULT_FONT_SIZE).roundToInt()

    /** All sizes to show, with the current one added when it is not one of [SIZES]. */
    fun menuSizes(fontSize: Float?): List<Int> {
        val current = selectedSize(fontSize)
        return if (current in SIZES) SIZES else (SIZES + current).sorted()
    }
}
