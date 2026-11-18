package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.text.LossyFeature
import org.samesame.notes.core.export.text.MarkdownConverter
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.richtext.ListKind

class MarkdownConverterTest {

    @Test
    fun testRoundTrip() {
        val originalMd = """
            # Heading 1

            ## Heading 2

            ### Heading 3

            Hello **bold** and *italic* and ~~strike~~ and [link](https://example.com)

            - Item 1
            - Item 2

            1. Numbered 1
            2. Numbered 2

            - [ ] Task 1
            - [x] Task 2
        """.trimIndent() + "\n"

        val richText = MarkdownConverter.markdownToRichText(originalMd)
        val doc = NoteDocument(
            id = "test-doc",
            title = "",
            body = richText,
            pages = listOf(PageSpec("p1")),
        )
        val exportedMd = MarkdownConverter.richTextToMarkdown(doc).markdown

        assertThat(exportedMd).contains("# Heading 1")
        assertThat(exportedMd).contains("## Heading 2")
        assertThat(exportedMd).contains("### Heading 3")
        assertThat(exportedMd).contains("Hello **bold** and *italic* and ~~strike~~ and [link](https://example.com)")
        assertThat(exportedMd).contains("- Item 1\n- Item 2")
        assertThat(exportedMd).contains("1. Numbered 1\n2. Numbered 2")
        assertThat(exportedMd).contains("- [ ] Task 1\n- [x] Task 2")
    }

    @Test
    fun testTaskListsConversion() {
        val md = """
            - [ ] Todo item
            - [x] Completed item
            1. [ ] Ordered todo
            2. [x] Ordered completed
        """.trimIndent()

        val rt = MarkdownConverter.markdownToRichText(md)
        assertThat(rt.paragraphs).hasSize(4)

        assertThat(rt.paragraphs[0].checkbox).isTrue()
        assertThat(rt.paragraphs[0].checked).isFalse()

        assertThat(rt.paragraphs[1].checkbox).isTrue()
        assertThat(rt.paragraphs[1].checked).isTrue()

        assertThat(rt.paragraphs[2].list).isEqualTo(ListKind.Numbered)
        assertThat(rt.paragraphs[2].checkbox).isTrue()
        assertThat(rt.paragraphs[2].checked).isFalse()

        assertThat(rt.paragraphs[3].list).isEqualTo(ListKind.Numbered)
        assertThat(rt.paragraphs[3].checkbox).isTrue()
        assertThat(rt.paragraphs[3].checked).isTrue()
    }

    @Test
    fun testLossyDetection() {
        val cleanMd = "# Title\n\nSimple text and - [ ] task item."
        assertThat(MarkdownConverter.detectLossyFeatures(cleanMd)).isEmpty()

        val tableMd = """
            | Header A | Header B |
            |---|---|
            | 1 | 2 |
        """.trimIndent()
        assertThat(MarkdownConverter.detectLossyFeatures(tableMd)).containsExactly(LossyFeature.TABLES)

        val codeMd = """
            ```kotlin
            val x = 42
            ```
        """.trimIndent()
        assertThat(MarkdownConverter.detectLossyFeatures(codeMd)).containsExactly(LossyFeature.CODE_BLOCKS)

        val htmlMd = "<div class=\"warning\">Warning</div>"
        assertThat(MarkdownConverter.detectLossyFeatures(htmlMd)).containsExactly(LossyFeature.HTML)

        val quoteMd = "> This is a quote"
        assertThat(MarkdownConverter.detectLossyFeatures(quoteMd)).containsExactly(LossyFeature.BLOCK_QUOTES)

        val mathMd = "Einstein: \$E = mc^2\$"
        assertThat(MarkdownConverter.detectLossyFeatures(mathMd)).containsExactly(LossyFeature.MATH)

        val comboMd = """
            # Summary
            > Quote here
            ```
            code block
            ```
            | A | B |
            |---|---|
            | 1 | 2 |
        """.trimIndent()
        assertThat(MarkdownConverter.detectLossyFeatures(comboMd)).containsAtLeast(
            LossyFeature.TABLES,
            LossyFeature.CODE_BLOCKS,
            LossyFeature.BLOCK_QUOTES,
        )
    }

    @Test
    fun testPlainTextConversions() {
        val md = "# Heading\n\nSome **bold** and *italic* text.\n\n- Bullet 1\n- Bullet 2"
        val plain = MarkdownConverter.markdownToPlainText(md)
        assertThat(plain).contains("Heading")
        assertThat(plain).contains("Some bold and italic text.")
        assertThat(plain).doesNotContain("**")
        assertThat(plain).doesNotContain("*italic*")

        val plainInput = "# Fake heading\n> Fake quote"
        val mdOutput = MarkdownConverter.plainTextToMarkdown(plainInput)
        assertThat(mdOutput).contains("\\# Fake heading")
        assertThat(mdOutput).contains("\\> Fake quote")
    }
}
