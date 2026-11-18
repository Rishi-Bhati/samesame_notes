package org.samesame.notes.core.data.pdf

import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.samesame.notes.core.model.pdf.PdfLinkDestination

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfTextExtractorTest {

    @Before
    fun setUp() {
        PdfTextExtractor.init(RuntimeEnvironment.getApplication())
    }

    private fun createTestPdf(): PDDocument {
        val doc = PDDocument()

        // Page 0
        val page0 = PDPage(PDRectangle.A4)
        doc.addPage(page0)

        PDPageContentStream(doc, page0).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(50f, 750f)
            stream.showText("Hello World!")
            stream.newLineAtOffset(0f, -20f)
            stream.showText("Second line with words.")
            stream.endText()
        }

        // Add external link to page 0
        val linkExt = PDAnnotationLink().apply {
            rectangle = PDRectangle(50f, 740f, 100f, 20f)
            action = PDActionURI().apply { uri = "https://example.com" }
        }

        // Page 1
        val page1 = PDPage(PDRectangle.A4)
        doc.addPage(page1)

        PDPageContentStream(doc, page1).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(50f, 750f)
            stream.showText("Page Two content.")
            stream.endText()
        }

        // Add internal link to page 0 pointing to page 1
        val linkInt = PDAnnotationLink().apply {
            rectangle = PDRectangle(50f, 710f, 100f, 20f)
            val dest = PDPageXYZDestination().apply {
                page = page1
                top = 500
            }
            action = PDActionGoTo().apply { destination = dest }
        }

        page0.annotations = listOf(linkExt, linkInt)

        // Document Outline
        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline

        val item1 = PDOutlineItem().apply {
            title = "Chapter 1"
            destination = PDPageXYZDestination().apply { page = page0 }
        }
        val subItem1 = PDOutlineItem().apply {
            title = "Section 1.1"
            destination = PDPageXYZDestination().apply { page = page0 }
        }
        item1.addLast(subItem1)
        outline.addLast(item1)

        val item2 = PDOutlineItem().apply {
            title = "Chapter 2"
            destination = PDPageXYZDestination().apply { page = page1 }
        }
        outline.addLast(item2)

        return doc
    }

    @Test
    fun extractPageExtractsTextGlyphsWordsAndLines() {
        val doc = createTestPdf()
        val extractor = PdfTextExtractor()

        val pageText = extractor.extractPage(doc, 0)
        assertThat(pageText.pageIndex).isEqualTo(0)
        assertThat(pageText.text).contains("Hello World!")
        assertThat(pageText.text).contains("Second line with words.")

        // Every character in text must have a corresponding glyph with matching index
        assertThat(pageText.glyphs).hasSize(pageText.text.length)
        pageText.glyphs.forEachIndexed { i, glyph ->
            assertThat(glyph.index).isEqualTo(i)
            assertThat(glyph.char).isEqualTo(pageText.text[i])
        }

        // Words
        val words = pageText.lines.flatMap { it.words }
        val wordTexts = words.map { it.text }
        assertThat(wordTexts).contains("Hello")
        assertThat(wordTexts).contains("World!")
        assertThat(wordTexts).contains("Second")
        assertThat(wordTexts).contains("line")

        // Words bounds should be non-empty for non-whitespace words
        for (w in words) {
            assertThat(w.boundsPt.width).isGreaterThan(0f)
            assertThat(w.boundsPt.height).isGreaterThan(0f)
            assertThat(pageText.text.substring(w.startIndex, w.endIndex)).isEqualTo(w.text)
        }

        // Lines
        assertThat(pageText.lines.size).isAtLeast(2)
        val line0 = pageText.lines[0]
        assertThat(line0.text).contains("Hello")

        // Links
        assertThat(pageText.links).hasSize(2)
        val extLink = pageText.links.find { it.destination is PdfLinkDestination.External }
        assertThat(extLink).isNotNull()
        assertThat((extLink!!.destination as PdfLinkDestination.External).uri).isEqualTo("https://example.com")

        val intLink = pageText.links.find { it.destination is PdfLinkDestination.Internal }
        assertThat(intLink).isNotNull()
        assertThat((intLink!!.destination as PdfLinkDestination.Internal).targetPageIndex).isEqualTo(1)

        doc.close()
    }

    @Test
    fun extractOutlineExtractsHierarchyCorrectly() {
        val doc = createTestPdf()
        val extractor = PdfTextExtractor()

        val outline = extractor.extractOutline(doc)
        assertThat(outline).hasSize(2)

        val ch1 = outline[0]
        assertThat(ch1.title).isEqualTo("Chapter 1")
        assertThat(ch1.pageIndex).isEqualTo(0)
        assertThat(ch1.children).hasSize(1)

        val sec1 = ch1.children[0]
        assertThat(sec1.title).isEqualTo("Section 1.1")
        assertThat(sec1.pageIndex).isEqualTo(0)
        assertThat(sec1.depth).isEqualTo(1)

        val ch2 = outline[1]
        assertThat(ch2.title).isEqualTo("Chapter 2")
        assertThat(ch2.pageIndex).isEqualTo(1)

        doc.close()
    }

    @Test
    fun searchFindsOccurrencesAndComputesRects() {
        val doc = createTestPdf()
        val extractor = PdfTextExtractor()

        val pageText = extractor.extractPage(doc, 0)
        val matches = PdfTextExtractor.search(pageText, "World")
        assertThat(matches).hasSize(1)

        val match = matches[0]
        assertThat(match.pageIndex).isEqualTo(0)
        assertThat(pageText.text.substring(match.start, match.endExclusive)).isEqualTo("World")
        assertThat(match.rectsPt).isNotEmpty()
        assertThat(match.rectsPt[0].width).isGreaterThan(0f)

        doc.close()
    }

    @Test
    fun invalidPageIndexReturnsEmpty() {
        val doc = createTestPdf()
        val extractor = PdfTextExtractor()

        val pageText = extractor.extractPage(doc, 99)
        assertThat(pageText.pageIndex).isEqualTo(99)
        assertThat(pageText.text).isEmpty()
        assertThat(pageText.glyphs).isEmpty()
        assertThat(pageText.lines).isEmpty()
        assertThat(pageText.links).isEmpty()

        doc.close()
    }
}
