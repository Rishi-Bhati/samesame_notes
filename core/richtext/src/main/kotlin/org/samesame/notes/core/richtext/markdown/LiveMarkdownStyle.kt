package org.samesame.notes.core.richtext.markdown

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import org.samesame.notes.core.designsystem.theme.SameSameColors
import kotlin.math.abs

/**
 * Styles of the live Markdown editor, all from the theme colours. Hidden syntax keeps its characters (so offsets,
 * the caret, IME composition and saving are untouched) but draws them transparent at a near-zero size; prefix syntax
 * that is replaced by a drawing (bullet dot, check box, quote bar, rule) stays full size and transparent, so the
 * drawing has the room the characters take.
 */
class LiveMarkdownStyle(val colors: SameSameColors) {
    internal val hidden = SpanStyle(color = Color.Transparent, fontSize = 0.01.em, letterSpacing = 0.em)
    internal val transparent = SpanStyle(color = Color.Transparent)
    internal val marker = SpanStyle(color = colors.textTertiary)
    internal val headings = HeadingScale.map { SpanStyle(fontSize = it.em, fontWeight = FontWeight.Bold) }
    internal val bold = SpanStyle(fontWeight = FontWeight.Bold)
    internal val italic = SpanStyle(fontStyle = FontStyle.Italic)
    internal val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = colors.card)
    internal val link = SpanStyle(color = colors.accent, textDecoration = TextDecoration.Underline)
    internal val imageAlt = SpanStyle(color = colors.textSecondary, fontStyle = FontStyle.Italic)
    internal val quoteText = SpanStyle(color = colors.textSecondary)
    internal val ordered = SpanStyle(color = colors.textSecondary)
    // No TextDecoration.LineThrough here: Android keeps one decoration per character run and a later underline (a
    // link, or the keyboard's composing underline) switches the strike off. Strikes are drawn instead
    // ([drawLiveStrikes]).
    internal val taskDoneText = SpanStyle(color = colors.textSecondary)
    internal val codeBlock = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.85.em)
    internal val table = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em)
    internal val tableSyntax = SpanStyle(color = colors.textTertiary)

    companion object {
        /** Heading sizes relative to the body text, level 1 to 6 (the preview's 28/24/20/17/15/13 over 16). */
        val HeadingScale = listOf(1.6f, 1.4f, 1.25f, 1.1f, 1.0f, 0.9f)
    }
}

/**
 * What the editor shows: [revealed] lines show their syntax (softened); [selection] touching a line's prefix shows
 * the raw prefix instead of the dot/box/bar. Only [window] lines are styled.
 */
data class LiveMarkdownView(val window: IntRange, val revealed: IntRange, val selection: TextRange?)

/** The styled text for [view]; its text is exactly [LiveMarkdownDocument.text]. */
fun LiveMarkdownDocument.styled(view: LiveMarkdownView, style: LiveMarkdownStyle): AnnotatedString {
    val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
    val last = minOf(view.window.last, lineCount - 1)
    for (index in view.window.first.coerceAtLeast(0)..last) {
        val l = line(index)
        if (l.tokenCount == 0) continue
        val base = lineStart(index)
        val revealed = index in view.revealed
        val prefixShown = revealed && touchesPrefix(index, view.selection)
        for (t in 0 until l.tokenCount) {
            val type = l.tokenType(t)
            val s = base + l.tokenStart(t)
            val e = base + l.tokenEnd(t)
            val span = when {
                LiveToken.isLineMarker(type) -> if (revealed) style.marker else style.hidden
                LiveToken.isHeading(type) -> style.headings[LiveToken.headingLevel(type) - 1]
                type == LiveToken.TASK_BULLET -> if (prefixShown) style.marker else style.hidden
                LiveToken.isPrefixMarker(type) -> if (prefixShown) style.marker else style.transparent
                type == LiveToken.RULE -> if (revealed) style.marker else style.transparent
                else -> when (type) {
                    LiveToken.BOLD -> style.bold
                    LiveToken.ITALIC -> style.italic
                    LiveToken.CODE -> style.code
                    LiveToken.LINK_TEXT, LiveToken.AUTOLINK -> style.link
                    LiveToken.IMAGE_ALT -> style.imageAlt
                    LiveToken.QUOTE_TEXT -> style.quoteText
                    LiveToken.ORDERED -> style.ordered
                    LiveToken.TASK_DONE_TEXT -> style.taskDoneText
                    LiveToken.CODE_BLOCK -> style.codeBlock
                    LiveToken.TABLE -> style.table
                    LiveToken.TABLE_PIPE, LiveToken.TABLE_DELIM -> style.tableSyntax
                    else -> null
                }
            } ?: continue
            spans.add(AnnotatedString.Range(span, s, e))
        }
        // A table's delimiter row is also monospace.
        for (t in 0 until l.tokenCount) {
            if (l.tokenType(t) == LiveToken.TABLE_DELIM) {
                spans.add(AnnotatedString.Range(style.table, base + l.tokenStart(t), base + l.tokenEnd(t)))
            }
        }
    }
    return AnnotatedString(text, spans)
}

