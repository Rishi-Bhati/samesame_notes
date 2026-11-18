package org.samesame.notes.core.richtext.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
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
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.samesame.notes.core.designsystem.theme.SameSameColors
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.Limits

/**
 * Read-only rendering of Markdown text. Everything is drawn from [SameSameTheme] colours, nothing is hard-coded.
 *
 * [onTaskCheckedChange] gets the source offset of the tapped item's `[` (see [MarkdownParser.withTaskChecked]) and the
 * new state; without it the task boxes are drawn but do nothing. [onLinkClick] replaces opening a link in the
 * browser, which is what happens when it is null. Text over [Limits.MAX_MARKDOWN_CHARS] draws nothing: the host
 * shows its own "too large" hint ([MarkdownParser.isTooLarge]).
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    modifier: Modifier = Modifier,
    onTaskCheckedChange: ((charOffset: Int, isChecked: Boolean) -> Unit)? = null,
    onLinkClick: ((url: String) -> Unit)? = null,
) {
    val document = remember(markdown) { MarkdownParser.parse(markdown) } ?: return
    val colors = SameSameTheme.colors
    val ctx = remember(markdown, colors, onTaskCheckedChange, onLinkClick) {
        RenderContext(markdown, colors, onTaskCheckedChange, onLinkClick)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Blocks(document, ctx)
    }
}

/** What every block needs. */
private class RenderContext(
    val source: String,
    val colors: SameSameColors,
    val onTask: ((Int, Boolean) -> Unit)?,
    val onLink: ((String) -> Unit)?,
) {
}

@Composable
private fun Blocks(parent: Node, ctx: RenderContext) {
    var child = parent.firstChild
    while (child != null) {
        Block(child, ctx)
        child = child.next
    }
}

private val HeadingSizes = listOf(28.sp, 24.sp, 20.sp, 17.sp, 15.sp, 13.sp)

@Composable
private fun Block(node: Node, ctx: RenderContext) {
    val c = ctx.colors
    val body = SameSameText.ListBody.copy(color = c.textPrimary)
    when (node) {
        is Heading -> {
            val size = HeadingSizes[(node.level - 1).coerceIn(0, 5)]
            BasicText(
                inlines(node, ctx),
                style = body.copy(fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.Bold),
            )
        }
        is Paragraph -> BasicText(inlines(node, ctx), style = body)
        is BlockQuote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(c.accent))
            Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Quote text is the secondary colour and italic, also for nested paragraphs.
                QuoteBlocks(node, ctx)
            }
        }
        is FencedCodeBlock -> CodeBlock(node.literal, ctx)
        is IndentedCodeBlock -> CodeBlock(node.literal, ctx)
        is HtmlBlock -> BasicText(node.literal.trimEnd(), style = body.copy(color = c.textSecondary, fontFamily = FontFamily.Monospace))
        is ThematicBreak -> Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        is BulletList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Items(node, ctx, ordered = false, start = 1) }
        is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Items(node, ctx, ordered = true, start = node.markerStartNumber ?: 1)
        }
        is TableBlock -> Table(node, ctx)
        else -> BasicText(inlines(node, ctx), style = body)
    }
}

@Composable
private fun QuoteBlocks(quote: BlockQuote, ctx: RenderContext) {
    val c = ctx.colors
    val style = SameSameText.ListBody.copy(color = c.textSecondary, fontStyle = FontStyle.Italic)
    var child = quote.firstChild
    while (child != null) {
        if (child is Paragraph) BasicText(inlines(child, ctx), style = style) else Block(child, ctx)
        child = child.next
    }
}

@Composable
private fun Items(list: Node, ctx: RenderContext, ordered: Boolean, start: Int) {
    var index = start
    var item = list.firstChild
    while (item != null) {
        if (item is ListItem) {
            val first = item.firstChild
            Row {
                if (first is TaskListItemMarker) {
                    TaskBox(first.isChecked, MarkdownParser.taskMarkerOffset(ctx.source, item), ctx)
                } else {
                    val label = if (ordered) "$index." else "•"
                    BasicText(
                        label,
                        Modifier.width(if (ordered) 28.dp else 20.dp),
                        style = SameSameText.ListBody.copy(color = ctx.colors.textPrimary),
                    )
                }
                Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    var child = if (first is TaskListItemMarker) first.next else first
                    while (child != null) {
                        Block(child, ctx)
                        child = child.next
                    }
                }
            }
            index++
        }
        item = item.next
    }
}

