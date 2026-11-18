package org.samesame.notes.core.data.pdf

import com.google.common.truth.Truth.assertThat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
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
import org.samesame.notes.core.model.Limits

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
    @Test fun densePageStopsRetainingGlyphsAndText() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(10f, 750f)
                cs.showText("a".repeat(Limits.PDF_TEXT_MAX_CHARS_PER_PAGE + 1000))
                cs.endText()
            }
            val text = PdfTextExtractor().extractPage(doc, 0)
            assertThat(text.text.length).isAtMost(Limits.PDF_TEXT_MAX_CHARS_PER_PAGE)
            assertThat(text.glyphs.size).isEqualTo(text.text.length)
            assertThat(text.text.length).isGreaterThan(0)
            assertThat(PdfTextExtractor.search(text, "a", 7)).hasSize(7)
            assertThat(PdfTextExtractor.search(text, "a", 0)).isEmpty()
        }
    }

    @Test fun rotatedCropGlyphsAndXyzTargetsUseDisplayedCoordinates() {
        PDDocument().use { doc ->
            val rotations = listOf(0, 90, 180, 270)
            for (rotation in rotations) {
                val page = PDPage(PDRectangle(300f, 400f))
                page.cropBox = PDRectangle(10f, 20f, 200f, 300f)
                page.rotation = rotation
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(30f, 270f); cs.showText("A"); cs.endText()
                }
                page.annotations = listOf(PDAnnotationLink().apply {
                    rectangle = PDRectangle(30f, 260f, 12f, 12f)
                    destination = PDPageXYZDestination().apply { this.page = page; left = 30; top = 270 }
                })
            }
            val pages = rotations.indices.map { PdfTextExtractor().extractPage(doc, it) }
            val base = pages[0].glyphs.first { it.char == 'A' }.boundsPt
            assertThat(base.left).isWithin(0.1f).of(20f)
            assertThat(base.top).isWithin(0.1f).of(38f)
            val expected = listOf(
                base,
                org.samesame.notes.core.model.pdf.RectPt(300f - base.bottom, base.left, 300f - base.top, base.right),
                org.samesame.notes.core.model.pdf.RectPt(200f - base.right, 300f - base.bottom, 200f - base.left, 300f - base.top),
                org.samesame.notes.core.model.pdf.RectPt(base.top, 200f - base.right, base.bottom, 200f - base.left),
            )
            pages.forEachIndexed { i, text ->
                val bounds = text.glyphs.first { it.char == 'A' }.boundsPt
                assertThat(bounds.left).isWithin(0.1f).of(expected[i].left)
                assertThat(bounds.top).isWithin(0.1f).of(expected[i].top)
                assertThat(bounds.right).isWithin(0.1f).of(expected[i].right)
                assertThat(bounds.bottom).isWithin(0.1f).of(expected[i].bottom)
                val target = text.links.single().destination as PdfLinkDestination.Internal
                assertThat(target.targetPageIndex).isEqualTo(i)
                assertThat(target.targetYPt!!).isWithin(0.1f).of(listOf(50f, 20f, 250f, 180f)[i])
                assertThat(PdfTextExtractor.search(text, "A").single().rectsPt).containsExactly(bounds)
            }
        }
    }

    @Test fun ligatureExpansionAndWhitespaceKeepFollowingGlyphAligned() {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val font = RuntimeEnvironment.getApplication().assets.open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf").use { PDType0Font.load(doc, it) }
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(font, 12f); cs.newLineAtOffset(50f, 750f)
                cs.showText("A\uFB01 B"); cs.endText()
            }
            val text = PdfTextExtractor().extractPage(doc, 0)
            assertThat(text.text).contains("Afi B")
            val f = text.glyphs[text.text.indexOf('f')]
            val i = text.glyphs[text.text.indexOf('i')]
            val b = text.glyphs[text.text.indexOf('B')]
            assertThat(f.boundsPt).isEqualTo(i.boundsPt)
            assertThat(f.boundsPt.width).isGreaterThan(0f)
            assertThat(b.boundsPt.left).isGreaterThan(f.boundsPt.right)
            assertThat(b.boundsPt.width).isGreaterThan(0f)
        }
    }

}
