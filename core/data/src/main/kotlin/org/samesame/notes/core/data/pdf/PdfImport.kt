package org.samesame.notes.core.data.pdf

import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSize
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PdfBackground
import org.samesame.notes.core.model.document.TemplateRef
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class PdfImportError { PasswordProtected, Corrupt, Empty, TooManyPages }

class PdfImportException(val reason: PdfImportError, cause: Throwable? = null) : RuntimeException(reason.name, cause)

/** The pages of one PDF as the importer sees them; the Android implementation wraps `PdfRenderer`. */
interface PdfPageSource : AutoCloseable {
    val pageCount: Int
    /** Page size in PDF points as displayed (the page's /Rotate applied, so a rotated portrait page is landscape). */
    fun pageSize(index: Int): Pair<Float, Float>
    /** Renders the page, scaled to fit and centred on a white [widthPx] x [heightPx] canvas, as PNG. */
    fun renderPng(index: Int, widthPx: Int, heightPx: Int): ByteArray
}

/** One page made from a PDF page: its rendered background image, where it came from and its own size (format v6). */
data class ImportedPdfPage(val media: String, val pdf: PdfBackground?, val size: PageSize)

/** Pure parts of "Import PDF": the shape of each page, the render sizes and what the note document looks like. */
object PdfNoteBuilder {
    /** Rendered width of a page image (2x the 1080 pu page width); the height follows the page's own aspect. */
    const val RENDER_WIDTH_PX = 2160

    /** Largest page image in pixels (about 36 MB while it is rendered); taller pages are rendered narrower. */
    const val MAX_RENDER_PIXELS = RENDER_WIDTH_PX * RENDER_WIDTH_PX * 2

    const val MAX_PAGES = 300

    /**
     * The note's global ratio (used for pages appended later without a size of their own and by consumers that need
     * one shape, e.g. the slide size of a .pptx). It follows the first page of the first PDF.
     */
    fun ratioFor(widthPt: Float, heightPt: Float): PageRatio {
        val r = heightPt / widthPt
        return when {
            r >= 1.8f -> PageRatio.Long
            r >= 1f -> PageRatio.A4Portrait
            else -> PageRatio.A4Landscape
        }
    }

    /** The size a page keeps in the note: its own size in points, or A4 when the file reports nonsense. */
    fun pageSize(widthPt: Float, heightPt: Float): PageSize =
        PageSize(widthPt, heightPt).takeIf { it.isValid } ?: PageSize(PageSize.A4_WIDTH_PT, PageSize.A4_HEIGHT_PT)

    /**
     * Pixel size of the background image of a page of [size]: [RENDER_WIDTH_PX] wide, as tall as the page's (clamped)
     * aspect, scaled down uniformly to at most [MAX_RENDER_PIXELS].
     */
    fun renderSize(size: PageSize): Pair<Int, Int> {
        val ratio = if (size.isValid) size.heightOverWidth else PageRatio.A4Portrait.heightOverWidth
        var w = RENDER_WIDTH_PX.toFloat()
        var h = w * ratio
        if (w * h > MAX_RENDER_PIXELS) {
            val k = sqrt(MAX_RENDER_PIXELS / (w * h))
            w *= k
            h *= k
        }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /** Scale and offset that fit a [srcW] x [srcH] page inside [dstW] x [dstH], centred. Returns (scale, dx, dy). */
    fun fit(srcW: Float, srcH: Float, dstW: Float, dstH: Float): Triple<Float, Float, Float> {
        val s = minOf(dstW / srcW, dstH / srcH)
        return Triple(s, (dstW - srcW * s) / 2f, (dstH - srcH * s) / 2f)
    }

    /** Throws [PdfImportException] when a PDF has no pages or more than [MAX_PAGES] in total. */
    fun requirePages(total: Int) {
        if (total == 0) throw PdfImportException(PdfImportError.Empty)
        if (total > MAX_PAGES) throw PdfImportException(PdfImportError.TooManyPages)
    }

    /** One new page per imported page, in order, with fresh ids, its image as background and its own size. */
    fun backgroundPages(pages: List<ImportedPdfPage>): List<PageSpec> = pages.map {
        PageSpec(id = UUID.randomUUID().toString(), template = TemplateRef.Image(it.media), pdf = it.pdf, size = it.size)
    }

    /** A paged note made of [pages]; its global ratio follows the first page. */
    fun document(id: String, title: String, pages: List<ImportedPdfPage>): NoteDocument {
        val first = pages.firstOrNull()?.size
        val ratio = first?.let { ratioFor(it.widthPt, it.heightPt) } ?: PageRatio.A4Portrait
        return NoteDocument(
            id = id,
            title = title,
            layout = PageLayout(mode = PageMode.Pages, ratio = ratio),
            pages = backgroundPages(pages),
        )
    }

    /**
     * Renders every page of [sources] in order, one page at a time, each at its own shape, and stores each image with
     * [store] (png bytes -> media path). [sourceMedia]`[i]` is where the bytes of `sources[i]` are kept in the note (null
     * = not kept); each page records it with its page index so export can use the original page.
     */
    fun renderAll(
        sources: List<PdfPageSource>,
        sourceMedia: List<String?> = emptyList(),
        fitToFirstPage: Boolean = false,
        store: (ByteArray) -> String,
    ): List<ImportedPdfPage> {
        val total = sources.sumOf { it.pageCount }
        requirePages(total)
        val out = ArrayList<ImportedPdfPage>(total)
        // "Fit to page layout": every page takes the first page's shape (letterboxed on white) and is a plain image page.
        var uniform: PageSize? = null
        sources.forEachIndexed { s, source ->
            val media = sourceMedia.getOrNull(s)
            for (i in 0 until source.pageCount) {
                val (wPt, hPt) = source.pageSize(i)
                val own = pageSize(wPt, hPt)
                if (fitToFirstPage && uniform == null) uniform = own
                val size = if (fitToFirstPage) uniform!! else own
                val (w, h) = renderSize(size)
                val path = store(source.renderPng(i, w, h))
                out += ImportedPdfPage(path, if (fitToFirstPage) null else media?.let { PdfBackground(it, i) }, size)
            }
        }
        return out
    }
}
