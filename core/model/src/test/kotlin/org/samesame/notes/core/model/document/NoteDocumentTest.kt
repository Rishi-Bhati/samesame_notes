package org.samesame.notes.core.model.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps

class NoteDocumentTest {
    @Test fun `document round trip with all object kinds`() {
        val body = RichTextOps.insertObject(RichText.of("hi", CharStyle(bold = true)), 1, "img1")
        val doc = NoteDocument(
            id = "n1", title = "T",
            layout = PageLayout(PageMode.Infinite, PageRatio.Long, ScrollDirection.Horizontal),
            style = NoteStyle(backgroundColor = 0xFF010203, adjustForDarkMode = false),
            body = body,
            inlineObjects = listOf(
                ImageObject("img1", "media/abc.png", 100f, 50f, RectPu(0f, 0f, 10f, 10f)),
                TextBoxObject("tb", RichText.of("box"), TextBoxBorder.Solid),
                VoiceObject("v", "media/v.m4a", 1234L),
                PaintingObject("d", "media/t.png", "media/s.drw", RectPu(1f, 2f, 30f, 40f)),
            ),
            recordings = listOf(
                NoteRecording("r1", "media/r.m4a", "Voice recording 1", 1_700_000_000_000L, 18_000L, "00:18", 7,
                    listOf(VoiceTimelineEvent(1, 0L), VoiceTimelineEvent(4, 18_000L))),
            ),
            pages = listOf(
                PageSpec("p1", TemplateRef.Builtin(PageTemplate.GridMedium), 5L, true, PdfBackground("media/x.pdf", 2)),
                PageSpec("p2", TemplateRef.Image("media/bg.png"), size = PageSize(612f, 792f)),
            ),
            viewState = ViewState(1, 20f, 1.5f),
        )
        assertThat(NoteDocument.fromJson(doc.toJson())).isEqualTo(doc)
    }

    @Test fun `defaults are written and version is 6`() {
        val json = NoteDocument(id = "n", pages = listOf(PageSpec("p"))).toJson()
        assertThat(json).contains("\"formatVersion\":6")
        assertThat(json).contains("\"size\":null")
        assertThat(json).contains("\"recordings\":[]")
        assertThat(NoteDocument.fromJson(json).formatVersion).isEqualTo(NOTE_DOCUMENT_FORMAT_VERSION)
    }

    @Test fun `unknown keys are ignored and missing defaults filled`() {
        val doc = NoteDocument.fromJson("""{"id":"n","pages":[{"id":"p"}],"future":1}""")
        assertThat(doc.layout).isEqualTo(PageLayout())
        assertThat(doc.pages.single().template).isEqualTo(TemplateRef.Builtin(PageTemplate.Blank))
        assertThat(doc.body.paragraphs).hasSize(1)
    }

    @Test fun `floating object round trip`() {
        val f = FloatingObject(ImageObject("i", "media/a.png", 1f, 2f), RectPu(1f, 2f, 3f, 4f), 15f, TextWrap.Behind)
        assertThat(FloatingObject.fromJson(f.toJson())).isEqualTo(f)
        val t = FloatingObject(TextBoxObject("t", RichText.of("x")), RectPu(0f, 0f, 5f, 5f))
        assertThat(FloatingObject.fromJson(t.toJson())).isEqualTo(t)
    }

    @Test fun `painting object round trip and default size`() {
        val f = FloatingObject(PaintingObject("d", "media/t.png", "media/s.drw", RectPu(10f, 20f, 110f, 80f)), RectPu(0f, 0f, 50f, 30f), 30f)
        assertThat(FloatingObject.fromJson(f.toJson())).isEqualTo(f)
        assertThat(f.defaultSize()).isEqualTo(SizePu(100f, 60f))
        assertThat(f.obj.bitmapMedia).isEqualTo("media/t.png")
    }

    @Test fun `resize helpers`() {
        val f = FloatingObject(ImageObject("i", "media/a.png", 400f, 200f), RectPu(100f, 100f, 300f, 200f))
        val d = f.resizedToDefault().rect
        assertThat(d.width).isEqualTo(400f); assertThat(d.height).isEqualTo(200f)
        assertThat((d.left + d.right) / 2f).isEqualTo(200f)
        val fit = f.fittedToWidth(1080f).rect
        assertThat(fit.left).isEqualTo(0f); assertThat(fit.width).isEqualTo(1080f)
        assertThat(fit.height).isEqualTo(540f); assertThat(fit.top).isEqualTo(100f)
        assertThat(f.fittedToWidth(1080f).isFittedToWidth(1080f)).isTrue()
        assertThat(f.isFittedToWidth(1080f)).isFalse()
        val voice = FloatingObject(VoiceObject("v", "media/v.m4a", 1L), RectPu(0f, 0f, 480f, 120f))
        assertThat(voice.isResizable).isFalse()
        assertThat(voice.supportsWrap).isFalse()
        assertThat(f.supportsWrap).isTrue()
        val m = RectPu(0f, 0f, 10f, 10f).mapped(2f, 0f, 5f, 0f, 3f, 1f)
        assertThat(m).isEqualTo(RectPu(5f, 1f, 25f, 31f))
    }
}
