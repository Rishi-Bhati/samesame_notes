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

    /**
     * The applied row, where a glide starts: the heading; Body when Body was the last pick ([bodyPicked]) and the
     * size is still Body's; else the plain size row (also for 17 that was not picked as Body, so the first steps
     * reach 16 and 18); else Body.
     */
    fun anchorRow(heading: TextSizeCatalog.Heading?, size: Int, sizes: List<Int>, bodyPicked: Boolean = false): Int = when {
        heading != null -> heading.ordinal
        bodyPicked && size == TextSizeCatalog.BODY_SIZE -> BODY_INDEX
        size in sizes -> SIZE_START + sizes.indexOf(size)
        else -> BODY_INDEX
    }
}
