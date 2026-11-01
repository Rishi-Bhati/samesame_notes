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
        assertThat(read.pages.map { it.pageId }).containsExactly("page-1", "page-2")
    }

    @Test
    fun `builder is byte stable for the same input and clock`() {
        val first = SdocxDocumentBuilder.toArchive(document, SdocxFixtures.MODIFIED_US) { SdocxFixtures.UUID_1 }
        val second = SdocxDocumentBuilder.toArchive(document, SdocxFixtures.MODIFIED_US) { SdocxFixtures.UUID_1 }

        assertThat(SdocxWriter().writeToBytes(second)).isEqualTo(SdocxWriter().writeToBytes(first))
    }

    @Test
    fun `builder does not use branded app metadata`() {
        val archive = SdocxDocumentBuilder.toArchive(document, SdocxFixtures.MODIFIED_US)

        assertThat(archive.endTag.appName.lowercase()).contains("samesame")
        assertThat(archive.endTag.appName.lowercase()).doesNotContain("reference")
    }
}
