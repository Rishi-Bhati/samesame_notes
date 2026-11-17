package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.text.HtmlExport
import org.samesame.notes.core.export.text.MarkdownExport
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ParagraphStyle
import java.io.ByteArrayOutputStream

/** End-to-end BundleZip: render a note that links to a library file, then write and inspect the zip. */
class BundleZipExportTest {
    private val id = "0b6c1a52-6d6f-4a39-9a3c-3f2f6a7d9e10"
    private val uri = "samesame://file/$id?n=Plan%20v2.pdf"
    private val doc = sampleDoc(rich(Para(ParagraphStyle(), "See " to PLAIN, "Plan v2" to CharStyle(link = uri), " now" to PLAIN)))
    private fun files() = listOf(ExportLinkedFile(id, "Plan v2.pdf") { "pdf-bytes".toByteArray().inputStream() })

    @Test fun markdownZipHasNoteAndFilesWithRelativeLinks() {
        val markdown = MarkdownExport.render(doc, FakeAssets(), writeMedia = false).markdown
        val paths = org.samesame.notes.core.model.files.ZipEntryNames.allocate(files().map { it.id to it.name })
        assertThat(BundleZip.linkedIds(markdown)).containsExactly(id)
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking { BundleZip.write(out, "note.md", BundleZip.rewriteLinks(markdown, paths), files = files(), paths = paths) }
        val parts = unzip(out.toByteArray())
        assertThat(parts.keys).containsExactly("note.md", "files/Plan v2.pdf")
        assertThat(String(parts.getValue("note.md"))).contains("[Plan v2](files/Plan%20v2.pdf)")
        assertThat(String(parts.getValue("files/Plan v2.pdf"))).isEqualTo("pdf-bytes")
    }

    @Test fun htmlZipHasNoteAndFilesWithRelativeLinks() {
        val html = HtmlExport.render(doc, FakeAssets())
        val paths = org.samesame.notes.core.model.files.ZipEntryNames.allocate(files().map { it.id to it.name })
        val out = ByteArrayOutputStream()
        kotlinx.coroutines.runBlocking { BundleZip.write(out, "note.html", BundleZip.rewriteLinks(html, paths), files = files(), paths = paths) }
        val parts = unzip(out.toByteArray())
        assertThat(parts.keys).containsExactly("note.html", "files/Plan v2.pdf")
        assertThat(String(parts.getValue("note.html"))).contains("href=\"files/Plan%20v2.pdf\"")
    }
}
