package org.samesame.notes.core.export.render

import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.util.Matrix
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PdfMarkupKind
import org.samesame.notes.core.model.document.PdfTextMarkup
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeoutException

/**
 * Real PDF export. Pages that came from a PDF keep their original page (content stream, fonts, images, so vectors and
 * selectable text survive); ink, text boxes and images are drawn on top as a vector form; text markups become genuine
 * highlight / underline / strike-out annotations; [org.samesame.notes.core.model.document.PageSpec.rotation] becomes the
 * page's /Rotate. Notes without PDF pages go through the same path (their page is imported from the platform writer).
 *
 * Throws on anything unexpected: [PdfExport.write] then falls back to the raster export, and the output file is only
 * copied to the caller's stream after a complete save, so a failure never leaves half a PDF behind.
 */
internal object PdfVectorExport {
    private fun scratch() = MemoryUsageSetting.setupMixed(Limits.PDF_SCRATCH_MEMORY_BYTES)

    fun write(renderer: NoteRenderer, outFile: File) {
        val started = System.nanoTime()
        fun checkTime() {
            if ((System.nanoTime() - started) / 1_000_000 > Limits.PDF_VECTOR_EXPORT_TIMEOUT_MS) throw TimeoutException("vector PDF export")
        }
        val overlayFile = File.createTempFile("pdf-overlay", ".pdf")
        try {
            PDDocument(scratch()).use { target ->
                for (i in 0 until renderer.pageCount) {
                    checkTime()
                    val spec = renderer.note.doc.pages.getOrNull(i)
                    val noteH = renderer.unrotatedPageH(i)
                    val ref = spec?.pdf
                    val source = ref?.let {
                        val file = renderer.note.pdfMedia[it.media] ?: throw IOException("Missing PDF source: ${it.media}")
                        PDDocument.load(file, scratch())
                    }
                    source.use { document ->
                        val src = document?.getPage(ref!!.pageIndex)
                        writeOverlay(renderer, i, src, overlayFile)
                        PDDocument.load(overlayFile, scratch()).use { overlay ->
                            assemblePage(target, spec, src, noteH, overlay)
                        }
                    }
                }
                target.save(outFile)
            }
        } finally {
            overlayFile.delete()
        }
    }

    private fun writeOverlay(renderer: NoteRenderer, i: Int, src: PDPage?, file: File) {
        val spec = renderer.note.doc.pages.getOrNull(i)
        val noteH = renderer.unrotatedPageH(i)
        val overlay = PdfDocument()
        try {
            if (src != null) {
                val crop = src.cropBox
                val sideways = src.rotation % 180 != 0
                val srcW = if (sideways) crop.height else crop.width
                val srcH = if (sideways) crop.width else crop.height
                val (pw, ph) = PdfPageGeometry.pageBox(srcW, srcH)
                val page = overlay.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
                val c = page.canvas
                c.clipRect(0f, 0f, pw.toFloat(), ph.toFloat())
                val t = PdfPageGeometry.fit(srcW, srcH, Limits.PAGE_WIDTH_PU, noteH)
                c.scale(pw / srcW, ph / srcH)
                c.scale(1f / t.scale, 1f / t.scale)
                c.translate(-t.dx, -t.dy)
                renderer.drawPage(c, i, Layers(background = false, template = false))
                overlay.finishPage(page)
            } else {
                val (pw, ph) = PdfPageGeometry.nativePageBox(spec?.size, noteH / renderer.pageW)
                val page = overlay.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
                val c = page.canvas
                c.clipRect(0f, 0f, pw.toFloat(), ph.toFloat())
                c.scale(PdfPageGeometry.pointsPerPu(pw), ph / noteH)
                c.clipRect(0f, 0f, renderer.pageW, noteH)
                renderer.drawPage(c, i)
                overlay.finishPage(page)
            }
            file.outputStream().use { overlay.writeTo(it) }
        } finally { overlay.close() }
    }