@Composable
private fun TaskBox(checked: Boolean, offset: Int, ctx: RenderContext) {
    val c = ctx.colors
    val toggle = ctx.onTask
    val shape = RoundedCornerShape(4.dp)
    val base = Modifier.padding(end = 8.dp, top = 2.dp).size(18.dp).testTag("md-task-$offset")
    val shown = if (checked) base.background(c.accent, shape) else base.border(2.dp, c.checkOutline, shape)
    val clickable = if (toggle != null && offset >= 0) {
        shown.clickable(role = Role.Checkbox) { toggle(offset, !checked) }
    } else shown
    Box(clickable, contentAlignment = Alignment.Center) {
        if (checked) BasicText("✓", style = TextStyle(color = c.background, fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun CodeBlock(literal: String, ctx: RenderContext) {
    val c = ctx.colors
    val text = literal.trimEnd('\n').let { if (it.length > Limits.MAX_MARKDOWN_CODE_CHARS) it.take(Limits.MAX_MARKDOWN_CODE_CHARS) else it }
    Box(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(12.dp)) {
        BasicText(text, style = TextStyle(color = c.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp))
    }
}

@Composable
private fun Table(table: TableBlock, ctx: RenderContext) {
    val c = ctx.colors
    val rows = buildList {
        fun addRows(section: Node?) {
            var row = section?.firstChild
            while (row != null && size < Limits.MAX_MARKDOWN_TABLE_ROWS) { if (row is TableRow) add(row); row = row.next }
        }
        var section = table.firstChild
        while (section != null) {
            if (section is TableHead || section is TableBody) addRows(section)
            section = section.next
        }
    }
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, c.divider)) {
        for (row in rows) {
            Row {
                var cell = row.firstChild
                var col = 0
                while (cell != null && col < Limits.MAX_MARKDOWN_TABLE_COLS) {
                    if (cell is TableCell) {
                        val align = when (cell.alignment) {
                            TableCell.Alignment.CENTER -> TextAlign.Center
                            TableCell.Alignment.RIGHT -> TextAlign.Right
                            else -> TextAlign.Left
                        }
                        val header = cell.isHeader
                        BasicText(
                            inlines(cell, ctx),
                            Modifier
                                .width(140.dp)
                                .border(1.dp, c.divider)
                                .then(if (header) Modifier.background(c.elevated) else Modifier)
                                .padding(8.dp),
                            style = SameSameText.ListBody.copy(
                                color = c.textPrimary, textAlign = align, fontWeight = if (header) FontWeight.Bold else null,
                            ),
                        )
                    }
                    cell = cell.next
                    col++
                }
            }
        }
    }
}

/** The inline children of [parent] as one styled string; nesting is bounded by the parser's depth limit. */
@Composable
private fun inlines(parent: Node, ctx: RenderContext): AnnotatedString {
    val c = ctx.colors
    val link = TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline))
    return remember(parent, ctx) {
        buildAnnotatedString { appendInlines(parent, c, link, ctx.onLink) }
    }
}

private fun AnnotatedString.Builder.appendInlines(parent: Node, c: SameSameColors, link: TextLinkStyles, onLink: ((String) -> Unit)?) {
    var node = parent.firstChild
    while (node != null) {
        when (node) {
            is Text -> append(node.literal)
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(node, c, link, onLink) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(node, c, link, onLink) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(node, c, link, onLink) }
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.card)) { append(node.literal) }
            is Link -> {
                val url = node.destination.orEmpty()
                val annotation = if (onLink != null) {
                    LinkAnnotation.Clickable("md-link", link) { onLink(url) }
                } else {
                    LinkAnnotation.Url(url, link)
                }
                withLink(annotation) { appendInlines(node, c, link, onLink) }
            }
            // No remote images in the preview: the alt text stands in, in the link colour.
            is Image -> withStyle(SpanStyle(color = c.textSecondary)) { appendInlines(node, c, link, onLink) }
            is HtmlInline -> append(node.literal)
            else -> appendInlines(node, c, link, onLink)
        }
        node = node.next
    }
}
