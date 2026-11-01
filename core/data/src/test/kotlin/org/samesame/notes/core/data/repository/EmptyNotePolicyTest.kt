package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteRecording
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.PdfBackground
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.richtext.RichText

class EmptyNotePolicyTest {

    private fun note(
        title: String = "",
        body: RichText = RichText(),
        pages: List<PageSpec> = listOf(PageSpec("p1")),
    ) = NoteDocument(id = "n1", title = title, body = body, pages = pages)

    @Test fun `fresh note with one page is empty`() {
        assertThat(isNoteEmpty(note(), listOf(0))).isTrue()
    }

    @Test fun `whitespace-only title and body are still empty`() {
        assertThat(isNoteEmpty(note(title = "   ", body = RichText.of(" \n\t\n")), listOf(0))).isTrue()
    }

    @Test fun `a title is content`() {
        assertThat(isNoteEmpty(note(title = "Ideas"), listOf(0))).isFalse()
    }

    @Test fun `body text is content`() {
        assertThat(isNoteEmpty(note(body = RichText.of("hello")), listOf(0))).isFalse()
    }

    @Test fun `a voice recording is content`() {
        val doc = note().copy(recordings = listOf(NoteRecording(id = "r1", media = "media/a.m4a", elapsedMs = 1_000)))
        assertThat(isNoteEmpty(doc, listOf(0))).isFalse()
    }

    @Test fun `an inline object is content`() {
        val doc = note(body = RichText(text = "\uFFFC"))
            .copy(inlineObjects = listOf(ImageObject(id = "o1", media = "media/a.png", widthPu = 10f, heightPu = 10f)))
        assertThat(isNoteEmpty(doc, listOf(0))).isFalse()
    }

    @Test fun `an element on any page is content`() {
        assertThat(isNoteEmpty(note(), listOf(1))).isFalse()
    }

    @Test fun `page count mismatch is not treated as empty`() {
        assertThat(isNoteEmpty(note(pages = listOf(PageSpec("p1"), PageSpec("p2"))), listOf(0))).isFalse()
    }

    @Test fun `a built-in template is decoration, not content`() {
        val pages = listOf(PageSpec("p1", template = TemplateRef.Builtin(PageTemplate.LinesMedium)))
        assertThat(isNoteEmpty(note(pages = pages), listOf(0))).isTrue()
    }

    @Test fun `an image template is content`() {
        val pages = listOf(PageSpec("p1", template = TemplateRef.Image("media/bg.png")))
        assertThat(isNoteEmpty(note(pages = pages), listOf(0))).isFalse()
    }

    @Test fun `a PDF page is content`() {
        val pages = listOf(PageSpec("p1", pdf = PdfBackground("media/doc.pdf", 0)))
        assertThat(isNoteEmpty(note(pages = pages), listOf(0))).isFalse()
    }

    @Test fun `extra empty pages do not make a note non-empty`() {
        val pages = listOf(PageSpec("p1"), PageSpec("p2"))
        assertThat(isNoteEmpty(note(pages = pages), listOf(0, 0))).isTrue()
    }
}
