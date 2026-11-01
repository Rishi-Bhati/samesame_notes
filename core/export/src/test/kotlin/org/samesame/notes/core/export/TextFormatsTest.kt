package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.samesame.notes.core.export.text.HtmlExport
import org.samesame.notes.core.export.text.MarkdownExport
import org.samesame.notes.core.export.text.TextExport
import org.junit.Test

class TextFormatsTest {
    @Test fun txtHasTitleFirstAndListMarkers() {
        val lines = TextExport.render(sampleDoc(), null).lines()
        assertThat(lines[0]).isEqualTo("My Note")
        assertThat(lines).containsAtLeast(
            "Hello bold and italic", "• apples", "1. first", "2. second", "[ ] todo", "[x] done",
        )
        assertThat(lines.any { it == "  centered <tag> & 'q'" }).isTrue()
    }

    @Test fun txtNumberingRestartsAfterInterruption() {
        val rt = rich(
            Para(org.samesame.notes.core.model.richtext.ParagraphStyle(list = org.samesame.notes.core.model.richtext.ListKind.Numbered), "a" to PLAIN),
            Para(org.samesame.notes.core.model.richtext.ParagraphStyle(), "x" to PLAIN),
            Para(org.samesame.notes.core.model.richtext.ParagraphStyle(list = org.samesame.notes.core.model.richtext.ListKind.Numbered), "b" to PLAIN),
        )
        assertThat(TextExport.renderBody(rt)).isEqualTo("1. a\nx\n1. b")
    }

    @Test fun markdownEmphasisListsAndChecklists() {
        val md = MarkdownExport.render(sampleDoc(), FakeAssets(), writeMedia = false).markdown
        assertThat(md).startsWith("# My Note\n")
        assertThat(md).contains("Hello **bold** and *italic*")
        assertThat(md).contains("- apples")
        assertThat(md).contains("1. first\n2. ~~<u>second</u>~~".replace("~~<u>second</u>~~", "~~<u>second</u>~~"))
        assertThat(md).contains("- [ ] todo")
        assertThat(md).contains("- [x] done")
    }

    @Test fun markdownImagesWrittenOnlyForZip() {
        val assets = FakeAssets(media = mapOf("media/abc.png" to TINY_PNG), ink = setOf(0))
        val zipMode = MarkdownExport.render(docWithImage(), assets, writeMedia = true)
        assertThat(zipMode.markdown).contains("![image](media/abc.png)")
        assertThat(zipMode.media.keys).containsExactly("media/abc.png", "media/page-1-ink.png")
        val plain = MarkdownExport.render(docWithImage(), assets, writeMedia = false)
        assertThat(plain.markdown).contains("before [Image] after")
        assertThat(plain.media).isEmpty()
    }

    @Test fun markdownKeepsSpacesOutsideEmphasis() {
        val rt = rich(Para(org.samesame.notes.core.model.richtext.ParagraphStyle(), "a" to PLAIN, " b " to BOLD, "c" to PLAIN))
        assertThat(MarkdownExport.render(sampleDoc(rt, title = ""), FakeAssets(), false).markdown).isEqualTo("a **b** c\n")
    }

    @Test fun htmlUsesInlineStylesAndEmbedsImages() {
        val html = HtmlExport.render(sampleDoc(), FakeAssets())
        assertThat(html).contains("<title>My Note</title>")
        assertThat(html).contains("font-weight:bold")
        assertThat(html).contains("color:#FF0000;background-color:#FFFF00;font-size:1.412em;")
        assertThat(html).contains("text-decoration:underline line-through")
        assertThat(html).contains("centered &lt;tag&gt; &amp; 'q'")
        assertThat(html).contains("<ol start=\"1\">")
        assertThat(html).contains("☐ todo")
        val withImg = HtmlExport.render(docWithImage(), FakeAssets(media = mapOf("media/abc.png" to TINY_PNG), ink = setOf(0)))
        assertThat(withImg).contains("src=\"data:image/png;base64,")
        assertThat(withImg).contains("Handwriting, page 1")
        // well-formed enough to parse as XML apart from the doctype/void tags
        assertThat(Regex("<img [^>]*>").findAll(withImg).count()).isEqualTo(2)
    }
}
