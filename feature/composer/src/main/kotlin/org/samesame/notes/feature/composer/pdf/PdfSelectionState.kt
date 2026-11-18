package org.samesame.notes.feature.composer.pdf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import org.samesame.notes.core.model.pdf.PdfGlyph
import org.samesame.notes.core.model.pdf.PdfLinkAnnotation
import org.samesame.notes.core.model.pdf.PdfPageText
import org.samesame.notes.core.model.pdf.PdfWord
import org.samesame.notes.core.model.pdf.RectPt
import kotlin.math.abs

/**
 * Manages in-note PDF text selection, handle dragging, copy to clipboard, and link interaction.
 */
class PdfSelectionState {

    /** The 0-based page index of the currently selected PDF page, or null if no selection. */
    var selectedPage: Int? by mutableStateOf(null)

    /** Selection character range [start..endExclusive - 1] in [PdfPageText.text]. */
    var selectionRange: IntRange? by mutableStateOf(null)

    var isDraggingHandle: Boolean by mutableStateOf(false)
    var isStartHandle: Boolean by mutableStateOf(false)
    var dragPositionPx: Offset by mutableStateOf(Offset.Zero)

    val hasSelection: Boolean
        get() = selectedPage != null && selectionRange != null && !selectionRange!!.isEmpty()

    fun clear() {
        selectedPage = null
        selectionRange = null
        isDraggingHandle = false
        isStartHandle = false
        dragPositionPx = Offset.Zero
    }

    fun selectWord(pageIndex: Int, word: PdfWord): Boolean {
        selectedPage = pageIndex
        selectionRange = word.startIndex until word.endIndex
        return true
    }

    fun selectRange(pageIndex: Int, range: IntRange) {
        selectedPage = pageIndex
        selectionRange = range
    }

    fun hitTestWord(pageText: PdfPageText, ptX: Float, ptY: Float, tolerancePt: Float = 4f): PdfWord? {
        for (line in pageText.lines) {
            if (ptY < line.boundsPt.top - tolerancePt || ptY > line.boundsPt.bottom + tolerancePt) continue
            for (word in line.words) {
                val b = word.boundsPt
                if (ptX in (b.left - tolerancePt)..(b.right + tolerancePt) &&
                    ptY in (b.top - tolerancePt)..(b.bottom + tolerancePt)
                ) {
                    return word
                }
            }
        }
        return null
    }

    fun hitTestLink(pageText: PdfPageText, ptX: Float, ptY: Float, tolerancePt: Float = 4f): PdfLinkAnnotation? {
        for (link in pageText.links) {
            val b = link.boundsPt
            if (ptX in (b.left - tolerancePt)..(b.right + tolerancePt) &&
                ptY in (b.top - tolerancePt)..(b.bottom + tolerancePt)
            ) {
                return link
            }
        }
        return null
    }

    /**
     * Hit tests the selection handles in view px.
     * Returns true for start handle, false for end handle, or null if neither was hit.
     */
    fun hitTestHandle(
        viewPos: Offset,
        startHandlePos: Offset,
        endHandlePos: Offset,
        touchRadiusPx: Float = 36f,
    ): Boolean? {
        val distStart = (viewPos - startHandlePos).getDistance()
        val distEnd = (viewPos - endHandlePos).getDistance()
        return when {
            distStart <= touchRadiusPx && distStart <= distEnd -> true
            distEnd <= touchRadiusPx -> false
            else -> null
        }
    }

    /**
     * Updates selection range while dragging the start or end handle to point (ptX, ptY) on [pageText].
     */
    fun updateDrag(pageText: PdfPageText, ptX: Float, ptY: Float) {
        val range = selectionRange ?: return
        if (pageText.glyphs.isEmpty()) return

        val nearestGlyph = findNearestGlyph(pageText, ptX, ptY) ?: return
        val targetIdx = nearestGlyph.index

        if (isStartHandle) {
            val currentEnd = range.last + 1
            if (targetIdx < currentEnd) {
                selectionRange = targetIdx until currentEnd
            } else {
                // Dragged past end handle: flip to dragging end handle
                isStartHandle = false
                selectionRange = (currentEnd - 1) until (targetIdx + 1)
            }
        } else {
            val currentStart = range.first
            if (targetIdx >= currentStart) {
                selectionRange = currentStart until (targetIdx + 1)
            } else {
                // Dragged before start handle: flip to dragging start handle
                isStartHandle = true
                selectionRange = targetIdx until (currentStart + 1)
            }
        }
    }

