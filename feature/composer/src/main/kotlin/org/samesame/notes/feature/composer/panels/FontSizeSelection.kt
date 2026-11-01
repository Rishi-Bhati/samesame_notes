package org.samesame.notes.feature.composer.panels

/** One choice in the font-size popup: a heading look, Body (default size, not bold) or a plain point size. */
sealed interface FontSizePick {
    data class Heading(val heading: TextSizeCatalog.Heading) : FontSizePick
    data object Body : FontSizePick
    data class Size(val pt: Int) : FontSizePick
}

/** Row order of the popup: three headings, Body, then the plain sizes. */
internal object FontSizeRows {
    val BODY_INDEX = TextSizeCatalog.Heading.entries.size
    val SIZE_START = BODY_INDEX + 1

    fun count(sizes: List<Int>): Int = SIZE_START + sizes.size

    fun pick(index: Int, sizes: List<Int>): FontSizePick? = when {
        index < 0 -> null
        index < TextSizeCatalog.Heading.entries.size -> FontSizePick.Heading(TextSizeCatalog.Heading.entries[index])
        index == BODY_INDEX -> FontSizePick.Body
        else -> sizes.getOrNull(index - SIZE_START)?.let(FontSizePick::Size)
    }

    /** Row under a touch at [y] (root coordinates), or -1 when the touch is between/away from rows. */
    fun rowAt(y: Float, rows: List<RowBounds>): Int =
        rows.firstOrNull { y >= it.top && y < it.bottom }?.index ?: -1
}

/** A row's bounds in root coordinates, reported by the popup (scrolling updates them). */
data class RowBounds(val index: Int, val top: Float, val bottom: Float)
