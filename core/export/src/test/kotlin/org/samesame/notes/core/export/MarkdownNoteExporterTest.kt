package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.MarkdownBody
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import java.io.ByteArrayOutputStream

class MarkdownNoteExporterTest {

    private fun mdSource(markdown: String, title: String = "Test Markdown"): ExportSource {
        val doc = NoteDocument(
            id = "md-doc",
            title = title,
            body = org.samesame.notes.core.model.richtext.RichText(),
            pages = listOf(PageSpec("p1")),
            markdown = MarkdownBody(content = markdown),
        )
        return object : ExportSource {
            override suspend fun document(): NoteDocument = doc
            override suspend fun page(pageId: String): PageContent = PageContent(pageId, emptyList(), 0)
            override suspend fun media(path: String): ByteArray? = null
        }
    }

    @Test
    fun testExportMarkdownVerbatim() = runBlocking {
        val md = "# Title\n\nContent paragraph."
        val source = mdSource(md)
        val exporter = NoteExporter()
        val out = ByteArrayOutputStream()

        val target = exporter.export(source, ExportFormat.MARKDOWN, ExportOptions(), out)
        assertThat(target.extension).isEqualTo("md")
        assertThat(out.toString(Charsets.UTF_8.name())).isEqualTo(md)
    }

    @Test
    fun testExportMarkdownAsHtml() = runBlocking {
        val md = "# Title\n\n- [ ] Task"
        val source = mdSource(md)
        val exporter = NoteExporter()
        val out = ByteArrayOutputStream()

        val target = exporter.export(source, ExportFormat.HTML, ExportOptions(), out)
        assertThat(target.extension).isEqualTo("html")
        val html = out.toString(Charsets.UTF_8.name())
        assertThat(html).contains("<!DOCTYPE html>")
        assertThat(html).contains("<input type=\"checkbox\"")
    }

    @Test
    fun testExportMarkdownAsDocx() = runBlocking {
        val md = "# Heading\n\n| A | B |\n|---|---|\n| 1 | 2 |"
        val source = mdSource(md)
        val exporter = NoteExporter()
        val out = ByteArrayOutputStream()

        val target = exporter.export(source, ExportFormat.DOCX, ExportOptions(), out)
        assertThat(target.extension).isEqualTo("docx")
        val parts = unzip(out.toByteArray())
        assertThat(parts.keys).contains("word/document.xml")
    }

    @Test
    fun testExportMarkdownAsTxt() = runBlocking {
        val md = "# Title\n\nHello **world**"
        val source = mdSource(md)
        val exporter = NoteExporter()
        val out = ByteArrayOutputStream()

        val target = exporter.export(source, ExportFormat.TXT, ExportOptions(), out)
        assertThat(target.extension).isEqualTo("txt")
        val txt = out.toString(Charsets.UTF_8.name())
        assertThat(txt).contains("Title")
        assertThat(txt).contains("Hello world")
        assertThat(txt).doesNotContain("**")
    }
}
