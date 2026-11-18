package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.samesame.notes.core.export.render.MarkdownCardPreview
import org.samesame.notes.core.export.render.renderFirstPagePreview
import org.samesame.notes.core.model.document.MarkdownBody
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.ink.page.PageContent

@RunWith(RobolectricTestRunner::class)
class MarkdownCardPreviewTest {

    @Test
    fun testCardPreviewRendersPngBytes() {
        val md = """
            # Note Title

            This is a preview paragraph with **bold** text.

            - [ ] Todo item
            - [x] Done item

            | A | B |
            |---|---|
            | 1 | 2 |
        """.trimIndent()

        val bytes = MarkdownCardPreview.render(md, targetWidthPx = 360, darkTheme = false)
        assertThat(bytes).isNotNull()
        assertThat(bytes!!.size).isGreaterThan(100)
        // PNG magic header: 89 50 4E 47 0D 0A 1A 0A
        assertThat(bytes[0].toInt() and 0xFF).isEqualTo(0x89)
        assertThat(bytes[1].toInt().toChar()).isEqualTo('P')
        assertThat(bytes[2].toInt().toChar()).isEqualTo('N')
        assertThat(bytes[3].toInt().toChar()).isEqualTo('G')
    }

    @Test
    fun testCardPreviewDarkTheme() {
        val md = "# Dark Note\n\nSome preview content."
        val bytes = MarkdownCardPreview.render(md, targetWidthPx = 360, darkTheme = true)
        assertThat(bytes).isNotNull()
    }

    @Test
    fun testPagePreviewDispatchesMarkdown() {
        val doc = NoteDocument(
            id = "md-note-1",
            title = "MD Title",
            body = org.samesame.notes.core.model.richtext.RichText(),
            pages = listOf(PageSpec("p1")),
            markdown = MarkdownBody(content = "# Card Preview\n\nPreview text"),
        )
        val source = object : ExportSource {
            override suspend fun document(): NoteDocument = doc
            override suspend fun page(pageId: String): PageContent = PageContent(pageId, emptyList(), 0)
            override suspend fun media(path: String): ByteArray? = null
        }

        val bytes = kotlinx.coroutines.runBlocking {
            renderFirstPagePreview(source, targetWidthPx = 360, darkTheme = false)
        }
        assertThat(bytes).isNotNull()
    }
}