/** True when [selection] touches (or sits right after) the block prefix of [lineIndex]. */
fun LiveMarkdownDocument.touchesPrefix(lineIndex: Int, selection: TextRange?): Boolean {
    if (selection == null) return false
    val l = line(lineIndex)
    if (l.prefixEnd == 0 && l.kind != LiveLineKind.RULE) return false
    val start = lineStart(lineIndex)
    val end = start + (if (l.kind == LiveLineKind.RULE) l.length else l.prefixEnd)
    return selection.min <= end && selection.max >= start
}

/** Things drawn under the text: bullet dots, task boxes, quote bars, code block backgrounds and rules. */
class LiveDecorations internal constructor(
    /** Offsets of bullet characters. */
    internal val bullets: IntArray,
    /** Offset of a task box's `[` and 1 for done, 0 for open, in pairs. */
    internal val tasks: IntArray,
    /** Offset of a `>` and the end of its line, in pairs. */
    internal val quotes: IntArray,
    /** First and last offset of a fenced code block, in pairs. */
    internal val codeBlocks: IntArray,
    /** Start offsets of rule lines. */
    internal val rules: IntArray,
    /** Struck-through ranges: start, end and 1 for a done task's text (softer colour), 0 for `~~text~~`. */
    internal val strikes: IntArray,
) {
    val isEmpty: Boolean get() = bullets.isEmpty() && tasks.isEmpty() && quotes.isEmpty() && codeBlocks.isEmpty() && rules.isEmpty()
    val hasStrikes: Boolean get() = strikes.isNotEmpty()

    /** The struck-through ranges as start until end. */
    val strikeRanges: List<IntRange> get() = (strikes.indices step 3).map { strikes[it] until strikes[it + 1] }
}

/** The decorations for [view] (only [LiveMarkdownView.window] lines). */
fun LiveMarkdownDocument.decorations(view: LiveMarkdownView): LiveDecorations {
    val bullets = IntList()
    val tasks = IntList()
    val quotes = IntList()
    val code = IntList()
    val rules = IntList()
    val strikes = IntList()
    val first = view.window.first.coerceAtLeast(0)
    val last = minOf(view.window.last, lineCount - 1)
    var blockStart = -1
    for (index in first..last) {
        val l = line(index)
        val base = lineStart(index)
        when (l.kind) {
            LiveLineKind.FENCE_OPEN -> if (blockStart < 0) blockStart = base
            LiveLineKind.CODE -> if (blockStart < 0) blockStart = base
            LiveLineKind.FENCE_CLOSE -> {
                code.add(if (blockStart < 0) base else blockStart)
                code.add(base + l.length)
                blockStart = -1
            }
        }
        for (t in 0 until l.tokenCount) {
            val type = l.tokenType(t)
            if (type == LiveToken.STRIKE || type == LiveToken.TASK_DONE_TEXT) {
                strikes.add(base + l.tokenStart(t))
                strikes.add(base + l.tokenEnd(t))
                strikes.add(if (type == LiveToken.TASK_DONE_TEXT) 1 else 0)
            }
        }
        val revealed = index in view.revealed
        if (l.kind == LiveLineKind.RULE) {
            if (!revealed) rules.add(base)
            continue
        }
        if (l.prefixEnd == 0) continue
        if (revealed && touchesPrefix(index, view.selection)) continue
        for (t in 0 until l.tokenCount) {
            val s = base + l.tokenStart(t)
            when (l.tokenType(t)) {
                LiveToken.BULLET -> bullets.add(s)
                LiveToken.TASK_OPEN -> { tasks.add(s); tasks.add(0) }
                LiveToken.TASK_DONE -> { tasks.add(s); tasks.add(1) }
                LiveToken.QUOTE_MARK -> { quotes.add(s); quotes.add(base + l.length) }
            }
        }
    }
    if (blockStart >= 0) {
        // Unclosed block: runs to the window's end.
        code.add(blockStart)
        code.add(lineEnd(last))
    }
    return LiveDecorations(bullets.toArray(), tasks.toArray(), quotes.toArray(), code.toArray(), rules.toArray(), strikes.toArray())
}

