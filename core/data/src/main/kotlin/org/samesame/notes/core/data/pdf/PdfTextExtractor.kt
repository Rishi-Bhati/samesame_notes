package org.samesame.notes.core.data.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.pdf.PdfGlyph
import org.samesame.notes.core.model.pdf.PdfLine
import org.samesame.notes.core.model.pdf.PdfLinkAnnotation
import org.samesame.notes.core.model.pdf.PdfLinkDestination
import org.samesame.notes.core.model.pdf.PdfOutlineNode
import org.samesame.notes.core.model.pdf.PdfPageText
import org.samesame.notes.core.model.pdf.PdfSearchMatch
import org.samesame.notes.core.model.pdf.PdfWord
import org.samesame.notes.core.model.pdf.RectPt
import java.io.Writer
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracts text layers (glyphs, words, lines), link annotations, and outlines from PDF files using PdfBox-Android.
 */
class PdfTextExtractor(context: Context? = null) {

    init {
        context?.let { init(it) }
    }

    /**
     * Extracts text, glyphs, words, lines, and links for [pageIndex] (0-based) from [document].
     */
    fun extractPage(document: PDDocument, pageIndex: Int): PdfPageText {
        if (pageIndex < 0 || pageIndex >= document.numberOfPages) {
            return PdfPageText.empty(pageIndex)
        }
        val page = document.getPage(pageIndex)
        val stripper = PageStripper(pageIndex)
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.sortByPosition = true

        stripper.writeText(document, object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) = Unit
            override fun flush() = Unit
            override fun close() = Unit
        })

        val (text, glyphs, lines) = stripper.buildResult()
        val links = extractLinks(document, page)

        return PdfPageText(
            pageIndex = pageIndex,
            text = text,
            glyphs = glyphs,
            lines = lines,
            links = links,
        )
    }

    fun extractPage(file: File, pageIndex: Int): PdfPageText =
        PDDocument.load(file, MemoryUsageSetting.setupMixed(Limits.PDF_SCRATCH_MEMORY_BYTES)).use { doc -> extractPage(doc, pageIndex) }

    fun extractAllPages(file: File): List<PdfPageText> =
        PDDocument.load(file, MemoryUsageSetting.setupMixed(Limits.PDF_SCRATCH_MEMORY_BYTES)).use { doc ->
            List(doc.numberOfPages) { i -> extractPage(doc, i) }
        }

    /**
     * Extracts document outline (table of contents) up to [Limits.PDF_MAX_OUTLINE_DEPTH].
     */
    fun extractOutline(document: PDDocument): List<PdfOutlineNode> {
        val outline = document.documentCatalog.documentOutline ?: return emptyList()
        val result = mutableListOf<PdfOutlineNode>()
        for (item in outline.children()) {
            val node = parseOutlineItem(document, item, depth = 0)
            if (node != null) result.add(node)
        }
        return result
    }

    fun extractOutline(file: File): List<PdfOutlineNode> =
        PDDocument.load(file, MemoryUsageSetting.setupMixed(Limits.PDF_SCRATCH_MEMORY_BYTES)).use { doc -> extractOutline(doc) }

    private fun parseOutlineItem(doc: PDDocument, item: PDOutlineItem, depth: Int): PdfOutlineNode? {
        if (depth > Limits.PDF_MAX_OUTLINE_DEPTH) return null
        val title = item.title ?: ""
        var targetPageIndex = -1

        val dest = item.destination ?: (item.action as? PDActionGoTo)?.destination
        if (dest != null) {
            targetPageIndex = resolveDestinationPage(doc, dest)
        }

        val children = mutableListOf<PdfOutlineNode>()
        if (depth < Limits.PDF_MAX_OUTLINE_DEPTH) {
            for (child in item.children()) {
                val childNode = parseOutlineItem(doc, child, depth + 1)
                if (childNode != null) children.add(childNode)
            }
        }

        return PdfOutlineNode(
            title = title,
            pageIndex = targetPageIndex,
            depth = depth,
            children = children,
        )
    }

    private fun extractLinks(doc: PDDocument, page: PDPage): List<PdfLinkAnnotation> {
        val annotations = try {
            page.annotations
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()

        val links = mutableListOf<PdfLinkAnnotation>()
        for (annot in annotations) {
            if (annot !is PDAnnotationLink) continue
            val rect = annot.rectangle ?: continue
            val boundsPt = toRectPt(rect, page)

            val destination = resolveLinkDestination(doc, annot) ?: continue
            links.add(PdfLinkAnnotation(boundsPt, destination))
        }
        return links
    }

    private fun resolveLinkDestination(doc: PDDocument, link: PDAnnotationLink): PdfLinkDestination? {
        val action = link.action
        if (action is PDActionURI) {
            val uri = action.uri ?: return null
            return PdfLinkDestination.External(uri)
        }
        val dest = link.destination ?: (action as? PDActionGoTo)?.destination
        if (dest != null) {
            val pageIndex = resolveDestinationPage(doc, dest)
            if (pageIndex >= 0) {
                var targetYPt: Float? = null
                val resolvedPage = resolveDestinationPageObj(doc, dest)
                if (dest is PDPageXYZDestination && dest.top != -1) {
                    val pageHeight = resolvedPage?.cropBox?.height ?: 842f
                    targetYPt = pageHeight - dest.top
                }
                return PdfLinkDestination.Internal(pageIndex, targetYPt)
            }
        }
        return null
    }

    private fun resolveDestinationPage(doc: PDDocument, dest: Any): Int {
        val pageObj = resolveDestinationPageObj(doc, dest)
        if (pageObj != null) {
            val index = doc.pages.indexOf(pageObj)
            if (index >= 0) return index
        }
        if (dest is PDPageDestination) {
            val num = dest.pageNumber
            if (num in 0 until doc.numberOfPages) return num
        }
        return -1
    }

    private fun resolveDestinationPageObj(doc: PDDocument, dest: Any): PDPage? {
        return when (dest) {
            is PDPageDestination -> dest.page
            is PDNamedDestination -> {
                val named = doc.documentCatalog.findNamedDestinationPage(dest)
                if (named is PDPageDestination) named.page else null
            }
            else -> null
        }
    }

    /**
     * Converts a PDF user-space [PDRectangle] to [RectPt] with top-left origin.
     */
    private fun toRectPt(rect: PDRectangle, page: PDPage): RectPt {
        val crop = page.cropBox ?: page.mediaBox ?: PDRectangle(0f, 0f, 595f, 842f)
        val pageW = crop.width
        val pageH = crop.height

        val x1 = rect.lowerLeftX - crop.lowerLeftX
        val y1 = rect.lowerLeftY - crop.lowerLeftY
        val x2 = rect.upperRightX - crop.lowerLeftX
        val y2 = rect.upperRightY - crop.lowerLeftY

        return when ((page.rotation % 360 + 360) % 360) {
            90 -> RectPt(
                left = minOf(y1, y2),
                top = minOf(x1, x2),
                right = maxOf(y1, y2),
                bottom = maxOf(x1, x2),
            )
            180 -> RectPt(
                left = pageW - maxOf(x1, x2),
                top = minOf(y1, y2),
                right = pageW - minOf(x1, x2),
                bottom = maxOf(y1, y2),
            )
            270 -> RectPt(
                left = pageH - maxOf(y1, y2),
                top = pageW - maxOf(x1, x2),
                right = pageH - minOf(y1, y2),
                bottom = pageW - minOf(x1, x2),
            )
            else -> RectPt(
                left = minOf(x1, x2),
                top = pageH - maxOf(y1, y2),
                right = maxOf(x1, x2),
                bottom = pageH - minOf(y1, y2),
            )
        }
    }

    /**
     * Custom stripper that records glyphs, words, and lines.
     */
    private class PageStripper(private val targetPageIndex: Int) : PDFTextStripper() {
        private var positionedGlyphs = 0
        private var positionedChars = 0

        override fun processTextPosition(text: TextPosition) {
            val chars = text.unicode?.length ?: 0
            if (positionedGlyphs >= Limits.PDF_TEXT_MAX_GLYPHS_PER_PAGE || positionedChars + chars > Limits.PDF_TEXT_MAX_CHARS_PER_PAGE) return
            positionedGlyphs++
            positionedChars += chars
            super.processTextPosition(text)
        }

        private val textBuilder = StringBuilder()
        private val glyphs = mutableListOf<PdfGlyph>()
        private val currentLineWords = mutableListOf<PdfWord>()
        private val allLines = mutableListOf<PdfLine>()

        private val wordChars = StringBuilder()
        private var wordStartIndex = -1
        private val wordGlyphBounds = mutableListOf<RectPt>()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            var posIdx = 0
            for (ch in text) {
                if (glyphs.size >= Limits.PDF_TEXT_MAX_CHARS_PER_PAGE) break
                if (ch.isWhitespace()) {
                    finishCurrentWord()
                    val idx = glyphs.size
                    textBuilder.append(ch)
                    glyphs.add(PdfGlyph(ch, RectPt(0f, 0f, 0f, 0f), idx))
                } else {
                    val pos = textPositions.getOrNull(posIdx)
                    posIdx++
                    val bounds = if (pos != null) {
                        val h = if (pos.fontSize > 0f) pos.fontSize else if (pos.heightDir > 0f) pos.heightDir else 12f
                        val left = pos.xDirAdj
                        val top = pos.yDirAdj - h
                        RectPt(left, top, left + pos.widthDirAdj, top + h)
                    } else {
                        RectPt(0f, 0f, 0f, 0f)
                    }

                    if (wordStartIndex == -1) {
                        wordStartIndex = glyphs.size
                    }
                    wordChars.append(ch)
                    wordGlyphBounds.add(bounds)

                    val idx = glyphs.size
                    textBuilder.append(ch)
                    glyphs.add(PdfGlyph(ch, bounds, idx))
                }
            }
        }

        override fun writeWordSeparator() {
            finishCurrentWord()
            if (glyphs.size < Limits.PDF_TEXT_MAX_CHARS_PER_PAGE && textBuilder.isNotEmpty() && !textBuilder.last().isWhitespace()) {
                val idx = glyphs.size
                textBuilder.append(' ')
                glyphs.add(PdfGlyph(' ', RectPt(0f, 0f, 0f, 0f), idx))
            }
        }

        override fun writeLineSeparator() {
            finishCurrentWord()
            finishCurrentLine()
            if (glyphs.size < Limits.PDF_TEXT_MAX_CHARS_PER_PAGE && textBuilder.isNotEmpty() && textBuilder.last() != '\n') {
                val idx = glyphs.size
                textBuilder.append('\n')
                glyphs.add(PdfGlyph('\n', RectPt(0f, 0f, 0f, 0f), idx))
            }
        }

        private fun finishCurrentWord() {
            if (wordChars.isNotEmpty()) {
                val wordText = wordChars.toString()
                val start = wordStartIndex
                val end = start + wordText.length
                val bounds = union(wordGlyphBounds) ?: RectPt(0f, 0f, 0f, 0f)
                currentLineWords.add(PdfWord(wordText, bounds, start, end))
                wordChars.clear()
                wordGlyphBounds.clear()
                wordStartIndex = -1
            }
        }

        private fun finishCurrentLine() {
            if (currentLineWords.isNotEmpty()) {
                val lineText = currentLineWords.joinToString(" ") { it.text }
                val lineBounds = union(currentLineWords.map { it.boundsPt }) ?: RectPt(0f, 0f, 0f, 0f)
                allLines.add(PdfLine(lineText, lineBounds, currentLineWords.toList()))
                currentLineWords.clear()
            }
        }

        fun buildResult(): Triple<String, List<PdfGlyph>, List<PdfLine>> {
            finishCurrentWord()
            finishCurrentLine()
            return Triple(textBuilder.toString(), glyphs, allLines)
        }

        private fun union(rects: List<RectPt>): RectPt? {
            if (rects.isEmpty()) return null
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            var valid = false
            for (r in rects) {
                if (r.width > 0f || r.height > 0f) {
                    left = minOf(left, r.left)
                    top = minOf(top, r.top)
                    right = maxOf(right, r.right)
                    bottom = maxOf(bottom, r.bottom)
                    valid = true
                }
            }
            return if (valid && left <= right && top <= bottom) RectPt(left, top, right, bottom) else null
        }
    }

    companion object {
        private val initialized = AtomicBoolean(false)

        fun init(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PDFBoxResourceLoader.init(context.applicationContext)
            }
        }

        /**
         * Finds matches of [query] in [pageText] and computes hit bounding boxes.
         */
        fun search(pageText: PdfPageText, query: String, maxMatches: Int = Limits.PDF_SEARCH_MAX_MATCHES): List<PdfSearchMatch> {
            val q = query.trim()
            if (q.isEmpty() || pageText.text.isEmpty()) return emptyList()

            val matches = mutableListOf<PdfSearchMatch>()
            var start = 0
            while (start < pageText.text.length && matches.size < maxMatches.coerceIn(0, Limits.PDF_SEARCH_MAX_MATCHES)) {
                val idx = pageText.text.indexOf(q, start, ignoreCase = true)
                if (idx < 0) break
                val endExclusive = idx + q.length

                // Compute bounding rects per line touched
                val hitGlyphs = pageText.glyphs.subList(idx, minOf(endExclusive, pageText.glyphs.size))
                    .filter { it.boundsPt.width > 0f && it.boundsPt.height > 0f }

                val lineRects = mutableListOf<RectPt>()
                for (line in pageText.lines) {
                    val lineHits = hitGlyphs.filter { g ->
                        g.boundsPt.top < line.boundsPt.bottom && g.boundsPt.bottom > line.boundsPt.top
                    }
                    if (lineHits.isNotEmpty()) {
                        val minLeft = lineHits.minOf { it.boundsPt.left }
                        val minTop = lineHits.minOf { it.boundsPt.top }
                        val maxRight = lineHits.maxOf { it.boundsPt.right }
                        val maxBottom = lineHits.maxOf { it.boundsPt.bottom }
                        lineRects.add(RectPt(minLeft, minTop, maxRight, maxBottom))
                    }
                }

                if (lineRects.isEmpty() && hitGlyphs.isNotEmpty()) {
                    val minLeft = hitGlyphs.minOf { it.boundsPt.left }
                    val minTop = hitGlyphs.minOf { it.boundsPt.top }
                    val maxRight = hitGlyphs.maxOf { it.boundsPt.right }
                    val maxBottom = hitGlyphs.maxOf { it.boundsPt.bottom }
                    lineRects.add(RectPt(minLeft, minTop, maxRight, maxBottom))
                }

                matches.add(PdfSearchMatch(pageText.pageIndex, idx, endExclusive, lineRects))
                start = idx + 1
            }
            return matches
        }
    }
}
