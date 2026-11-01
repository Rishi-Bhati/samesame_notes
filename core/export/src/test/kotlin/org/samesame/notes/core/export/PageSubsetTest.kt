package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.samesame.notes.core.export.render.loadNote
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec

class PageSubsetTest {
    private val full = object : ExportSource {
        val doc = NoteDocument(id = "n1", title = "T", pages = listOf(PageSpec("p1"), PageSpec("p2"), PageSpec("p3")))
        override suspend fun document() = doc
        override suspend fun page(pageId: String) = PageContent(pageId, emptyList(), 0)
        override suspend fun media(path: String): ByteArray? = if (path == "media/a.png") byteArrayOf(1) else null
    }

    @Test fun documentKeepsSelectedPagesInNoteOrder() {
        runBlocking {
            val subset = PageSubsetSource(full, setOf("p3", "p1"))
            assertThat(subset.document().pages.map { it.id }).containsExactly("p1", "p3").inOrder()
            assertThat(subset.document().title).isEqualTo("T")
        }
    }

    @Test fun layersAndMediaPassThrough() {
        runBlocking {
            val subset = PageSubsetSource(full, setOf("p2"))
            assertThat(subset.page("p2").pageId).isEqualTo("p2")
            assertThat(subset.media("media/a.png")).isEqualTo(byteArrayOf(1))
            assertThat(subset.media("media/none.png")).isNull()
        }
    }

    @Test fun loadedNoteHoldsOnlyTheSubset() {
        runBlocking {
            val loaded = loadNote(PageSubsetSource(full, setOf("p2")))
            assertThat(loaded.doc.pages.map { it.id }).containsExactly("p2")
            assertThat(loaded.pages.keys).containsExactly("p2")
        }
    }

    @Test fun optionsDefaultToTheWholeNote() {
        assertThat(ExportOptions().pageIds).isNull()
        assertThat(ExportOptions(pageIds = setOf("p1")).pageIds).containsExactly("p1")
    }
}
