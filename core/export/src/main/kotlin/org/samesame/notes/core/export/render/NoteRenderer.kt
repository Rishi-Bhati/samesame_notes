package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import org.samesame.notes.core.export.ExportSource
import org.samesame.notes.core.export.text.sniffImageMime
import org.samesame.notes.core.ink.DarkModeInk
import org.samesame.notes.core.ink.brush.GrainTextureStore
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.bitmapMedia
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.document.TextBoxBorder
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.document.TextWrap
import java.io.ByteArrayOutputStream

/** Which layers of a page to draw. */
internal data class Layers(
    val background: Boolean = true,
    val template: Boolean = true,
    val text: Boolean = true,
    /** Strokes. */
    val ink: Boolean = true,
    val images: Boolean = true,
    val textBoxes: Boolean = true,
)

/** A fully loaded note: document, page layers and media bytes. */
internal class LoadedNote(
    val doc: NoteDocument,
    val pages: Map<String, PageContent>,
    val media: Map<String, ByteArray>,
)

internal suspend fun loadNote(source: ExportSource): LoadedNote {
    val doc = source.document()
    val pages = LinkedHashMap<String, PageContent>()
    val paths = LinkedHashSet<String>()
    doc.inlineObjects.filterIsInstance<ImageObject>().forEach { paths += it.media }
    for (spec in doc.pages) {
        val content = source.page(spec.id)
        pages[spec.id] = content
        (spec.template as? TemplateRef.Image)?.let { paths += it.media }
        for (e in content.elements) {
            if (e is ObjectElement) parseFloating(e)?.obj?.bitmapMedia?.let { paths += it }
        }
    }
    val media = LinkedHashMap<String, ByteArray>()
    for (p in paths) source.media(p)?.let { media[p] = it }
    return LoadedNote(doc, pages, media)
}

internal fun parseFloating(e: ObjectElement): FloatingObject? = runCatching { FloatingObject.fromJson(e.json) }.getOrNull()

/** the reference list card dark background (#171717 = noteslist_card_view_bg_color_black). Used for the
 *  page fill of dark previews that have no stored custom colour so the preview blends with the card. */
internal val DARK_PREVIEW_PAGE_ARGB: Int = 0xFF171717.toInt()

internal fun isDefaultBackground(argb: Long?): Boolean =
    argb == null ||
        argb == 0L ||
        argb == -1L ||
        (argb and 0xFFFFFFL) == 0xFCFCFCL ||
        (argb and 0xFFFFFFL) == 0xFFFFFFL

/**
 * Resolves the ARGB integer to paint as the page background for a preview.
 *
 * - No stored colour + dark theme → [DARK_PREVIEW_PAGE_ARGB] (#171717, the reference card grey).
 * - Stored custom colour → [colorTransform] applied (existing invert path).
 * - No stored colour + light theme → [android.graphics.Color.WHITE].
 */
internal fun previewPageColor(storedArgb: Long?, darkTheme: Boolean, colorTransform: (Int) -> Int): Int {
    val isDefault = isDefaultBackground(storedArgb)
    return when {
        isDefault && darkTheme -> DARK_PREVIEW_PAGE_ARGB
        !isDefault && storedArgb != null -> colorTransform(storedArgb.toInt() or (0xFF shl 24))
        else -> android.graphics.Color.WHITE
    }
}

/** Draws the pages of one loaded note. Not thread-safe (shares a layout and a stroke renderer). */
internal class NoteRenderer(val note: LoadedNote, private val darkTheme: Boolean = false) {
    val doc = note.doc
    private val bitmaps = HashMap<String, Bitmap?>()
    private val normalisedMedia = HashMap<String, ByteArray?>()
    private val strokeRenderer by lazy { CanvasStrokeRenderer.create(GrainTextureStore) }

    /** Dark previews reuse the composer's HSL-lightness inversion; images and image templates stay untouched. */
    private val colorTransform: (Int) -> Int = if (darkTheme) { c -> DarkModeInk.invert(c) } else { c -> c }

    val layout = NoteLayout(doc, { bitmap(it) }, colorTransform)
    val pageW get() = layout.pageW
    /** Height of the first page (uniform notes: of every page). Prefer [pageH] with an index. */
    val pageH get() = layout.pageHeight(0)

    /** Height of page [i] in pu (pages keep their own size, format v6). */
    fun pageH(i: Int): Float = layout.pageHeight(i)

    private val floating: List<List<Pair<ObjectElement, FloatingObject>>> = doc.pages.map { spec ->
        note.pages[spec.id]?.elements.orEmpty().filterIsInstance<ObjectElement>().mapNotNull { e -> parseFloating(e)?.let { e to it } }
    }

    /** Rendered pages: spec pages or more when the text overflows; infinite notes lose trailing blank segments. */
    val pageCount: Int = run {
        var n = maxOf(doc.pages.size, layout.textPageCount, 1)
        if (layout.infinite) while (n > 1 && isBlank(n - 1)) n--
        n
    }

    fun bitmap(path: String): Bitmap? = bitmaps.getOrPut(path) {
        note.media[path]?.let { org.samesame.notes.core.ink.media.BoundedBitmaps.decode(it) }
    }

