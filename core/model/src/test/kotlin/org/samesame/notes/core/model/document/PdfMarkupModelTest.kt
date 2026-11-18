package org.samesame.notes.core.model.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PdfMarkupModelTest {
    private val markup = PdfTextMarkup(
        "m1", PdfMarkupKind.Highlight, 0x80FFFF00L,
        listOf(QuadPu.fromRect(RectPu(10f, 20f, 110f, 40f))), "hello", 1_700_000_000_000L,
    )

    @Test fun `markup and rotation survive a round trip`() {
        val doc = NoteDocument(
            id = "n",
            pages = listOf(
                PageSpec("p1", pdf = PdfBackground("media/x.pdf", 0), textMarkup = listOf(markup), rotation = 90),
                PageSpec("p2"),
            ),
        )
        val json = doc.toJson()
        assertThat(json).contains("\"highlight\"")
        assertThat(json).contains("\"rotation\":90")
        assertThat(NoteDocument.fromJson(json)).isEqualTo(doc)
    }

    @Test fun `a note without pdf features writes no new keys`() {
        val json = NoteDocument(id = "n", pages = listOf(PageSpec("p", size = PageSize(612f, 792f)))).toJson()
        assertThat(json).doesNotContain("textMarkup")
        assertThat(json).doesNotContain("rotation")
        // Hand-written v10 text (field order as the v10 writer produced it) is reproduced exactly apart from the version.
        val v10 = json.replace("\"formatVersion\":11", "\"formatVersion\":10")
        val reread = NoteDocument.fromJson(v10)
        assertThat(reread.pages.single().textMarkup).isEmpty()
        assertThat(reread.pages.single().rotation).isEqualTo(0)
        assertThat(reread.toJson().replace("\"formatVersion\":11", "\"formatVersion\":10")).isEqualTo(v10)
    }

    @Test fun `quad bounds cover all corners`() {
        val q = QuadPu(1f, 2f, 9f, 2f, 1f, 7f, 9f, 7f)
        assertThat(q.bounds).isEqualTo(RectPu(1f, 2f, 9f, 7f))
    }

    @Test fun `a sideways page swaps its aspect and rotation is folded`() {
        val layout = PageLayout()
        val base = PageSpec("p", size = PageSize(600f, 800f))
        assertThat(base.heightOverWidth(layout)).isWithin(1e-5f).of(800f / 600f)
        assertThat(base.copy(rotation = 90).heightOverWidth(layout)).isWithin(1e-5f).of(600f / 800f)
        assertThat(base.copy(rotation = 180).heightOverWidth(layout)).isWithin(1e-5f).of(800f / 600f)
        assertThat(base.copy(rotation = -90).normalizedRotation).isEqualTo(270)
        assertThat(base.copy(rotation = 450).normalizedRotation).isEqualTo(90)
    }

    @Test fun `a follower page has no markup`() {
        assertThat(PageSpec("p", textMarkup = listOf(markup)).follower("q").textMarkup).isEmpty()
    }
}
