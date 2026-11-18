package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.samesame.notes.core.export.render.loadNote
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PdfBackground
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LoadedPdfMediaTest {
    private abstract class Source : ExportSource {
        override suspend fun document() = NoteDocument(id = "n", pages = listOf(PageSpec("p", pdf = PdfBackground("media/src.pdf", 0))))
        override suspend fun page(pageId: String) = PageContent(pageId, emptyList(), 0)
        override suspend fun media(path: String): ByteArray? = error("PDF must not be loaded as bytes")
    }

    @Test fun `seekable PDF is borrowed without reading bytes`() = runBlocking {
        val file = File.createTempFile("source", ".pdf")
        try {
            val loaded = loadNote(object : Source() {
                override suspend fun mediaFile(path: String) = file
            }, includePdfMedia = true)
            assertThat(loaded.media).isEmpty()
            assertThat(loaded.pdfMedia["media/src.pdf"]).isSameInstanceAs(file)
            loaded.close()
            assertThat(file.exists()).isTrue()
        } finally { file.delete() }
    }

    @Test fun `streamed PDF is staged and owned file is removed on close`() = runBlocking {
        var closed = false
        val loaded = loadNote(object : Source() {
            override suspend fun openMedia(path: String) = object : ByteArrayInputStream(ByteArray(20000) { 42 }) {
                override fun close() { closed = true; super.close() }
            }
        }, includePdfMedia = true)
        val file = loaded.pdfMedia.getValue("media/src.pdf")
        assertThat(file.length()).isEqualTo(20000)
        assertThat(closed).isTrue()
        assertThat(loaded.media).isEmpty()
        loaded.close()
        assertThat(file.exists()).isFalse()
    }
}
