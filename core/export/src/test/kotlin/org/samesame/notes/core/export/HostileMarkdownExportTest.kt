package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.ooxml.DocxExport
import org.samesame.notes.core.export.ooxml.MarkdownDocxExport
import org.samesame.notes.core.export.text.HtmlExport
import org.samesame.notes.core.export.text.MarkdownConverter
import org.samesame.notes.core.export.text.MarkdownHtmlExport
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ParagraphStyle
import java.io.ByteArrayOutputStream

/** Markdown and link targets from a file someone else made: nothing may crash an export or survive as a script link. */
class HostileMarkdownExportTest {

    private val deepQuotes = "> ".repeat(200_000) + "deep\n"
    private val deepEmphasis = "*".repeat(100_000) + "x" + "*".repeat(100_000)

    @Test fun deepNestingDoesNotOverflowTheHtmlExport() {
        for (md in listOf(deepQuotes, deepEmphasis)) {
            val html = MarkdownHtmlExport.render(md, "Deep")
            assertThat(html).contains("</html>")
        }
    }

    @Test fun deepNestingDoesNotOverflowTheWordExport() {
        for (md in listOf(deepQuotes, deepEmphasis)) {
            val parts = unzip(ByteArrayOutputStream().also { MarkdownDocxExport.write(md, "Deep", it) }.toByteArray())
            parseXml(parts.getValue("word/document.xml"))
        }
    }

    @Test fun deepNestingDoesNotOverflowTheConverter() {
        MarkdownConverter.markdownToPlainText(deepQuotes)
        MarkdownConverter.markdownToRichText(deepEmphasis)
    }

    @Test fun ordinaryNestingIsKept() {
        val html = MarkdownHtmlExport.render("> > > three\n", "T")
        assertThat(html.split("<blockquote>").size - 1).isEqualTo(3)
    }

    @Test fun wordExportLinksOnlySafeSchemes() {
        val md = "[web](https://example.com) [unc](file://attacker/share) [js](javascript:alert(1))"
        val parts = unzip(ByteArrayOutputStream().also { MarkdownDocxExport.write(md, "", it) }.toByteArray())
        val rels = String(parts.getValue("word/_rels/document.xml.rels"))
        assertThat(rels).contains("Target=\"https://example.com\"")
        assertThat(rels).doesNotContain("file://")
        assertThat(rels).doesNotContain("javascript:")
        val xml = String(parts.getValue("word/document.xml"))
        assertThat(xml).contains("unc")
        assertThat(xml).contains("js")
    }

    @Test fun richTextExportsDropScriptLinks() {
        val doc = sampleDoc(
            rich(Para(ParagraphStyle(), "click" to CharStyle(link = "javascript:alert(1)"), " ok" to CharStyle(link = "https://example.com"))),
        )
        val html = HtmlExport.render(doc, FakeAssets())
        assertThat(html).doesNotContain("javascript:")
        assertThat(html).contains("click")
        assertThat(html).contains("<a href=\"https://example.com\">")

        val parts = unzip(ByteArrayOutputStream().also { DocxExport.write(doc, FakeAssets(), it) }.toByteArray())
        assertThat(String(parts.getValue("word/_rels/document.xml.rels"))).doesNotContain("javascript:")
    }
}
