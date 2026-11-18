package org.samesame.notes.core.richtext.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.Block
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.samesame.notes.core.model.Limits

/**
 * CommonMark plus the GitHub extras the preview draws: tables, ~~strikethrough~~, bare-URL links and task lists.
 * Raw HTML is never interpreted (the preview shows it as text), so there is no sanitising to get wrong.
 */
object MarkdownParser {
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
                TaskListItemsExtension.create(),
            ),
        )
        // Block spans give each list item its offset in the source, which a task toggle needs.
        .includeSourceSpans(IncludeSourceSpans.BLOCKS)
        .build()

    /** True when [markdown] is too large to parse for the live preview ([Limits.MAX_MARKDOWN_CHARS]). */
    fun isTooLarge(markdown: String): Boolean = markdown.length > Limits.MAX_MARKDOWN_CHARS

    /**
     * The document node, or null when the text is over [Limits.MAX_MARKDOWN_CHARS] (the host then offers plain
     * editing without a preview). Nesting deeper than [Limits.MAX_MARKDOWN_AST_DEPTH] is cut off, so a hostile file
     * of `> > > > ...` cannot overflow the stack of the renderer.
     */
    fun parse(markdown: String): Node? {
        if (isTooLarge(markdown)) return null
        val document = parser.parse(markdown)
        prune(document, 1, IntArray(1))
        return document
    }

    /**
     * Drops every child below the depth limit and every block after the first [Limits.MAX_MARKDOWN_PREVIEW_NODES]
     * (document order), so the renderer needs no limits of its own. Recursion here is bounded by the depth limit.
     */
    private fun prune(node: Node, depth: Int, blocks: IntArray) {
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            val tooDeep = depth >= Limits.MAX_MARKDOWN_AST_DEPTH
            val tooMany = child is Block && ++blocks[0] > Limits.MAX_MARKDOWN_PREVIEW_NODES
            if (tooDeep || tooMany) child.unlink() else prune(child, depth + 1, blocks)
            child = next
        }
    }

    /**
     * Offset in [source] of the `[` of a task item's `[ ]` / `[x]` marker, or -1 when it cannot be found. Needs a node
     * made by [parse] (block source spans).
     */
    fun taskMarkerOffset(source: String, item: ListItem): Int {
        val start = item.sourceSpans.firstOrNull()?.inputIndex ?: return -1
        if (start !in source.indices) return -1
        return source.indexOf('[', start)
    }

    /** [source] with the task marker at [markerOffset] set to checked or unchecked; unchanged if there is no marker. */
    fun withTaskChecked(source: String, markerOffset: Int, checked: Boolean): String {
        if (markerOffset < 0 || markerOffset + 2 >= source.length) return source
        if (source[markerOffset] != '[' || source[markerOffset + 2] != ']') return source
        if (source[markerOffset + 1] !in " xX") return source
        return source.substring(0, markerOffset + 1) + (if (checked) 'x' else ' ') + source.substring(markerOffset + 2)
    }
}
