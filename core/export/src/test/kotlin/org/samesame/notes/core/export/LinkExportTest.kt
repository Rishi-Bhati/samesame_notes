package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.ooxml.DocxExport
import org.samesame.notes.core.export.ooxml.PptxExport
import org.samesame.notes.core.export.text.HtmlExport
import org.samesame.notes.core.export.text.MarkdownExport
import org.samesame.notes.core.export.text.TextExport
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ParagraphStyle
import java.io.ByteArrayOutputStream

/** Link runs (CharStyle.link) in every text-like export format. */
class LinkExportTest {
    private val uri = "samesame://file/0b6c1a52-6d6f-4a39-9a3c-3f2f6a7d9e10?n=Plan%20v2.pdf"
    private val body = rich(
        Para(ParagraphStyle(), "See " to PLAIN, "Plan v2" to CharStyle(link = uri), " now" to PLAIN),
    )
    private val doc = sampleDoc(body)

    @Test fun txtWritesLabelThenUri() {
        assertThat(TextExport.render(doc, null)).contains("See Plan v2 <$uri> now")
    }

    @Test fun txtLeavesPlainTextAndUriLabelsAlone() {
        val d = sampleDoc(rich(Para(ParagraphStyle(), "x " to PLAIN, uri to CharStyle(link = uri))))
        assertThat(TextExport.render(d, null).trim()).isEqualTo("My Note\n\nx $uri".trim())
    }

    @Test fun markdownWritesInlineLink() {
        val md = MarkdownExport.render(doc, FakeAssets(), writeMedia = false).markdown
        assertThat(md).contains("See [Plan v2]($uri) now")
    }

    @Test fun htmlWritesAnchor() {
        val html = HtmlExport.render(doc, FakeAssets())
        assertThat(html).contains("<a href=\"$uri\">Plan v2</a>")
    }

    @Test fun docxWritesExternalHyperlinkRelationship() {
        val parts = unzip(ByteArrayOutputStream().also { DocxExport.write(doc, FakeAssets(), it) }.toByteArray())
        for ((name, bytes) in parts) if (name.endsWith(".xml") || name.endsWith(".rels")) parseXml(bytes)
        val rels = String(parts.getValue("word/_rels/document.xml.rels"))
        assertThat(rels).contains("relationships/hyperlink\" Target=\"$uri\" TargetMode=\"External\"")
        val xml = String(parts.getValue("word/document.xml"))
        assertThat(xml).contains("<w:hyperlink r:id=\"rIdLink1\"")
        assertThat(xml).contains("Plan v2")
    }

    @Test fun pptxHyperlinksTheLabelInThePageText() {
        val a = object : ExportAssets by FakeAssets() { override fun pageText(pageIndex: Int) = "See Plan v2 now" }
        val parts = unzip(ByteArrayOutputStream().also { PptxExport.write(doc, a, it) }.toByteArray())
        for ((name, bytes) in parts) if (name.endsWith(".xml") || name.endsWith(".rels")) parseXml(bytes)
        assertThat(String(parts.getValue("ppt/slides/_rels/slide1.xml.rels")))
            .contains("relationships/hyperlink\" Target=\"$uri\" TargetMode=\"External\"")
        val slide = String(parts.getValue("ppt/slides/slide1.xml"))
        assertThat(slide).contains("<a:hlinkClick r:id=\"rIdLink1\"/></a:rPr><a:t>Plan v2</a:t>")
        assertThat(slide).contains("<a:t>See </a:t>")
    }

    @Test fun noLinkMeansUnchangedOutput() {
        val plain = sampleDoc()
        assertThat(HtmlExport.render(plain, FakeAssets())).doesNotContain("<a href")
        assertThat(MarkdownExport.render(plain, FakeAssets(), false).markdown).doesNotContain("](")
    }
}
