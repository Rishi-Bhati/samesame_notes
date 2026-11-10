package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.sdocx.SdocxSideChannel
import java.io.File

/**
 * Guards the view-side `.sdocx` fallback in [NoteDocumentStore.readPage]: a broken side channel must never
 * break page loading, and a local ink file always wins over the imported archive once a page is edited.
 * The positive decoding path builds androidx.ink strokes (native), so it lives in the instrumented
 * `SdocxInkBridgeTest`.
 */
class SdocxReadPageFallbackTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(root: File) = NoteDocumentStore(root, File(tmp.root, "working"))

    @Test
    fun brokenSideChannelYieldsEmptyPage() = runBlocking {
        val store = store(tmp.newFolder("notes"))
        val note = store.create("n1", PageMode.Pages, "imported")
        store.writeExtras("n1", mapOf(SdocxSideChannel.RAW_ARCHIVE_PATH to "not a zip".toByteArray()))

        assertThat(store.readPage("n1", note.pages.single().id).elements).isEmpty()
    }

    @Test
    fun localInkFileWinsOverImportedArchive() = runBlocking {
        val root = tmp.newFolder("notes")
        val store = store(root)
        val note = store.create("n1", PageMode.Pages, "imported")
        val pageId = note.pages.single().id
        store.writeExtras("n1", mapOf(SdocxSideChannel.RAW_ARCHIVE_PATH to "not a zip".toByteArray()))

        val local = PageContent(
            pageId,
            listOf(ObjectElement(ElementId(7), "{}", box(1f, 2f, 3f, 4f))),
            0,
        )
        store.writePage("n1", local)

        val elements = store.readPage("n1", pageId).elements
        assertThat(elements).hasSize(1)
        assertThat(elements.single()).isInstanceOf(ObjectElement::class.java)
    }
}
