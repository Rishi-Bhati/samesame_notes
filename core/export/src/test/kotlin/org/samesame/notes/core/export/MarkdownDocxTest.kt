package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.ooxml.MarkdownDocxExport
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream

class MarkdownDocxTest {

    private val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun renderDocx(markdown: String, title: String = "Test Doc"): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        MarkdownDocxExport.write(markdown, title, out)
        return unzip(out.toByteArray())
    }

    @Test
    fun testTableDocx() {
        val md = """
            # Table Example

            | Header 1 | Header 2 |
            |---|---|
            | Cell 1 | Cell 2 |
        """.trimIndent()

        val parts = renderDocx(md)
        assertThat(parts.keys).containsAtLeast(
            "[Content_Types].xml",
            "_rels/.rels",
            "word/document.xml",
            "word/_rels/document.xml.rels",
            "word/styles.xml",
            "word/numbering.xml",
        )

        val docXml = parseXml(parts.getValue("word/document.xml"))
        val tables = docXml.getElementsByTagNameNS(W, "tbl")
        assertThat(tables.length).isEqualTo(1)

        val docStr = String(parts.getValue("word/document.xml"))
        assertThat(docStr).contains("<w:tbl>")
        assertThat(docStr).contains("<w:shd w:fill=\"F2F2F2\"/>")
        assertThat(docStr).contains("Header 1")
        assertThat(docStr).contains("Cell 1")
    }

    @Test
    fun testCodeBlockDocx() {
        val md = """
            # Code Sample

            ```kotlin
            fun main() = println("Hello")
            ```
        """.trimIndent()

        val parts = renderDocx(md)
        val docStr = String(parts.getValue("word/document.xml"))

        assertThat(docStr).contains("w:ascii=\"Consolas\"")
        assertThat(docStr).contains("<w:shd w:fill=\"F5F5F5\"/>")
        assertThat(docStr).contains("println(&quot;Hello&quot;)")
    }

    @Test
    fun testHeadingsAndTaskListsDocx() {
        val md = """
            # Heading One

            ## Heading Two

            - [ ] Open task
            - [x] Closed task
        """.trimIndent()

        val parts = renderDocx(md)
        val docStr = String(parts.getValue("word/document.xml"))

        assertThat(docStr).contains("<w:pStyle w:val=\"Heading1\"/>")
        assertThat(docStr).contains("<w:pStyle w:val=\"Heading2\"/>")
        assertThat(docStr).contains("☐ ")
        assertThat(docStr).contains("☑ ")
        assertThat(docStr).contains("Open task")
        assertThat(docStr).contains("Closed task")
    }
}