    /** Media bytes as PNG/JPEG/GIF (others, e.g. WebP/HEIC, are re-encoded as PNG). */
    fun media(path: String): ByteArray? = normalisedMedia.getOrPut(path) {
        val raw = note.media[path] ?: return@getOrPut null
        if (sniffImageMime(raw) != null) raw
        else bitmap(path)?.let { b -> ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray() }
    }

    private fun spec(i: Int): PageSpec? = doc.pages.getOrNull(i)
    private fun content(i: Int): PageContent? = spec(i)?.let { note.pages[it.id] }

    private fun isBlank(i: Int): Boolean =
        (content(i)?.elements?.isEmpty() ?: true) && !layout.hasText(i)

    fun hasInkLayer(i: Int): Boolean =
        content(i)?.elements.orEmpty().any { it is InkElement } ||
            floating.getOrNull(i).orEmpty().any { it.second.obj.bitmapMedia != null }

    fun floatingTexts(i: Int) = floating.getOrNull(i).orEmpty().mapNotNull { (it.second.obj as? TextBoxObject)?.text }

    fun pageText(i: Int): String =
        (listOf(layout.pageText(i)) + floatingTexts(i).map { it.plainText() }).filter { it.isNotBlank() }.joinToString("\n")

    /** Draws page [i] in page units (0..1080 x 0..pageH); the caller sets up scale and clipping. */
    fun drawPage(canvas: Canvas, i: Int, layers: Layers = Layers()) {
        val spec = spec(i)
        val pageH = pageH(i)
        if (layers.background) {
            val argb = spec?.backgroundColor ?: doc.style.backgroundColor
            canvas.drawColor(previewPageColor(argb, darkTheme, colorTransform))
        }
        if (layers.template && spec != null) {
            when (val t = spec.template) {
                is TemplateRef.Builtin -> TemplateArt.draw(canvas, t.template, pageW, pageH, colorTransform)
                is TemplateRef.Image -> bitmap(t.media)?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, pageW, pageH), Paint(Paint.FILTER_BITMAP_FLAG)) }
            }
        }
        val objects = floating.getOrNull(i).orEmpty()
        for ((_, f) in objects) if (f.wrap == TextWrap.Behind) drawObject(canvas, f, layers)
        if (layers.text) layout.drawText(canvas, i)
        val elements = content(i)?.elements.orEmpty()
        for (e in elements) {
            when (e) {
                is InkElement -> if (layers.ink) {
                    val stroke = if (darkTheme) DarkModeInk.invert(e.stroke) else e.stroke
                    strokeRenderer.draw(canvas, stroke, AffineTransform.IDENTITY)
                }
                is ObjectElement -> floating.getOrNull(i)?.firstOrNull { it.first === e }?.second
                    ?.takeIf { it.wrap == TextWrap.InFront }?.let { drawObject(canvas, it, layers) }
            }
        }
    }

    private fun drawObject(canvas: Canvas, f: FloatingObject, layers: Layers) {
        val r = f.rect
        canvas.save()
        if (f.rotationDeg != 0f) canvas.rotate(f.rotationDeg, (r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
        when (val o = f.obj) {
            is ImageObject -> if (layers.images) bitmap(o.media)?.let {
                canvas.drawBitmap(it, null, RectF(r.left, r.top, r.right, r.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            // A drawing is drawn from its thumbnail, like an image.
            is PaintingObject -> if (layers.images) bitmap(o.media)?.let {
                canvas.drawBitmap(it, null, RectF(r.left, r.top, r.right, r.bottom), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            is TextBoxObject -> if (layers.textBoxes) {
                if (o.border == TextBoxBorder.Solid) {
                    canvas.drawRect(r.left, r.top, r.right, r.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = 2f; color = colorTransform(Color.BLACK)
                    })
                }
                val pad = 12f
                val w = (r.width - 2 * pad).toInt().coerceAtLeast(1)
                val tl = layoutText(o.text, w, { bitmap(it) }, doc.inlineObjects.filterIsInstance<ImageObject>().associateBy { it.id }, colorTransform)
                canvas.clipRect(r.left, r.top, r.right, r.bottom)
                canvas.translate(r.left + pad, r.top + pad)
                tl.draw(canvas)
            }
            is VoiceObject -> if (layers.textBoxes) {
                // The audio itself isn't in a page image; the pill shows that a recording sits here.
                val h = r.height
                canvas.drawRoundRect(RectF(r.left, r.top, r.right, r.bottom), h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorTransform(0xFFEDEDED.toInt()) })
                val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorTransform(0xFF303030.toInt()); textSize = h * 0.36f }
                val cx = r.left + h / 2f; val tr = h * 0.2f
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(cx - tr * 0.7f, r.top + h / 2f - tr); lineTo(cx + tr, r.top + h / 2f); lineTo(cx - tr * 0.7f, r.top + h / 2f + tr); close()
                }, ink)
                val sec = o.durationMs.coerceAtLeast(0) / 1000
                canvas.drawText("%d:%02d".format(sec / 60, sec % 60), r.left + h, r.top + h / 2f + ink.textSize / 3f, ink)
            }
            else -> Unit
        }
        canvas.restore()
    }

    /** Renders page [i] to a bitmap at [scale] pixels per page unit. */
    fun renderBitmap(i: Int, scale: Float, layers: Layers): Bitmap {
        val bmp = Bitmap.createBitmap(
            (pageW * scale).toInt().coerceAtLeast(1), (pageH(i) * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.scale(scale, scale)
        drawPage(c, i, layers)
        return bmp
    }
}
