package org.samesame.notes.core.data.transfer

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.export.NoteExporter
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.io.InputTooLargeException
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileConversionServiceTest {
    private val service = FileConversionService(NoteExporter())

    private fun render(text: String, markdown: Boolean, format: ExportFormat, title: String = "Doc"): ByteArray = runBlocking {
        ByteArrayOutputStream().also { service.exportText(text, title, markdown, format, it) }.toByteArray()
    }

    @Test fun markdownRendersToEscapedHtml() {
        val html = render("# Plan\n\n- [ ] one\n\n<script>alert(1)</script>\n\n[x](javascript:alert(1))", markdown = true, ExportFormat.HTML)
            .toString(Charsets.UTF_8)
        assertThat(html).contains("<h1>Plan</h1>")
        assertThat(html).doesNotContain("<script>alert(1)</script>")
        assertThat(html).doesNotContain("href=\"javascript:")
    }

    @Test fun plainTextIsNotReadAsMarkdown() {
        val html = render("# not a heading\n<b>bold?</b>", markdown = false, ExportFormat.HTML).toString(Charsets.UTF_8)
        assertThat(html).doesNotContain("<h1>not a heading</h1>")
        assertThat(html).contains("# not a heading")
        assertThat(html).doesNotContain("<b>bold?</b>")
    }

    @Test fun markdownRendersToWord() {
        val docx = render("# Plan\n\nBody", markdown = true, ExportFormat.DOCX)
        // A .docx is a ZIP archive.
        assertThat(docx.size).isGreaterThan(4)
        assertThat(docx.copyOfRange(0, 2).toString(Charsets.US_ASCII)).isEqualTo("PK")
    }

    @Test fun textOverTheCapIsRefused() {
        val big = "x".repeat(Limits.MAX_TEXT_IMPORT_BYTES.toInt() + 1)
        assertThrows(InputTooLargeException::class.java) { render(big, markdown = false, ExportFormat.HTML) }
    }

    @Test fun onlyRenderingsAreAccepted() {
        assertThrows(IllegalArgumentException::class.java) { render("a", markdown = false, ExportFormat.SSNOTE) }
    }
}
