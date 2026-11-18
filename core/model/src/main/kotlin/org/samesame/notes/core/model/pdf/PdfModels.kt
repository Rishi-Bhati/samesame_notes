package org.samesame.notes.core.model.pdf

/**
 * Text layer of a PDF page, produced by the extractor in core:data. Nothing here is saved in a note.
 * All geometry is in PDF points of the page as the renderer shows it (the PDF's own /Rotate applied, so it matches
 * `PageSpec.size`), origin top left, y down, before any `PageSpec.rotation`.
 */
data class RectPt(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** One character; [index] is its position in [PdfPageText.text]. */
data class PdfGlyph(val char: Char, val boundsPt: RectPt, val index: Int)

/** A word as a char range [startIndex, endIndex) of [PdfPageText.text]. */
data class PdfWord(val text: String, val boundsPt: RectPt, val startIndex: Int, val endIndex: Int)

data class PdfLine(val text: String, val boundsPt: RectPt, val words: List<PdfWord>)

sealed interface PdfLinkDestination {
    data class External(val uri: String) : PdfLinkDestination
    /** [targetYPt] is the scroll target inside the page (top-left origin), or null for the page top. */
    data class Internal(val targetPageIndex: Int, val targetYPt: Float? = null) : PdfLinkDestination
}

data class PdfLinkAnnotation(val boundsPt: RectPt, val destination: PdfLinkDestination)

/** One outline (table of contents) entry; [pageIndex] is 0-based in the PDF, -1 when the target could not be resolved. */
data class PdfOutlineNode(
    val title: String,
    val pageIndex: Int,
    val depth: Int = 0,
    val children: List<PdfOutlineNode> = emptyList(),
)

/** [glyphs] has one entry per char of [text] (whitespace included, with an empty rect), so indices line up. */
data class PdfPageText(
    val pageIndex: Int,
    val text: String,
    val glyphs: List<PdfGlyph>,
    val lines: List<PdfLine>,
    val links: List<PdfLinkAnnotation>,
) {
    companion object {
        fun empty(pageIndex: Int) = PdfPageText(pageIndex, "", emptyList(), emptyList(), emptyList())
    }
}

/** A search hit: char range in [PdfPageText.text] plus one rect per line it touches. */
data class PdfSearchMatch(val pageIndex: Int, val start: Int, val endExclusive: Int, val rectsPt: List<RectPt>)
