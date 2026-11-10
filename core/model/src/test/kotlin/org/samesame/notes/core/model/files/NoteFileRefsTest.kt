package org.samesame.notes.core.model.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun

class NoteFileRefsTest {
    private val a = FileId("0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60")
    private val b = FileId("11111111-2222-4333-8444-555555555555")
    private val uriA = FileLinks.format(a, "photo.png")
    private val uriB = FileLinks.format(b, "b.pdf")

    @Test fun `link runs, raw links and inline pictures are counted`() {
        val raw = " $uriB "
        val text = "ab" + "cd" + raw + OBJECT_CHAR + "x" + OBJECT_CHAR
        val body = RichText(
            text,
            listOf(
                // One link whose label is partly bold counts once.
                TextRun(1, CharStyle(link = uriA)), TextRun(1, CharStyle(bold = true, link = uriA)),
                // The same file under another name hint is a second link.
                TextRun(2, CharStyle(link = FileLinks.format(a, "renamed.png"))),
                TextRun(raw.length, CharStyle()),
                TextRun(1, CharStyle(objectId = "img1")), TextRun(1, CharStyle()), TextRun(1, CharStyle(objectId = "img2")),
            ),
        )
        val doc = NoteDocument(
            id = "n", pages = emptyList(), body = body,
            inlineObjects = listOf(
                ImageObject("img1", "media/x.png", 10f, 10f, libraryFileId = a.value),
                ImageObject("img2", "media/y.png", 10f, 10f), // not from Files
                ImageObject("gone", "media/z.png", 10f, 10f, libraryFileId = b.value), // no longer in the text
            ),
        )
        val refs = NoteFileRefs.of(doc)
        assertThat(refs[a]).containsExactly(FileRefKind.Link, 2, FileRefKind.Inline, 1)
        assertThat(refs[b]).containsExactly(FileRefKind.Link, 1)
    }

    @Test fun `a raw link inside a stored link run is not counted twice`() {
        val body = RichText(uriA, listOf(TextRun(uriA.length, CharStyle(link = uriA))))
        assertThat(NoteFileRefs.of(NoteDocument(id = "n", pages = emptyList(), body = body))).containsExactly(a, mapOf(FileRefKind.Link to 1))
    }

    @Test fun `a note without files has no refs`() {
        assertThat(NoteFileRefs.of(NoteDocument(id = "n", pages = emptyList(), body = RichText.of("see https://example.com")))).isEmpty()
        val web = RichText("site", listOf(TextRun(4, CharStyle(link = "https://example.com"))))
        assertThat(NoteFileRefs.of(NoteDocument(id = "n", pages = emptyList(), body = web))).isEmpty()
    }
}
