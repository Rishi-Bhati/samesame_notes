package org.samesame.notes.feature.composer.pdf

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import org.samesame.notes.core.data.pdf.PdfCoordinates
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PdfMarkupKind
import org.samesame.notes.core.model.document.QuadPu
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.pdf.PdfLinkDestination
import org.samesame.notes.feature.composer.canvas.DocumentLayout
import org.samesame.notes.feature.composer.canvas.Viewport

private const val DEFAULT_WIDTH_PT = 595f
private const val HIGHLIGHT_COLOR = 0x66FFEB3BL
private const val LINE_COLOR = 0xFFE53935L

/**
 * Touch handling and selection UI for PDF pages: long press on a word selects it (handles, magnifier, Copy and the
 * markup buttons come from [PdfSelectionOverlay]), a tap on a link follows it, a tap elsewhere drops the selection.
 * Presses are only observed, never consumed (until a long press lands on a word), so panning and zooming stay with
 * the canvas.
 */
@Composable
fun rememberPdfInteraction(
    host: PdfEngineHost,
    pages: List<PageSpec>,
    layout: DocumentLayout,
    viewport: Viewport,
    enabled: Boolean,
    onExternalLink: (String) -> Unit,
    /** Adds a markup to the page at the given note index. */
    onMarkup: (pageIndex: Int, kind: PdfMarkupKind, color: Long, quads: List<QuadPu>, text: String) -> Unit,
): PdfInteraction {
    val selection = host.selection
    val pagesNow by rememberUpdatedState(pages)
    val layoutNow by rememberUpdatedState(layout)
    val enabledNow by rememberUpdatedState(enabled)

    LaunchedEffect(enabled) { if (!enabled) selection.clear() }
    // Start extracting the text of the pages in view, so a long press finds its words ready.
    LaunchedEffect(host) {
        snapshotFlow { layoutNow.visiblePages(viewport.visibleDoc) }.collect { range ->
            for (i in range) pagesNow.getOrNull(i)?.pdf?.let { host.rawText(it) }
        }
    }

    fun pageAt(view: Offset): Int {
        val doc = viewport.viewToDoc(view)
        return layoutNow.pageRects.indexOfFirst { it.contains(doc) }
    }

    fun widthPtOf(i: Int) = pagesNow.getOrNull(i)?.size?.widthPt ?: DEFAULT_WIDTH_PT

    fun toPt(view: Offset, i: Int): Offset {
        val o = layoutNow.pageOrigin(i)
        val (x, y) = PdfCoordinates.viewToPt(
            view.x, view.y, viewport.origin.x, viewport.origin.y, viewport.scale, o.x, o.y, widthPtOf(i),
        )
        return Offset(x, y)
    }

    fun followInternal(media: String, targetPdfPage: Int, yPt: Float?, from: Int) {
        val target = pagesNow.indexOfFirst { it.pdf?.media == media && it.pdf?.pageIndex == targetPdfPage }
        if (target < 0) return
        val o = layoutNow.pageOrigin(target)
        val yPu = if (yPt != null) yPt * PdfCoordinates.puPerPt(widthPtOf(target)) else 0f
        val margin = 24f
        val dest = if (layoutNow.horizontal) Offset(o.x, viewport.scroll.y) else Offset(viewport.scroll.x, o.y + yPu - margin)
        viewport.scrollTo(dest)
        selection.clear()
    }

    val pressModifier = Modifier.pdfPresses(
            onTap = { p ->
                if (!enabledNow) return@pdfPresses
                val i = pageAt(p)
                val pdf = pagesNow.getOrNull(i)?.pdf
                val text = if (pdf != null) host.pageText(pdf, i) else null
                val pt = if (i >= 0) toPt(p, i) else null
                val link = if (text != null && pt != null) selection.hitTestLink(text, pt.x, pt.y) else null
                if (link != null && pdf != null) {
                    when (val d = link.destination) {
                        is PdfLinkDestination.External -> onExternalLink(d.uri)
                        is PdfLinkDestination.Internal -> followInternal(pdf.media, d.targetPageIndex, d.targetYPt, i)
                    }
                } else if (selection.hasSelection) {
                    selection.clear()
                }
            },
            onLongPress = { p ->
                if (!enabledNow) return@pdfPresses false
                val i = pageAt(p)
                val pdf = pagesNow.getOrNull(i)?.pdf ?: return@pdfPresses false
                val text = host.pageText(pdf, i) ?: return@pdfPresses false
                val pt = toPt(p, i)
                val word = selection.hitTestWord(text, pt.x, pt.y) ?: return@pdfPresses false
                selection.selectWord(i, word)
                true
            },
        
    )

    return PdfInteraction(pressModifier) { 
        if (enabled) {
            PdfSelectionOverlay(
                selectionState = selection,
                pageTextProvider = { i -> pagesNow.getOrNull(i)?.pdf?.let { host.pageText(it, i) } },
                pageWidthPtProvider = { i -> widthPtOf(i) },
                layout = layout,
                viewport = viewport,
                onMarkup = { kind ->
                    val i = selection.selectedPage
                    val text = i?.let { idx -> pagesNow.getOrNull(idx)?.pdf?.let { host.pageText(it, idx) } }
                    if (i != null && text != null) {
                        val w = widthPtOf(i)
                        val quads = selection.selectionRectsPt(text).map { r ->
                            val pu = PdfCoordinates.ptToPagePu(r, w)
                            QuadPu.fromRect(RectPu(pu.left, pu.top, pu.right, pu.bottom))
                        }
                        val color = if (kind == PdfMarkupKind.Highlight) HIGHLIGHT_COLOR else LINE_COLOR
                        onMarkup(i, kind, color, quads, selection.selectedText(text).orEmpty())
                    }
                    selection.clear()
                },
            )
        }
    }
}

/** [pressModifier] goes on the canvas modifier (observes presses); [Overlay] draws selection handles and the action bar. */
class PdfInteraction internal constructor(val pressModifier: Modifier, private val overlay: @Composable () -> Unit) {
    @Composable
    fun Overlay() = overlay()
}

/** Observes taps and long presses without consuming the press, except a long press that [onLongPress] accepted. */
private fun Modifier.pdfPresses(onTap: (Offset) -> Unit, onLongPress: (Offset) -> Boolean): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            // true = tap, false = cancelled (moved, second finger), null = long press timeout.
            val result = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    if (ev.changes.size > 1) return@withTimeoutOrNull false
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                    if ((ch.position - down.position).getDistance() > slop) return@withTimeoutOrNull false
                    if (!ch.pressed) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
            when (result) {
                true -> onTap(down.position)
                null -> if (onLongPress(down.position)) {
                    // Swallow the rest of this press so the canvas does not also treat it as a pan or a long press.
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
                false -> Unit
            }
        }
    }
