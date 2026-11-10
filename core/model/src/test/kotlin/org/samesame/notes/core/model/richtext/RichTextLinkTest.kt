package org.samesame.notes.core.model.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.NoteJson
import org.samesame.notes.core.model.document.PageSpec

/** Format v8 link runs: merge key, sticky-style rule, JSON. */
class RichTextLinkTest {
    private val uri = "samesame://file/0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60?n=photo.png"
    private val other = "samesame://file/11111111-2222-4333-8444-555555555555?n=b.pdf"
    private val link = CharStyle(link = uri)

    /** "see photo.png now": the label is a link run. */
    private fun sample(): RichText = RichTextOps.normalise(
        RichText("see photo.png now", listOf(TextRun(4, CharStyle()), TextRun(9, link), TextRun(4, CharStyle()))),
    )

    @Test fun `typing at the end of a link does not extend it`() {
        val rt = RichTextOps.insert(sample(), 13, "!")
        assertThat(rt.text).isEqualTo("see photo.png! now")
        assertThat(rt.runs).containsExactly(TextRun(4, CharStyle()), TextRun(9, link), TextRun(5, CharStyle())).inOrder()
    }

    @Test fun `typing at the start of a link does not extend it`() {
        val rt = RichTextOps.insert(sample(), 4, "a ")
        assertThat(rt.runs).containsExactly(TextRun(6, CharStyle()), TextRun(9, link), TextRun(4, CharStyle())).inOrder()
    }

    @Test fun `typing inside a link extends it`() {
        val rt = RichTextOps.insert(sample(), 9, "-1")
        assertThat(rt.text).isEqualTo("see photo-1.png now")
        assertThat(rt.runs).containsExactly(TextRun(4, CharStyle()), TextRun(11, link), TextRun(4, CharStyle())).inOrder()
    }

    @Test fun `typing after a link that ends the text does not extend it`() {
        val rt = RichTextOps.normalise(RichText("photo.png", listOf(TextRun(9, link))))
        val typed = RichTextOps.insert(rt, 9, "x")
        assertThat(typed.runs).containsExactly(TextRun(9, link), TextRun(1, CharStyle())).inOrder()
        assertThat(RichTextOps.insert(rt, 0, "x").runs).containsExactly(TextRun(1, CharStyle()), TextRun(9, link)).inOrder()
    }

    @Test fun `other attributes still stick at the end of a link`() {
        val boldLink = CharStyle(bold = true, link = uri)
        val rt = RichTextOps.normalise(RichText("ab", listOf(TextRun(2, boldLink))))
        assertThat(RichTextOps.typingStyleAt(rt, 2)).isEqualTo(CharStyle(bold = true))
        assertThat(RichTextOps.typingStyle(boldLink, boldLink)).isEqualTo(boldLink)
        assertThat(RichTextOps.typingStyle(boldLink, CharStyle(link = other))).isEqualTo(CharStyle(bold = true))
        assertThat(RichTextOps.typingStyle(CharStyle(italic = true), null)).isEqualTo(CharStyle(italic = true))
    }

    @Test fun `link is part of the merge key`() {
        val rt = RichTextOps.normalise(
            RichText("abcd", listOf(TextRun(2, CharStyle(link = uri)), TextRun(1, CharStyle(link = uri)), TextRun(1, CharStyle(link = other)))),
        )
        assertThat(rt.runs).containsExactly(TextRun(3, CharStyle(link = uri)), TextRun(1, CharStyle(link = other))).inOrder()
        // A plain neighbour never merges into a link.
        val mixed = RichTextOps.normalise(RichText("ab", listOf(TextRun(1, CharStyle()), TextRun(1, link))))
        assertThat(mixed.runs).hasSize(2)
    }

    @Test fun `objects inserted at a link end get no link`() {
        val rt = RichTextOps.insertObject(sample(), 13, "img")
        assertThat(rt.runs[2]).isEqualTo(TextRun(1, CharStyle(objectId = "img")))
    }

    @Test fun `link and libraryFileId round trip in JSON and default to null`() {
        val doc = NoteDocument(
            id = "n", body = sample(),
            inlineObjects = listOf(ImageObject("i", "media/a.png", 10f, 10f, libraryFileId = "0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60")),
            pages = listOf(PageSpec("p")),
        )
        assertThat(NoteDocument.fromJson(doc.toJson())).isEqualTo(doc)
        val v7Run = NoteJson.decodeFromString(TextRun.serializer(), """{"length":3,"style":{"bold":true}}""")
        assertThat(v7Run.style.link).isNull()
        val v7Image = NoteJson.decodeFromString(ImageObject.serializer(), """{"id":"i","media":"media/a.png","widthPu":1.0,"heightPu":2.0}""")
        assertThat(v7Image.libraryFileId).isNull()
    }
}