    private fun findNearestGlyph(pageText: PdfPageText, ptX: Float, ptY: Float): PdfGlyph? {
        var closest: PdfGlyph? = null
        var minDistance = Float.MAX_VALUE

        for (glyph in pageText.glyphs) {
            val b = glyph.boundsPt
            if (b.width <= 0f && b.height <= 0f) continue

            val centerX = (b.left + b.right) / 2f
            val centerY = (b.top + b.bottom) / 2f
            val dx = ptX - centerX
            val dy = ptY - centerY
            val dist = dx * dx + dy * dy * 4f // Weight vertical distance higher so line matching is preferred
            if (dist < minDistance) {
                minDistance = dist
                closest = glyph
            }
        }
        return closest
    }

    fun selectedText(pageText: PdfPageText): String? {
        val range = selectionRange ?: return null
        if (pageText.pageIndex != selectedPage) return null
        val start = range.first.coerceIn(0, pageText.text.length)
        val end = (range.last + 1).coerceIn(start, pageText.text.length)
        if (start >= end) return null
        return pageText.text.substring(start, end)
    }

    fun copyToClipboard(clipboard: ClipboardManager, pageText: PdfPageText): String? {
        val text = selectedText(pageText) ?: return null
        clipboard.setText(AnnotatedString(text))
        return text
    }

    /**
     * Bounding rects in PDF points for the selected glyph range, grouped by line.
     */
    fun selectionRectsPt(pageText: PdfPageText): List<RectPt> {
        val range = selectionRange ?: return emptyList()
        if (pageText.pageIndex != selectedPage) return emptyList()
        val start = range.first.coerceIn(0, pageText.glyphs.size)
        val end = (range.last + 1).coerceIn(start, pageText.glyphs.size)
        val selGlyphs = pageText.glyphs.subList(start, end).filter { it.boundsPt.width > 0f && it.boundsPt.height > 0f }
        if (selGlyphs.isEmpty()) return emptyList()

        val rects = mutableListOf<RectPt>()
        for (line in pageText.lines) {
            val lineHits = selGlyphs.filter { g ->
                g.boundsPt.top < line.boundsPt.bottom && g.boundsPt.bottom > line.boundsPt.top
            }
            if (lineHits.isNotEmpty()) {
                rects.add(
                    RectPt(
                        left = lineHits.minOf { it.boundsPt.left },
                        top = lineHits.minOf { it.boundsPt.top },
                        right = lineHits.maxOf { it.boundsPt.right },
                        bottom = lineHits.maxOf { it.boundsPt.bottom },
                    )
                )
            }
        }
        if (rects.isEmpty() && selGlyphs.isNotEmpty()) {
            rects.add(
                RectPt(
                    left = selGlyphs.minOf { it.boundsPt.left },
                    top = selGlyphs.minOf { it.boundsPt.top },
                    right = selGlyphs.maxOf { it.boundsPt.right },
                    bottom = selGlyphs.maxOf { it.boundsPt.bottom },
                )
            )
        }
        return rects
    }

    /**
     * Returns the tip positions (in PDF points) of the start handle (bottom-left) and end handle (bottom-right).
     */
    fun handleTipsPt(pageText: PdfPageText): Pair<Offset, Offset>? {
        val range = selectionRange ?: return null
        if (pageText.pageIndex != selectedPage) return null
        val start = range.first.coerceIn(0, pageText.glyphs.size)
        val end = (range.last + 1).coerceIn(start, pageText.glyphs.size)
        val selGlyphs = pageText.glyphs.subList(start, end).filter { it.boundsPt.width > 0f && it.boundsPt.height > 0f }
        if (selGlyphs.isEmpty()) return null

        val first = selGlyphs.first()
        val last = selGlyphs.last()

        val startTip = Offset(first.boundsPt.left, first.boundsPt.bottom)
        val endTip = Offset(last.boundsPt.right, last.boundsPt.bottom)
        return startTip to endTip
    }
}
