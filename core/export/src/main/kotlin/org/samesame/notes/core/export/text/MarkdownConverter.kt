package org.samesame.notes.core.export.text

import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.samesame.notes.core.export.ExportAssets
import org.samesame.notes.core.export.RenderedImage
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.model.richtext.TextSizeCatalog

enum class LossyFeature { TABLES, CODE_BLOCKS, HTML, BLOCK_QUOTES, MATH }

/**
 * Bi-directional converter between Markdown and SameSame's internal RichText document representation.
 * - Lossless conversion for headings, lists, checklists, bold, italic, strikethrough, links.
 * - Detects lossy constructs (tables, code blocks, HTML, block quotes, math) that cannot be fully
 *   preserved when converting to rich text.
 */
object MarkdownConverter {

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
    )

    private val parser = Parser.builder().extensions(extensions).build()

    private val mathRegex = Regex("""(?:\$\$[\s\S]+?\$\$|\$(?!\s)[^$\n]+(?<!\s)\$)""")

    fun detectLossyFeatures(markdown: String): Set<LossyFeature> {
        val features = LinkedHashSet<LossyFeature>()
        if (mathRegex.containsMatchIn(markdown)) {
            features += LossyFeature.MATH
        }

        val document = MarkdownTrees.parse(parser, markdown)
        fun walk(node: Node) {
            when (node) {
                is TableBlock -> features += LossyFeature.TABLES
                is FencedCodeBlock, is IndentedCodeBlock -> features += LossyFeature.CODE_BLOCKS
                is HtmlBlock, is HtmlInline -> features += LossyFeature.HTML
                is BlockQuote -> features += LossyFeature.BLOCK_QUOTES
            }
            var c = node.firstChild
            while (c != null) {
                walk(c)
                c = c.next
            }
        }
        walk(document)
        return features
    }

    fun markdownToRichText(markdown: String): RichText {
        if (markdown.isEmpty()) return RichText()

        val document = MarkdownTrees.parse(parser, markdown)
        val paragraphs = ArrayList<ParaBuilder>()

        fun processBlock(node: Node, indent: Int = 0) {
            when (node) {
                is Heading -> {
                    val size = when (node.level) {
                        1 -> TextSizeCatalog.Heading.H1.size.toFloat()
                        2 -> TextSizeCatalog.Heading.H2.size.toFloat()
                        3 -> TextSizeCatalog.Heading.H3.size.toFloat()
                        else -> Limits.DEFAULT_FONT_SIZE
                    }
                    val baseStyle = CharStyle(bold = true, fontSize = size)
                    val p = ParaBuilder(ParagraphStyle(indent = indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)))
                    collectInlines(node, p, baseStyle)
                    paragraphs += p
                }
                is Paragraph -> {
                    val p = ParaBuilder(ParagraphStyle(indent = indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)))
                    collectInlines(node, p, CharStyle())
                    paragraphs += p
                }
                is BulletList -> {
                    var item = node.firstChild
                    while (item != null) {
                        if (item is ListItem) processListItem(item, ListKind.Bullet, indent, paragraphs)
                        item = item.next
                    }
                }
                is OrderedList -> {
                    var item = node.firstChild
                    while (item != null) {
                        if (item is ListItem) processListItem(item, ListKind.Numbered, indent, paragraphs)
                        item = item.next
                    }
                }
                is FencedCodeBlock -> {
                    for (line in node.literal.trimEnd('\n').lines()) {
                        val p = ParaBuilder(ParagraphStyle(indent = indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)))
                        p.addRun(line, CharStyle())
                        paragraphs += p
                    }
                }
                is IndentedCodeBlock -> {
                    for (line in node.literal.trimEnd('\n').lines()) {
                        val p = ParaBuilder(ParagraphStyle(indent = indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)))
                        p.addRun(line, CharStyle())
                        paragraphs += p
                    }
                }
                is TableBlock -> {
                    flattenTable(node, paragraphs, indent)
                }
                is BlockQuote -> {
                    var c = node.firstChild
                    while (c != null) {
                        processBlock(c, indent + 1)
                        c = c.next
                    }
                }
                else -> {
                    var c = node.firstChild
                    while (c != null) {
                        processBlock(c, indent)
                        c = c.next
                    }
                }
            }
        }

        var block = document.firstChild
        while (block != null) {
            processBlock(block)
            block = block.next
        }

        if (paragraphs.isEmpty()) return RichText()

        val fullText = StringBuilder()
        val runs = ArrayList<TextRun>()
        val styles = ArrayList<ParagraphStyle>()

        for ((index, p) in paragraphs.withIndex()) {
            if (index > 0) {
                fullText.append('\n')
                runs += TextRun(1, CharStyle())
            }
            styles += p.style
            for (run in p.runs) {
                if (run.length > 0) {
                    fullText.append(run.text)
                    runs += TextRun(run.length, run.style)
                }
            }
        }

        return RichTextOps.normalise(RichText(fullText.toString(), runs, styles))
    }

    private fun processListItem(item: ListItem, listKind: ListKind, indent: Int, paragraphs: ArrayList<ParaBuilder>) {
        val taskMarker = findTaskMarker(item)
        val isCheckbox = taskMarker != null
        val isChecked = taskMarker?.isChecked ?: false
        // For checklists without numbered ordering, list is None so it displays with a checkbox only
        val finalKind = if (isCheckbox && listKind == ListKind.Bullet) ListKind.None else listKind

        val style = ParagraphStyle(
            list = finalKind,
            checkbox = isCheckbox,
            checked = isChecked,
            indent = indent.coerceIn(0, Limits.MAX_INDENT_LEVEL),
        )

        var child = item.firstChild
        var firstPara = true
        while (child != null) {
            when (child) {
                is Paragraph -> {
                    val p = ParaBuilder(if (firstPara) style else ParagraphStyle(indent = (indent + 1).coerceIn(0, Limits.MAX_INDENT_LEVEL)))
                    collectInlines(child, p, CharStyle(), skipTaskMarker = true)
                    paragraphs.add(p)
                    firstPara = false
                }
                is BulletList -> {
                    var sub = child.firstChild
                    while (sub != null) {
                        if (sub is ListItem) processListItem(sub, ListKind.Bullet, indent + 1, paragraphs)
                        sub = sub.next
                    }
                }
                is OrderedList -> {
                    var sub = child.firstChild
                    while (sub != null) {
                        if (sub is ListItem) processListItem(sub, ListKind.Numbered, indent + 1, paragraphs)
                        sub = sub.next
                    }
                }
                else -> {
                    // Other block in list item
                }
            }
            child = child.next
        }
        if (firstPara) {
            // Empty list item
            paragraphs.add(ParaBuilder(style))
        }
    }

    private fun collectInlines(node: Node, p: ParaBuilder, currentStyle: CharStyle, skipTaskMarker: Boolean = false) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is TaskListItemMarker -> {
                    if (!skipTaskMarker) {
                        val mark = if (child.isChecked) "[x] " else "[ ] "
                        p.addRun(mark, currentStyle)
                    }
                }
                is Text -> p.addRun(child.literal, currentStyle)
                is Code -> p.addRun(child.literal, currentStyle)
                is Emphasis -> collectInlines(child, p, currentStyle.copy(italic = true), skipTaskMarker)
                is StrongEmphasis -> collectInlines(child, p, currentStyle.copy(bold = true), skipTaskMarker)
                is Strikethrough -> collectInlines(child, p, currentStyle.copy(strikethrough = true), skipTaskMarker)
                is SoftLineBreak -> p.addRun(" ", currentStyle)
                is HardLineBreak -> p.addRun("\n", currentStyle)
                is Link -> collectInlines(child, p, currentStyle.copy(link = child.destination), skipTaskMarker)
                else -> collectInlines(child, p, currentStyle, skipTaskMarker)
            }
            child = child.next
        }
    }

    private fun findTaskMarker(item: ListItem): TaskListItemMarker? {
        var node: Node? = item.firstChild
        while (node != null) {
            if (node is TaskListItemMarker) return node
            if (node is Paragraph) {
                var pNode = node.firstChild
                while (pNode != null) {
                    if (pNode is TaskListItemMarker) return pNode
                    pNode = pNode.next
                }
            }
            node = node.next
        }
        return null
    }

    private fun flattenTable(table: TableBlock, paragraphs: ArrayList<ParaBuilder>, indent: Int) {
        fun collectCellText(node: Node): String {
            val sb = StringBuilder()
            fun walk(n: Node) {
                if (n is Text) sb.append(n.literal)
                if (n is Code) sb.append(n.literal)
                if (n is SoftLineBreak) sb.append(' ')
                var c = n.firstChild
                while (c != null) { walk(c); c = c.next }
            }
            walk(node)
            return sb.toString().trim()
        }

        fun processRow(row: TableRow) {
            val cells = ArrayList<String>()
            var cell = row.firstChild
            while (cell != null) {
                if (cell is TableCell) cells += collectCellText(cell)
                cell = cell.next
            }
            val line = cells.joinToString(" | ")
            val p = ParaBuilder(ParagraphStyle(indent = indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)))
            p.addRun(line, CharStyle())
            paragraphs += p
        }

        var child = table.firstChild
        while (child != null) {
            if (child is TableRow) processRow(child)
            else {
                var row = child.firstChild
                while (row != null) {
                    if (row is TableRow) processRow(row)
                    row = row.next
                }
            }
            child = child.next
        }
    }

    fun richTextToMarkdown(doc: NoteDocument, writeMedia: Boolean = false): MarkdownResult =
        MarkdownExport.render(doc, EmptyAssets, writeMedia)

    fun plainTextToMarkdown(plainText: String): String {
        val lines = plainText.lines()
        val sb = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(escapeBlockStart(line))
        }
        return sb.toString()
    }

    fun markdownToPlainText(markdown: String): String =
        markdownToRichText(markdown).plainText()

    private fun escapeBlockStart(s: String): String = when {
        s.startsWith("#") || s.startsWith("+") || s.startsWith("-") || s.startsWith("=") || s.startsWith(">") -> "\\$s"
        Regex("^\\d+[.)](?: |$)").containsMatchIn(s) -> s.replaceFirst(Regex("^(\\d+)([.)])"), "$1\\\\$2")
        else -> s
    }

    private class RunData(val text: String, val style: CharStyle) {
        val length: Int get() = text.length
    }

    private class ParaBuilder(val style: ParagraphStyle) {
        val runs = ArrayList<RunData>()
        fun addRun(text: String, style: CharStyle) {
            if (text.isNotEmpty()) runs += RunData(text, style)
        }
    }

    private object EmptyAssets : ExportAssets {
        override val pageCount: Int = 1
        override val heightOverWidth: Float = 1.4142857f
        override fun media(path: String): ByteArray? = null
        override fun inkLayer(pageIndex: Int): RenderedImage? = null
        override fun fullPage(pageIndex: Int): RenderedImage = RenderedImage(ByteArray(0), 1080, 1528)
        override fun floatingTexts(pageIndex: Int): List<RichText> = emptyList()
        override fun pageText(pageIndex: Int): String = ""
    }
}
