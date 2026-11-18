package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.samesame.notes.core.export.render.PdfExport
import org.samesame.notes.core.export.render.PdfExportDeadline
import org.samesame.notes.core.export.render.loadNote
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
class PdfDeadlineTest {
    @Test fun completedSavePastDeadlineIsNeverCopiedOrRetried() {
        var now = 0L
        val deadline = PdfExportDeadline(10) { now }
        val out = ByteArrayOutputStream()
        var fallback = false
        assertThrows(TimeoutException::class.java) {
            PdfExport.writeStaged(out, { it.writeText("complete"); now = 10_000_000 }, { fallback = true }, deadline::check)
        }
        assertThat(out.size()).isEqualTo(0)
        assertThat(fallback).isFalse()
    }

    @Test fun timeoutDuringVectorNeverStartsFallback() {
        var fallback = false
        val out = ByteArrayOutputStream()
        assertThrows(TimeoutException::class.java) {
            PdfExport.writeStaged(out, { throw TimeoutException("overlay") }, { fallback = true })
        }
        assertThat(fallback).isFalse()
        assertThat(out.size()).isEqualTo(0)
    }

    @Test fun slowDocumentLoadStopsBeforeReadingPages() {
        var now = 0L
        var pages = 0
        val deadline = PdfExportDeadline(10) { now }
        val source = object : ExportSource {
            override suspend fun document(): NoteDocument {
                now = 10_000_000
                return NoteDocument(id = "n", pages = listOf(PageSpec("p")))
            }
            override suspend fun page(pageId: String): PageContent { pages++; return PageContent(pageId, emptyList(), 0) }
            override suspend fun media(path: String): ByteArray? = null
        }
        assertThrows(TimeoutException::class.java) { runBlocking { loadNote(source, true, deadline::check) } }
        assertThat(pages).isEqualTo(0)
    }
}
