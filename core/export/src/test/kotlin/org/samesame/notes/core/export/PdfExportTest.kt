package org.samesame.notes.core.export

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import org.robolectric.RuntimeEnvironment
import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PageSize
import org.samesame.notes.core.model.document.PdfBackground
import org.samesame.notes.core.model.document.PdfMarkupKind
import org.samesame.notes.core.model.document.PdfTextMarkup
import org.samesame.notes.core.model.document.QuadPu
import org.samesame.notes.core.model.document.RectPu
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * End to end through [NoteExporter]. The platform PDF writer is faked by a shadow that writes a real, blank PdfBox page
 * per started page (so the vector assembly runs for real on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PdfExportTest.ShadowPdfDocument::class])
class PdfExportTest {

    @Implements(PdfDocument::class)
    class ShadowPdfDocument {
        private val sizes = mutableListOf<Pair<Int, Int>>()

        @Implementation
        fun __constructor__() {}

        @Implementation
        fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
            sizes += pageInfo.pageWidth to pageInfo.pageHeight
            val ctor = PdfDocument.Page::class.java.getDeclaredConstructor(Canvas::class.java, PdfDocument.PageInfo::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(Canvas(), pageInfo)
        }

        @Implementation
        fun finishPage(page: PdfDocument.Page) {}

        @Implementation
        fun writeTo(out: OutputStream) {
            PDDocument().use { d ->
                sizes.forEach { (w, h) -> d.addPage(PDPage(PDRectangle(w.toFloat(), h.toFloat()))) }
                d.save(out)
            }
        }

        @Implementation
        fun close() {}
    }

    @Before fun initPdfBox() {
        PDFBoxResourceLoader.init(RuntimeEnvironment.getApplication())
    }

    /** Two pages: A "ORIGINAL TEXT ALPHA" (200 x 300), B "SECOND PAGE BETA" rotated 90 in the file (300 x 200 mediabox). */
    private fun sourcePdf(): ByteArray = PDDocument().use { d ->
        listOf("ORIGINAL TEXT ALPHA" to 0, "SECOND PAGE BETA" to 90).forEach { (text, rot) ->
            val page = PDPage(PDRectangle(200f, 300f))
            page.rotation = rot
            d.addPage(page)
            PDPageContentStream(d, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(20f, 250f)
                cs.showText(text)
                cs.endText()
            }
        }
        ByteArrayOutputStream().also { d.save(it) }.toByteArray()
    }

    private fun source(doc: NoteDocument, media: Map<String, ByteArray>) = object : ExportSource {
        override suspend fun document() = doc
        override suspend fun page(pageId: String) = PageContent(pageId, emptyList(), 0)
        override suspend fun media(path: String): ByteArray? = media[path]
    }

    private fun export(doc: NoteDocument, media: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { NoteExporter(RuntimeEnvironment.getApplication()).export(source(doc, media), ExportFormat.PDF, ExportOptions(), out) }
        return out.toByteArray()
    }

    private val quad = QuadPu.fromRect(RectPu(108f, 108f, 540f, 162f))

