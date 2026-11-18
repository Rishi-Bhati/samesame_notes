package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.export.text.MarkdownExport
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle

class MarkdownExportTest {

    @Test
    fun testHeadingExport() {
        val rt = rich(
            Para(ParagraphStyle(), "Main Heading" to CharStyle(bold = true, fontSize = 32f)),
            Para(ParagraphStyle(), "Sub Heading" to CharStyle(bold = true, fontSize = 24f)),
            Para(ParagraphStyle(), "Section Heading" to CharStyle(bold = true, fontSize = 20f)),
            Para(ParagraphStyle(), "Plain Bold" to CharStyle(bold = true, fontSize = 17f)),
        )
        val md = MarkdownExport.render(sampleDoc(rt, title = ""), FakeAssets(), false).markdown
        assertThat(md).contains("# Main Heading\n\n## Sub Heading\n\n### Section Heading\n\n**Plain Bold**")
    }

    @Test
    fun testTaskLists() {
        val rt = rich(
            Para(ParagraphStyle(checkbox = true, checked = false), "Task item 1" to PLAIN),
            Para(ParagraphStyle(checkbox = true, checked = true), "Task item 2" to PLAIN),
            Para(ParagraphStyle(list = ListKind.Numbered, checkbox = true, checked = false), "Ordered task" to PLAIN),
        )
        val md = MarkdownExport.render(sampleDoc(rt, title = ""), FakeAssets(), false).markdown
        assertThat(md).contains("- [ ] Task item 1\n- [x] Task item 2\n1. [ ] Ordered task")
    }

    @Test
    fun testNestedIndentation() {
        val rt = rich(
            Para(ParagraphStyle(list = ListKind.Bullet, indent = 0), "Level 0" to PLAIN),
            Para(ParagraphStyle(list = ListKind.Bullet, indent = 1), "Level 1" to PLAIN),
            Para(ParagraphStyle(list = ListKind.Bullet, indent = 2), "Level 2" to PLAIN),
            Para(ParagraphStyle(list = ListKind.Numbered, indent = 1), "Numbered L1" to PLAIN),
            Para(ParagraphStyle(indent = 2), "Plain Indented Paragraph" to PLAIN),
        )
        val md = MarkdownExport.render(sampleDoc(rt, title = ""), FakeAssets(), false).markdown
        assertThat(md).contains("- Level 0\n    - Level 1\n        - Level 2\n    1. Numbered L1\n\nPlain Indented Paragraph")
        // Plain indented paragraph must not have 4 leading spaces (which would turn it into a code block)
        assertThat(md).doesNotContain("    Plain Indented Paragraph")
    }

    @Test
    fun testBlockStartEscaping() {
        val rt = rich(
            Para(ParagraphStyle(), "# Not A Heading" to PLAIN),
            Para(ParagraphStyle(), "> Not A Quote" to PLAIN),
            Para(ParagraphStyle(), "+ Not A List" to PLAIN),
            Para(ParagraphStyle(), "- Not A List" to PLAIN),
            Para(ParagraphStyle(), "= Not A Header" to PLAIN),
            Para(ParagraphStyle(), "1. Not A Numbered List" to PLAIN),
        )
        val md = MarkdownExport.render(sampleDoc(rt, title = ""), FakeAssets(), false).markdown
        assertThat(md).contains("\\# Not A Heading")
        assertThat(md).contains("\\> Not A Quote")
        assertThat(md).contains("\\+ Not A List")
        assertThat(md).contains("\\- Not A List")
        assertThat(md).contains("\\= Not A Header")
        assertThat(md).contains("1\\. Not A Numbered List")
    }
}
