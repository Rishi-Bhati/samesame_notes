package org.samesame.notes.core.richtext.markdown

import com.google.common.truth.Truth.assertThat
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.Block
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.junit.Test
import org.samesame.notes.core.model.Limits

class MarkdownParserTest {
    private fun descendants(parent: Node, out: MutableList<Node> = mutableListOf()): List<Node> {
        var n = parent.firstChild
        while (n != null) { out += n; descendants(n, out); n = n.next }
        return out
    }

    private inline fun <reified T : Node> all(root: Node): List<T> = descendants(root).filterIsInstance<T>()

    @Test fun `headings carry their level`() {
        val doc = MarkdownParser.parse("# One\n\n### Three\n")!!
        assertThat(all<Heading>(doc).map { it.level }).containsExactly(1, 3).inOrder()
    }

    @Test fun `gfm table strikethrough and autolink are parsed`() {
        val doc = MarkdownParser.parse("| a | b |\n|---|---|\n| 1 | 2 |\n\n~~gone~~ and https://example.com/x\n")!!
        assertThat(all<TableBlock>(doc)).hasSize(1)
        assertThat(all<Strikethrough>(doc)).hasSize(1)
        assertThat(all<Link>(doc).map { it.destination }).containsExactly("https://example.com/x")
    }

    @Test fun `task list items are marked and know their source offset`() {
        val src = "- [ ] open\n- [x] done\n- plain\n"
        val doc = MarkdownParser.parse(src)!!
        val markers = all<TaskListItemMarker>(doc)
        assertThat(markers.map { it.isChecked }).containsExactly(false, true).inOrder()
        val offsets = all<ListItem>(doc).filter { it.firstChild is TaskListItemMarker }.map { MarkdownParser.taskMarkerOffset(src, it) }
        assertThat(offsets).containsExactly(2, 13).inOrder()
        assertThat(MarkdownParser.withTaskChecked(src, 2, true)).isEqualTo("- [x] open\n- [x] done\n- plain\n")
        assertThat(MarkdownParser.withTaskChecked(src, 13, false)).isEqualTo("- [ ] open\n- [ ] done\n- plain\n")
        // Not a marker: untouched.
        assertThat(MarkdownParser.withTaskChecked(src, 0, true)).isEqualTo(src)
        assertThat(MarkdownParser.withTaskChecked(src, 999, true)).isEqualTo(src)
    }

    @Test fun `text over the length cap is refused`() {
        val big = "a".repeat(Limits.MAX_MARKDOWN_CHARS + 1)
        assertThat(MarkdownParser.isTooLarge(big)).isTrue()
        assertThat(MarkdownParser.parse(big)).isNull()
        assertThat(MarkdownParser.parse("a".repeat(Limits.MAX_MARKDOWN_CHARS))).isNotNull()
    }

    @Test fun `nesting thousands of levels deep does not crash`() {
        for (md in listOf("> ".repeat(200_000) + "deep\n", "*".repeat(100_000) + "deep" + "*".repeat(100_000))) {
            val doc = MarkdownParser.parse(md)!!
            var depth = 0
            var n: Node? = doc
            while (n?.firstChild != null) { n = n.firstChild; depth++ }
            assertThat(depth).isAtMost(Limits.MAX_MARKDOWN_AST_DEPTH)
        }
    }

    @Test fun `nesting deeper than the cap is cut off`() {
        val doc = MarkdownParser.parse("> ".repeat(500) + "deep\n")!!
        var depth = 0
        var n: Node? = doc
        while (n?.firstChild != null) { n = n.firstChild; depth++ }
        assertThat(depth).isAtMost(Limits.MAX_MARKDOWN_AST_DEPTH)
    }

    @Test fun `block count is capped`() {
        val doc = MarkdownParser.parse("x\n\n".repeat(Limits.MAX_MARKDOWN_PREVIEW_NODES + 500))!!
        assertThat(all<Block>(doc).size).isAtMost(Limits.MAX_MARKDOWN_PREVIEW_NODES)
    }
}
