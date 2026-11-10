package org.samesame.notes.core.model.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun

class FileIdRemapTest {
    private val old = "0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60"
    private val new = "99999999-8888-4777-8666-555555555555"
    private val other = "11111111-2222-4333-8444-555555555555"

    @Test fun `rewrites link runs, raw link text and library ids, leaving other ids alone`() {
        val text = "a samesame://file/$old?n=x b samesame://file/$other?n=y"
        val doc = NoteDocument(
            id = "n", pages = listOf(PageSpec("p")),
            body = RichText(
                text,
                listOf(TextRun(2, CharStyle(link = "samesame://file/$old?n=x")), TextRun(text.length - 2, CharStyle(link = "https://example.org/$old"))),
            ),
            inlineObjects = listOf(ImageObject("i", "media/a.png", 1f, 1f, libraryFileId = old.uppercase()), ImageObject("j", "media/b.png", 1f, 1f, libraryFileId = other)),
        )
        val out = FileIdRemap.apply(doc, mapOf(old to new))
        assertThat(out.body.text).isEqualTo("a samesame://file/$new?n=x b samesame://file/$other?n=y")
        assertThat(out.body.runs[0].style.link).isEqualTo("samesame://file/$new?n=x")
        assertThat(out.body.runs[1].style.link).isEqualTo("https://example.org/$old") // not a file link
        assertThat(out.body.runs.map { it.length }).isEqualTo(doc.body.runs.map { it.length })
        assertThat((out.inlineObjects[0] as ImageObject).libraryFileId).isEqualTo(new)
        assertThat((out.inlineObjects[1] as ImageObject).libraryFileId).isEqualTo(other)
    }

    @Test fun `an empty map returns the same document`() {
        val doc = NoteDocument(id = "n", pages = listOf(PageSpec("p")))
        assertThat(FileIdRemap.apply(doc, emptyMap())).isSameInstanceAs(doc)
    }
}
