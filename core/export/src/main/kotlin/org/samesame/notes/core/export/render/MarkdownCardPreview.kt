package org.samesame.notes.core.export.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import kotlinx.coroutines.CancellationException
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
import org.samesame.notes.core.export.text.MarkdownTrees
import org.samesame.notes.core.model.Limits
import java.io.ByteArrayOutputStream

/**
 * Headless card preview renderer for Markdown notes.
 * Parses the first blocks of Markdown into a formatted SpannableStringBuilder and renders
 * onto a bitmap via Android's StaticLayout for display in home note cards.
 */
object MarkdownCardPreview {

    private const val MAX_PREVIEW_BLOCKS = 15
    private const val BASE_PAGE_WIDTH = 1080f
    private const val BASE_PAGE_HEIGHT = 1528f

    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
        AutolinkExtension.create(),
    )

    private val parser = Parser.builder().extensions(extensions).build()

    fun render(source: String, targetWidthPx: Int, darkTheme: Boolean): ByteArray? {
        try {
            if (source.isBlank()) return null
            val document = MarkdownTrees.parse(parser, source)

            val widthPx = targetWidthPx.coerceIn(64, 4096)
            val heightPx = (widthPx * BASE_PAGE_HEIGHT / BASE_PAGE_WIDTH).toInt().coerceIn(64, 8192)
            val scale = widthPx.toFloat() / BASE_PAGE_WIDTH

            val bgColor = if (darkTheme) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
            val textColor = if (darkTheme) 0xFFE6EDF3.toInt() else 0xFF1F2328.toInt()
            val codeBgColor = if (darkTheme) 0xFF21262D.toInt() else 0xFFF6F8FA.toInt()
            val linkColor = if (darkTheme) 0xFF58A6FF.toInt() else 0xFF0969DA.toInt()

            val ssb = SpannableStringBuilder()
            var blockCount = 0
            var child = document.firstChild

            while (child != null && blockCount < MAX_PREVIEW_BLOCKS) {
                renderBlock(child, ssb, textColor, codeBgColor, linkColor)
                blockCount++
                child = child.next
            }

            if (ssb.isEmpty()) return null

            val padding = (48f * scale).coerceAtLeast(8f)
            val textWidth = (widthPx - 2 * padding).toInt().coerceAtLeast(1)
            val fontSizePx = (Limits.DEFAULT_FONT_SIZE * Limits.FONT_PU_PER_POINT * scale).coerceAtLeast(10f)

            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = fontSizePx
            }

            val layout = StaticLayout.Builder.obtain(ssb, 0, ssb.length, textPaint, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.2f)
                .setIncludePad(false)
                .build()

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(bgColor)
                canvas.save()
                canvas.clipRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
                canvas.translate(padding, padding)
                layout.draw(canvas)
                canvas.restore()

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                return stream.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        }
    }

    private fun renderBlock(node: Node, ssb: SpannableStringBuilder, textColor: Int, codeBgColor: Int, linkColor: Int) {
        when (node) {
            is Heading -> {
                val start = ssb.length
                renderInlines(node, ssb, codeBgColor, linkColor)
                val end = ssb.length
                if (end > start) {
                    val sizeMult = when (node.level) {
                        1 -> 1.8f
                        2 -> 1.4f
                        3 -> 1.2f
                        else -> 1.1f
                    }
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(RelativeSizeSpan(sizeMult), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append("\n\n")
            }
            is Paragraph -> {
                renderInlines(node, ssb, codeBgColor, linkColor)
                ssb.append("\n\n")
            }
            is BulletList -> {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        val task = findTaskMarker(item)
                        if (task != null) {
                            ssb.append(if (task.isChecked) "☑ " else "☐ ")
                        } else {
                            ssb.append("• ")
                        }
                        renderInlines(item, ssb, codeBgColor, linkColor, skipTaskMarker = true)
                        ssb.append("\n")
                    }
                    item = item.next
                }
                ssb.append("\n")
            }
            is OrderedList -> {
                var item = node.firstChild
                var num = 1
                while (item != null) {
                    if (item is ListItem) {
                        val task = findTaskMarker(item)
                        if (task != null) {
                            ssb.append(if (task.isChecked) "☑ " else "☐ ")
                        } else {
                            ssb.append("${num++}. ")
                        }
                        renderInlines(item, ssb, codeBgColor, linkColor, skipTaskMarker = true)
                        ssb.append("\n")
                    }
                    item = item.next
                }
                ssb.append("\n")
            }
            is FencedCodeBlock -> {
                val start = ssb.length
                ssb.append(node.literal.trimEnd('\n'))
                val end = ssb.length
                if (end > start) {
                    ssb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(codeBgColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append("\n\n")
            }
            is IndentedCodeBlock -> {
                val start = ssb.length
                ssb.append(node.literal.trimEnd('\n'))
                val end = ssb.length
                if (end > start) {
                    ssb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(codeBgColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append("\n\n")
            }
            is TableBlock -> {
                renderTablePreview(node, ssb)
                ssb.append("\n\n")
            }
            is BlockQuote -> {
                val start = ssb.length
                var c = node.firstChild
                while (c != null) {
                    if (c is Paragraph) renderInlines(c, ssb, codeBgColor, linkColor)
                    c = c.next
                }
                val end = ssb.length
                if (end > start) {
                    ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append("\n\n")
            }
            else -> {
                var c = node.firstChild
                while (c != null) {
                    renderBlock(c, ssb, textColor, codeBgColor, linkColor)
                    c = c.next
                }
            }
        }
    }

    private fun renderInlines(
        node: Node,
        ssb: SpannableStringBuilder,
        codeBgColor: Int,
        linkColor: Int,
        skipTaskMarker: Boolean = false,
    ) {
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is TaskListItemMarker -> {
                    if (!skipTaskMarker) {
                        ssb.append(if (child.isChecked) "☑ " else "☐ ")
                    }
                }
                is Text -> ssb.append(child.literal)
                is Code -> {
                    val start = ssb.length
                    ssb.append(child.literal)
                    val end = ssb.length
                    ssb.setSpan(TypefaceSpan("monospace"), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(codeBgColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Emphasis -> {
                    val start = ssb.length
                    renderInlines(child, ssb, codeBgColor, linkColor, skipTaskMarker)
                    val end = ssb.length
                    if (end > start) {
                        ssb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is StrongEmphasis -> {
                    val start = ssb.length
                    renderInlines(child, ssb, codeBgColor, linkColor, skipTaskMarker)
                    val end = ssb.length
                    if (end > start) {
                        ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Strikethrough -> {
                    val start = ssb.length
                    renderInlines(child, ssb, codeBgColor, linkColor, skipTaskMarker)
                    val end = ssb.length
                    if (end > start) {
                        ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is Link -> {
                    val start = ssb.length
                    renderInlines(child, ssb, codeBgColor, linkColor, skipTaskMarker)
                    val end = ssb.length
                    if (end > start) {
                        ssb.setSpan(ForegroundColorSpan(linkColor), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                is SoftLineBreak -> ssb.append(" ")
                is HardLineBreak -> ssb.append("\n")
                else -> renderInlines(child, ssb, codeBgColor, linkColor, skipTaskMarker)
            }
            child = child.next
        }
    }

    private fun renderTablePreview(table: TableBlock, ssb: SpannableStringBuilder) {
        fun extractText(node: Node): String {
            val sb = java.lang.StringBuilder()
            fun walk(n: Node) {
                if (n is Text) sb.append(n.literal)
                if (n is Code) sb.append(n.literal)
                var c = n.firstChild
                while (c != null) { walk(c); c = c.next }
            }
            walk(node)
            return sb.toString().trim()
        }

        var row = table.firstChild
        var rowCount = 0
        while (row != null && rowCount < 5) {
            val cells = ArrayList<String>()
            var cell = row.firstChild
            while (cell != null) {
                if (cell is TableCell) cells += extractText(cell)
                else {
                    var inner = cell.firstChild
                    while (inner != null) {
                        if (inner is TableRow) {
                            var innerCell = inner.firstChild
                            while (innerCell != null) {
                                if (innerCell is TableCell) cells += extractText(innerCell)
                                innerCell = innerCell.next
                            }
                        }
                        inner = inner.next
                    }
                }
                cell = cell.next
            }
            if (cells.isNotEmpty()) {
                ssb.append(cells.joinToString(" | ")).append("\n")
                rowCount++
            }
            row = row.next
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
}