/** Draws [decorations] in the coordinates of [layout] (the text field's own text layout). */
fun DrawScope.drawLiveDecorations(layout: TextLayoutResult, decorations: LiveDecorations, colors: SameSameColors) {
    val textLength = layout.layoutInput.text.length
    fun valid(offset: Int) = offset in 0 until textLength
    val c = decorations.codeBlocks
    var i = 0
    val pad = 6.dp.toPx()
    while (i < c.size) {
        val s = c[i].coerceIn(0, textLength)
        val e = c[i + 1].coerceIn(0, textLength)
        val top = layout.getLineTop(layout.getLineForOffset(s))
        val bottom = layout.getLineBottom(layout.getLineForOffset(e))
        drawRoundRect(
            colors.card,
            topLeft = Offset(-pad, top),
            size = Size(layout.size.width + 2 * pad, bottom - top),
            cornerRadius = CornerRadius(8.dp.toPx()),
        )
        i += 2
    }
    val q = decorations.quotes
    i = 0
    val bar = 3.dp.toPx()
    while (i < q.size) {
        if (valid(q[i])) {
            val box = layout.getBoundingBox(q[i])
            val top = layout.getLineTop(layout.getLineForOffset(q[i]))
            val bottom = layout.getLineBottom(layout.getLineForOffset(q[i + 1].coerceIn(0, textLength)))
            drawRect(colors.accent, topLeft = Offset(box.center.x - bar / 2, top), size = Size(bar, bottom - top))
        }
        i += 2
    }
    for (offset in decorations.bullets) {
        if (!valid(offset)) continue
        val box = layout.getBoundingBox(offset)
        drawCircle(colors.textPrimary, radius = 2.75.dp.toPx(), center = box.center)
    }
    val t = decorations.tasks
    i = 0
    while (i < t.size) {
        if (valid(t[i]) && valid(t[i] + 2)) {
            val rect = taskBoxRect(layout, t[i], 18.dp.toPx())
            val corner = CornerRadius(4.dp.toPx())
            if (t[i + 1] == 1) {
                drawRoundRect(colors.accent, rect.topLeft, rect.size, corner)
                val path = Path().apply {
                    moveTo(rect.left + rect.width * 0.24f, rect.top + rect.height * 0.52f)
                    lineTo(rect.left + rect.width * 0.43f, rect.top + rect.height * 0.70f)
                    lineTo(rect.left + rect.width * 0.77f, rect.top + rect.height * 0.32f)
                }
                drawPath(path, colors.background, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                val w = 2.dp.toPx()
                drawRoundRect(
                    colors.checkOutline,
                    Offset(rect.left + w / 2, rect.top + w / 2),
                    Size(rect.width - w, rect.height - w),
                    corner,
                    style = Stroke(w),
                )
            }
        }
        i += 2
    }
    val dividerHeight = 1.dp.toPx()
    for (offset in decorations.rules) {
        val line = layout.getLineForOffset(offset.coerceIn(0, textLength))
        val y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
        drawRect(colors.divider, topLeft = Offset(0f, y - dividerHeight / 2), size = Size(layout.size.width.toFloat(), dividerHeight))
    }
}

/**
 * Draws the strike-through lines over the text (call after the text is drawn). Each visual line of a range gets a
 * line at about a third of the ascent above the baseline, like the platform's own strike-through.
 */
fun DrawScope.drawLiveStrikes(layout: TextLayoutResult, decorations: LiveDecorations, colors: SameSameColors) {
    val textLength = layout.layoutInput.text.length
    val s = decorations.strikes
    var i = 0
    while (i < s.size) {
        val start = s[i].coerceIn(0, textLength)
        val end = s[i + 1].coerceIn(0, textLength)
        val color = if (s[i + 2] == 1) colors.textSecondary else colors.textPrimary
        i += 3
        if (end <= start) continue
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        for (line in firstLine..lastLine) {
            val from = maxOf(start, layout.getLineStart(line))
            val to = minOf(end, layout.getLineEnd(line, visibleEnd = true))
            if (to <= from) continue
            val left = layout.getBoundingBox(from).left
            val right = layout.getBoundingBox(to - 1).right
            val baseline = layout.getLineBaseline(line)
            val ascent = baseline - layout.getLineTop(line)
            val thickness = (ascent / 15f).coerceAtLeast(1f)
            val y = baseline - ascent * 0.3f
            drawRect(color, topLeft = Offset(minOf(left, right), y - thickness / 2), size = Size(abs(right - left), thickness))
        }
    }
}

/** The square a task box is drawn in: centred on its `[ ]`, [size] wide, centred on the line vertically. */
fun taskBoxRect(layout: TextLayoutResult, bracket: Int, size: Float): Rect {
    val open = layout.getBoundingBox(bracket)
    val close = layout.getBoundingBox(bracket + 2)
    val line = layout.getLineForOffset(bracket)
    val cx = (open.left + close.right) / 2
    val cy = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2
    return Rect(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
}

private class IntList {
    private var data = IntArray(8)
    private var size = 0
    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
    fun toArray(): IntArray = data.copyOf(size)
}