    @Test fun `imported PDF pages keep their text and gain real markup annotations`() {
        val doc = NoteDocument(
            id = "n",
            pages = listOf(
                PageSpec(
                    "p1", pdf = PdfBackground("media/src.pdf", 0), size = PageSize(200f, 300f),
                    textMarkup = listOf(PdfTextMarkup("m1", PdfMarkupKind.Highlight, 0xFFFFEB3BL, listOf(quad), "ORIGINAL")),
                ),
                PageSpec(
                    "p2", pdf = PdfBackground("media/src.pdf", 1), size = PageSize(300f, 200f), rotation = 90,
                    textMarkup = listOf(PdfTextMarkup("m2", PdfMarkupKind.Underline, 0xFF2196F3L, listOf(quad))),
                ),
                PageSpec(
                    "p3",
                    textMarkup = listOf(PdfTextMarkup("m3", PdfMarkupKind.Strike, 0xFFF44336L, listOf(quad))),
                ),
            ),
        )
        val bytes = export(doc, mapOf("media/src.pdf" to sourcePdf()))
        PDDocument.load(bytes).use { out ->
            assertThat(out.numberOfPages).isEqualTo(3)
            val stripper = PDFTextStripper()
            stripper.startPage = 1; stripper.endPage = 1
            assertThat(stripper.getText(out)).contains("ORIGINAL TEXT ALPHA")
            stripper.startPage = 2; stripper.endPage = 2
            assertThat(stripper.getText(out)).contains("SECOND PAGE BETA")

            val subtypes = (0 until 3).map { i -> out.getPage(i).annotations.map { it.subtype } }
            assertThat(subtypes[0]).containsExactly(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
            assertThat(subtypes[1]).containsExactly(PDAnnotationTextMarkup.SUB_TYPE_UNDERLINE)
            assertThat(subtypes[2]).containsExactly(PDAnnotationTextMarkup.SUB_TYPE_STRIKEOUT)

            // Page 1 is 200 x 300 pt: note width 1080 pu -> 200 pt. Quad top-left (108,108) pu -> (20, 300-20) pt.
            val h = out.getPage(0).annotations.single() as PDAnnotationTextMarkup
            assertThat(h.contents).isEqualTo("ORIGINAL")
            val q = h.quadPoints
            assertThat(q).hasLength(8)
            assertThat(q[0]).isWithin(0.5f).of(20f)
            assertThat(q[1]).isWithin(0.5f).of(280f)
            assertThat(q[2]).isWithin(0.5f).of(100f)

            // Page 2 is displayed 300 x 200 (file page rotated 90): displayed (30, 30 from the top-left) is user (30, 30).
            val u = out.getPage(1).annotations.single() as PDAnnotationTextMarkup
            assertThat(u.quadPoints[0]).isWithin(0.5f).of(30f)
            assertThat(u.quadPoints[1]).isWithin(0.5f).of(30f)

            // Rotation: the file page was turned 90 and the note turned another 90.
            assertThat(out.getPage(0).rotation).isEqualTo(0)
            assertThat(out.getPage(1).rotation).isEqualTo(180)
            assertThat(out.getPage(2).rotation).isEqualTo(0)
        }
    }

    @Test fun `a note without PDF pages still exports`() {
        val doc = NoteDocument(id = "n", pages = listOf(PageSpec("p1"), PageSpec("p2", rotation = 270)))
        PDDocument.load(export(doc, emptyMap())).use { out ->
            assertThat(out.numberOfPages).isEqualTo(2)
            assertThat(out.getPage(1).rotation).isEqualTo(270)
        }
    }

    @Test fun `inherited fonts survive import and overlay resources`() {
        val bytes = PDDocument().use { d ->
            val page = PDPage(PDRectangle(200f, 300f))
            d.addPage(page)
            PDPageContentStream(d, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(20f, 250f)
                cs.showText("INHERITED FONT")
                cs.endText()
            }
            val parent = page.cosObject.getDictionaryObject(COSName.PARENT) as com.tom_roush.pdfbox.cos.COSDictionary
            parent.setItem(COSName.RESOURCES, page.resources)
            page.cosObject.removeItem(COSName.RESOURCES)
            ByteArrayOutputStream().also { d.save(it) }.toByteArray()
        }
        val doc = NoteDocument(id = "n", pages = listOf(PageSpec("p", pdf = PdfBackground("media/src.pdf", 0))))
        PDDocument.load(export(doc, mapOf("media/src.pdf" to bytes))).use { out ->
            assertThat(PDFTextStripper().getText(out)).contains("INHERITED FONT")
            assertThat(out.getPage(0).cosObject.containsKey(COSName.RESOURCES)).isTrue()
        }
    }
}