    private fun assemblePage(target: PDDocument, spec: PageSpec?, src: PDPage?, noteH: Float, overlayDoc: PDDocument) {
        val layers = LayerUtility(target)
        val overlayPage = overlayDoc.getPage(0)
        val specRotation = spec?.normalizedRotation ?: 0
        val toUser: FloatArray
        val mapPu: (Float, Float) -> Pair<Float, Float> // page units -> displayed points, y down
        val page: PDPage
        if (src != null) {
            val crop = src.cropBox
            val rot = src.rotation
            val sideways = rot % 180 != 0
            val srcW = if (sideways) crop.height else crop.width
            val srcH = if (sideways) crop.width else crop.height
            page = target.importPage(src)
            // importPage omits resources inherited from the source page tree.
            src.resources?.let { resources ->
                page.resources = PDResources(PDFCloneUtility(target).cloneForNewDocument(resources.cosObject) as COSDictionary)
            }
            stripSourceLinks(page)
            page.cosObject.getDictionaryObject(COSName.ANNOTS)?.let { annotations ->
                page.cosObject.setItem(COSName.ANNOTS, PDFCloneUtility(target).cloneForNewDocument(annotations))
            }
            toUser = PdfPageGeometry.displayedToUser(rot, crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height)
            val form = layers.importPageAsForm(overlayDoc, 0)
            val (pw, ph) = PdfPageGeometry.pageBox(srcW, srcH)
            PDPageContentStream(target, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState()
                // The overlay page is srcW x srcH with its origin at the displayed bottom-left.
                cs.transform(Matrix(toUser[0], toUser[1], toUser[2], toUser[3], toUser[4], toUser[5]))
                cs.transform(Matrix.getScaleInstance(srcW / pw, srcH / ph)) // whole-point overlay page vs exact size
                cs.drawForm(form)
                cs.restoreGraphicsState()
            }
            val t = PdfPageGeometry.fit(srcW, srcH, Limits.PAGE_WIDTH_PU, noteH)
            mapPu = { x, y -> PdfPageGeometry.toPdfX(x, t) to (srcH - PdfPageGeometry.toPdfY(y, t)) }
            page.rotation = (rot + specRotation) % 360
        } else {
            page = target.importPage(overlayPage)
            overlayPage.resources?.let { resources ->
                page.resources = PDResources(PDFCloneUtility(target).cloneForNewDocument(resources.cosObject) as COSDictionary)
            }
            val box = page.mediaBox
            toUser = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
            mapPu = { x, y -> x * box.width / Limits.PAGE_WIDTH_PU to box.height - y * box.height / noteH }
            page.rotation = specRotation
        }
        val extra = spec?.textMarkup.orEmpty()
            .take(Limits.MAX_MARKUPS_PER_PAGE)
            .map { annotation(it, toUser, mapPu) }
        if (extra.isNotEmpty()) {
            val list = page.annotations
            list.addAll(extra)
            page.annotations = list
        }
    }

    private fun annotation(m: PdfTextMarkup, toUser: FloatArray, mapPu: (Float, Float) -> Pair<Float, Float>): PDAnnotation {
        val subtype = when (m.kind) {
            PdfMarkupKind.Highlight -> PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
            PdfMarkupKind.Underline -> PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE
            PdfMarkupKind.Strike -> PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT
        }
        val points = ArrayList<Float>(m.quads.size * 8)
        fun add(x: Float, y: Float) {
            val (dx, dy) = mapPu(x, y)
            val (ux, uy) = PdfPageGeometry.apply(toUser, dx, dy)
            points += ux
            points += uy
        }
        for (q in m.quads.take(Limits.MAX_QUADS_PER_MARKUP)) {
            add(q.x1, q.y1); add(q.x2, q.y2); add(q.x3, q.y3); add(q.x4, q.y4)
        }
        val a = PDAnnotationTextMarkup(subtype)
        a.quadPoints = points.toFloatArray()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in points.indices step 2) {
            minX = minOf(minX, points[k]); maxX = maxOf(maxX, points[k])
            minY = minOf(minY, points[k + 1]); maxY = maxOf(maxY, points[k + 1])
        }
        a.rectangle = PDRectangle(minX, minY, maxX - minX, maxY - minY)
        val r = ((m.color shr 16) and 0xFF) / 255f
        val g = ((m.color shr 8) and 0xFF) / 255f
        val b = (m.color and 0xFF) / 255f
        a.color = PDColor(floatArrayOf(r, g, b), PDDeviceRGB.INSTANCE)
        val alpha = ((m.color shr 24) and 0xFF) / 255f
        if (alpha in 0.01f..0.99f) a.constantOpacity = alpha
        if (m.text.isNotEmpty()) a.contents = m.text.take(Limits.MAX_MARKUP_TEXT_LENGTH)
        a.isPrinted = true
        runCatching { a.constructAppearances() } // viewers without their own renderer still show it
        return a
    }

    /**
     * An imported page shares its dictionary with the source document. Anything that points back into the source (internal
     * links, the /P page reference of an annotation, article beads, page actions) would drag the whole source page tree into
     * the output, so those are dropped; web links and every other annotation stay.
     */
    private fun stripSourceLinks(page: PDPage) {
        val dict = page.cosObject
        dict.removeItem(COSName.getPDFName("B"))
        dict.removeItem(COSName.getPDFName("AA"))
        val kept = page.annotations.filter { a ->
            if (a is PDAnnotationLink) runCatching { a.action is PDActionURI }.getOrDefault(false) else true
        }
        kept.forEach { it.cosObject.removeItem(COSName.P) }
        page.annotations = kept
    }
}
