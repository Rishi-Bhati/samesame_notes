package org.samesame.notes.core.export

import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Config
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.MarkdownBody
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [MarkdownNoteExporterTest.ShadowPdfDocument::class])
class MarkdownNoteExporterTest {

    @Implements(PdfDocument::class)
    class ShadowPdfDocument {
        private val pages = mutableListOf<PdfDocument.Page>()

        @Implementation
        fun __constructor__() {}

        @Implementation
        fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
            val ctor = PdfDocument.Page::class.java.getDeclaredConstructor(Canvas::class.java, PdfDocument.PageInfo::class.java)
            ctor.isAccessible = true
            val page = ctor.newInstance(Canvas(), pageInfo)
            pages.add(page)
            return page
        }

        @Implementation
        fun finishPage(page: PdfDocument.Page) {}

        @Implementation
        fun writeTo(out: OutputStream) {
            out.write("%PDF-1.4 mock content".toByteArray(Charsets.US_ASCII))
        }

        @Implementation
        fun close() {}
    }

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

    @Test
    fun testExportMarkdownAsPdf() = runBlocking {
        val md = "# Title\n\nHello **world**\n\n- [ ] Task"
        val source = mdSource(md)
        val exporter = NoteExporter()
        val out = ByteArrayOutputStream()

        val target = exporter.export(source, ExportFormat.PDF, ExportOptions(), out)
        assertThat(target.extension).isEqualTo("pdf")
        val bytes = out.toByteArray()
        assertThat(bytes.size).isGreaterThan(10)
        // PDF magic header: %PDF
        assertThat(String(bytes.copyOfRange(0, 4), Charsets.US_ASCII)).isEqualTo("%PDF")
    }
}
