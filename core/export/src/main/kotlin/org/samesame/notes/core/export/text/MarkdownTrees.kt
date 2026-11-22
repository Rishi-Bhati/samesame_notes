package org.samesame.notes.core.export.text

import org.commonmark.node.Document
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.samesame.notes.core.model.Limits

/**
 * Parsing for the Markdown exporters that a hostile file cannot crash. CommonMark's visitors (the autolink post-processor
 * while parsing, every renderer afterwards) recurse once per nesting level, so `> > > ...` or `****...x...****` a few
 * thousand levels deep overflows the stack. The tree is cut at [Limits.MAX_MARKDOWN_AST_DEPTH]; when parsing itself
 * overflows, the text is kept as plain paragraphs, one per line.
 */
internal object MarkdownTrees {

    fun parse(parser: Parser, markdown: String): Node {
        val document = try {
            parser.parse(markdown)
        } catch (_: StackOverflowError) {
            return plain(markdown)
        }
        prune(document, 1)
        return document
    }

    /** Drops every node below the depth limit. Recursion here is bounded by that limit. */
    private fun prune(node: Node, depth: Int) {
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            if (depth >= Limits.MAX_MARKDOWN_AST_DEPTH) child.unlink() else prune(child, depth + 1)
            child = next
        }
    }

    private fun plain(markdown: String): Node {
        val document = Document()
        markdown.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            document.appendChild(Paragraph().apply { appendChild(Text(line)) })
        }
        return document
    }
}
