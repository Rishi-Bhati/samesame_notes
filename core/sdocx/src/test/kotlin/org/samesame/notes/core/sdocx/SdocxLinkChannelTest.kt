package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.sdocx.map.SdocxMapper

class SdocxLinkChannelTest {
    private val uri = "samesame://file/0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60?n=plan.pdf"
    private val fileId = "0b6f6c1e-2a43-4c35-9a3e-1d2c3b4a5f60"

    private fun doc(text: String = "see plan.pdf now") = NoteDocument(
        id = "n", title = "T", pages = listOf(PageSpec("p1")),
        body = RichText(text, listOf(TextRun(4, CharStyle()), TextRun(8, CharStyle(link = uri, bold = true)), TextRun(text.length - 12, CharStyle()))),
        inlineObjects = listOf(ImageObject("i", "media/a.png", 1f, 1f, libraryFileId = fileId)),
    )

    private fun roundTrip(d: NoteDocument): SdocxArchive {
        val base = SdocxDocumentBuilder.toArchive(d, 1_000_000L)
        val withLinks = SdocxLinkChannel.withEntry(base, SdocxLinkChannel.encode(d) { "imgsha" })
        return SdocxReader().read(SdocxWriter().writeToBytes(withLinks))
    }

    @Test fun `link runs and library ids survive write and read and come back on import`() {
        val d = doc()
        val archive = roundTrip(d)
        assertThat(archive.extraEntries).containsKey(SdocxLinkChannel.ENTRY)
        val imported = SdocxMapper.toNoteDocument(archive, "n").copy(
            inlineObjects = listOf(ImageObject("i", "media/a.png", 1f, 1f)),
        )
        assertThat(imported.body.runs.none { it.style.link != null }).isTrue() // plain label text in the sdocx
        val restored = SdocxLinkChannel.apply(imported, archive.extraEntries[SdocxLinkChannel.ENTRY]) { "imgsha" }
        assertThat(restored.body.text).isEqualTo(d.body.text)
        val linked = restored.body.runs.filter { it.style.link == uri }
        assertThat(linked.sumOf { it.length }).isEqualTo(8)
        assertThat((restored.inlineObjects.single() as ImageObject).libraryFileId).isEqualTo(fileId)
    }

    @Test fun `a link is restored only where the label text is unchanged`() {
        val entry = SdocxLinkChannel.encode(doc()) { null }!!
        val edited = doc("see plan.PDF now").copy(body = RichText("see plan.PDF now"))
        val out = SdocxLinkChannel.apply(edited, entry) { null }
        assertThat(out.body.runs.none { it.style.link != null }).isTrue()
        val moved = RichText("xx see plan.pdf now")
        assertThat(SdocxLinkChannel.apply(doc().copy(body = moved), entry) { null }.body.runs.none { it.style.link != null }).isTrue()
    }

    @Test fun `no links and no library images means no entry and an unchanged archive`() {
        val plain = NoteDocument(id = "n", pages = listOf(PageSpec("p1")), body = RichText("hello"))
        assertThat(SdocxLinkChannel.encode(plain) { null }).isNull()
        val base = SdocxDocumentBuilder.toArchive(plain, 1L)
        assertThat(SdocxLinkChannel.withEntry(base, null)).isSameInstanceAs(base)
    }

    @Test fun `garbage entries never throw and leave the document alone`() {
        val d = doc()
        assertThat(SdocxLinkChannel.apply(d, "not json".toByteArray()) { null }).isEqualTo(d)
        assertThat(SdocxLinkChannel.apply(d, """{"links":[{"start":-5,"end":99999,"uri":"x","labelSha":"y"}]}""".toByteArray()) { null }).isEqualTo(d)
    }
}
