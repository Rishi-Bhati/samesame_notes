package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.RichText

class SdocxDocumentBuilderTest {

    private val document = NoteDocument(
        id = "note-1",
        title = "Shopping",
        layout = PageLayout(mode = PageMode.Pages),
        body = RichText.of("Milk\nEggs"),
        pages = listOf(PageSpec(id = "page-1"), PageSpec(id = "page-2")),
    )

    @Test
    fun `builder emits a readable archive with the note text and pages`() {
        val archive = SdocxDocumentBuilder.toArchive(document, nowUs = SdocxFixtures.MODIFIED_US) {
            SdocxFixtures.UUID_1
        }
        val read = SdocxReader().read(SdocxWriter().writeToBytes(archive))

        assertThat(read.note.title.text?.text).isEqualTo("Shopping")
        assertThat(read.note.body.text?.text).isEqualTo("Milk\nEggs")
        assertThat(read.note.width).isEqualTo(SdocxDocumentBuilder.PAGE_WIDTH_PX)
        assertThat(read.pages).hasSize(2)
        // Page ids are uuid texts in the file; another kind of id is mapped to a stable uuid.
        assertThat(read.pages.map { it.pageId })
            .containsExactly(SdocxDocumentBuilder.pageId("page-1"), SdocxDocumentBuilder.pageId("page-2")).inOrder()
        assertThat(read.pages.map { it.pageId.length }).containsExactly(36, 36)
        assertThat(SdocxDocumentBuilder.pageId("3f2a1b0c-9d8e-4f7a-b6c5-d4e3f2a1b0c9")).isEqualTo("3f2a1b0c-9d8e-4f7a-b6c5-d4e3f2a1b0c9")
        assertThat(read.pages.map { it.layers.size }).containsExactly(1, 1)
    }

    @Test
    fun `builder is byte stable for the same input and clock`() {
        val first = SdocxDocumentBuilder.toArchive(document, SdocxFixtures.MODIFIED_US) { SdocxFixtures.UUID_1 }
        val second = SdocxDocumentBuilder.toArchive(document, SdocxFixtures.MODIFIED_US) { SdocxFixtures.UUID_1 }

        assertThat(SdocxWriter().writeToBytes(second)).isEqualTo(SdocxWriter().writeToBytes(first))
    }

    @Test
    fun `builder writes the app fields the way real files have them`() {
        val archive = SdocxDocumentBuilder.toArchive(document, SdocxFixtures.MODIFIED_US)

        assertThat(archive.endTag.appName).isEmpty()
        assertThat(archive.endTag.appMajorVersion).isEqualTo(-1)
        assertThat(archive.endTag.appMinorVersion).isEqualTo(-1)
        assertThat(archive.endTag.noteId).isEmpty()
        assertThat(archive.note.noteId).isEmpty()
    }
}
