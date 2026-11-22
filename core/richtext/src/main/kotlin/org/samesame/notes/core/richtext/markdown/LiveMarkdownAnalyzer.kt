package org.samesame.notes.core.richtext.markdown

/**
 * Token types of the live Markdown editor. A token is a range of one source line plus one of these types; the editor
 * turns them into text styles and drawn decorations. The source text itself is never changed.
 */
object LiveToken {
    /** Syntax (`#`, `**`, `` ` ``, link brackets, fence lines, ...): hidden unless the caret's line/block is revealed. */
    const val MARKER = 1

    /** Heading text; the level is `type - HEADING_1 + 1`. */
    const val HEADING_1 = 2
    const val HEADING_6 = 7
    const val BOLD = 8
    const val ITALIC = 9
    const val STRIKE = 10
    const val CODE = 11
    const val LINK_TEXT = 12

    /** The `url "title"` part of an inline link or image: hidden like a [MARKER]. */
    const val LINK_URL = 13
    const val IMAGE_ALT = 14

    /** A bare `https://` / `www.` URL or the inside of `<...>`: drawn as a link, nothing hidden. */
    const val AUTOLINK = 15

    /** A `>` of a block quote: drawn as a bar unless the caret touches the line's prefix. */
    const val QUOTE_MARK = 16
    const val QUOTE_TEXT = 17

    /** `-`, `*` or `+` of a bullet item: drawn as a dot unless the caret touches the prefix. */
    const val BULLET = 18

    /** `1.` / `1)` of an ordered item: stays visible, in a softer colour. */
    const val ORDERED = 19

    /** `[ ]` of an open task: drawn as a check box unless the caret touches the prefix. */
    const val TASK_OPEN = 20

    /** `[x]` of a done task. */
    const val TASK_DONE = 21

    /** The text of a done task (struck through). */
    const val TASK_DONE_TEXT = 22

    /** The bullet in front of a task box: hidden while the box is drawn. */
    const val TASK_BULLET = 23

    /** A ```` ``` ```` / `~~~` fence line (whole line); revealed for the whole code block. */
    const val FENCE = 24

    /** A line inside a fenced code block (whole line). */
    const val CODE_BLOCK = 25

    /** A thematic break line: drawn as a rule unless the caret is on it. */
    const val RULE = 26

    /** A table row (whole line, monospace so hand-aligned columns line up). */
    const val TABLE = 27
    const val TABLE_PIPE = 28

    /** The `|---|:--:|` row under a table header. */
    const val TABLE_DELIM = 29

    fun isHeading(type: Int): Boolean = type in HEADING_1..HEADING_6
    fun headingLevel(type: Int): Int = type - HEADING_1 + 1

    /** Hidden outside the revealed lines, shown softened inside them. */
    fun isLineMarker(type: Int): Boolean = type == MARKER || type == LINK_URL || type == FENCE

    /** Block prefix syntax that is drawn (bar, dot, box) unless the caret touches the line's prefix. */
    fun isPrefixMarker(type: Int): Boolean =
        type == QUOTE_MARK || type == BULLET || type == TASK_OPEN || type == TASK_DONE || type == TASK_BULLET
}

/** What kind of block a line is part of, as far as the live editor cares. */
object LiveLineKind {
    const val PARAGRAPH = 0
    const val BLANK = 1
    const val HEADING = 2
    const val FENCE_OPEN = 3
    const val CODE = 4
    const val FENCE_CLOSE = 5
    const val RULE = 6
    const val TABLE = 7
    const val SETEXT_UNDERLINE = 8
    const val LIST = 9
    const val QUOTE = 10

    fun isInFence(kind: Int): Boolean = kind == FENCE_OPEN || kind == CODE || kind == FENCE_CLOSE
}

/**
 * One analysed source line. [tokens] holds `start, end, type` triples with offsets relative to the line start.
 * [prefixEnd] is the relative end of the leading block syntax (quote marks, list marker, task box), 0 when none.
 * [startState] / [endState] are the block parser state before and after the line (open fence, table, paragraph).
 */
class LiveLine internal constructor(
    val length: Int,
    val startState: Int,
    val endState: Int,
    val kind: Int,
    internal val tokens: IntArray,
    val prefixEnd: Int,
) {
    val tokenCount: Int get() = tokens.size / 3
    fun tokenStart(i: Int): Int = tokens[i * 3]
    fun tokenEnd(i: Int): Int = tokens[i * 3 + 1]
    fun tokenType(i: Int): Int = tokens[i * 3 + 2]
}

/**
 * A small line-by-line Markdown scanner for live styling. It follows CommonMark/GFM closely enough for the common
 * syntax (ATX and setext headings, emphasis with the delimiter-run rules, code spans, links, images, autolinks,
 * fenced code, quotes, lists, task items, rules, tables) but is not a full parser: emphasis does not span lines, and
 * indented code blocks are not recognised (indentation usually means a nested list). Each line depends only on the
 * state left by the line before and on the text of the line after, which keeps re-styling after an edit local.
 */
internal object LiveMarkdownAnalyzer {
    // State: bits 0-1 mode, bit 2 "previous line is paragraph text", bits 8+ fence length.
    const val MODE_NORMAL = 0
    const val MODE_FENCE_TICK = 1
    const val MODE_FENCE_TILDE = 2
    const val MODE_TABLE = 3
    private const val MODE_MASK = 3
    private const val PARA = 4
    private const val FENCE_LEN_SHIFT = 8

    /** Analyses `text[start, end)`, the line after which is `text[nextStart, nextEnd)` (nextEnd -1 when none). */
    fun analyzeLine(text: String, start: Int, end: Int, state: Int, nextStart: Int, nextEnd: Int): LiveLine {
        val out = TokenList()
        var lineEnd = end
        if (lineEnd > start && text[lineEnd - 1] == '\r') lineEnd--
        val mode = state and MODE_MASK
        val len = end - start

        if (mode == MODE_FENCE_TICK || mode == MODE_FENCE_TILDE) {
            val fenceChar = if (mode == MODE_FENCE_TICK) '`' else '~'
            val fenceLen = state ushr FENCE_LEN_SHIFT
            val i = skipSpaces(text, start, lineEnd)
            val run = runLength(text, i, lineEnd, fenceChar)
            if (run >= fenceLen && skipSpaces(text, i + run, lineEnd) == lineEnd) {
                out.add(0, len, LiveToken.FENCE)
                return line(len, state, MODE_NORMAL, LiveLineKind.FENCE_CLOSE, out, 0)
            }
            if (len > 0) out.add(0, len, LiveToken.CODE_BLOCK)
            return line(len, state, state and PARA.inv(), LiveLineKind.CODE, out, 0)
        }

        if (skipSpaces(text, start, lineEnd) == lineEnd) {
            return line(len, state, MODE_NORMAL, LiveLineKind.BLANK, out, 0)
        }

        if (mode == MODE_TABLE && hasPipe(text, start, lineEnd)) {
            tableRow(text, start, lineEnd, out)
            return line(len, state, MODE_TABLE, LiveLineKind.TABLE, out, 0)
        }

        val first = skipSpaces(text, start, lineEnd)
        val indent = first - start

        // Fenced code opening (any indent, so fences inside list items work too).
        val c0 = text[first]
        if (c0 == '`' || c0 == '~') {
            val run = runLength(text, first, lineEnd, c0)
            if (run >= 3 && (c0 == '~' || text.indexOf('`', first + run).let { it < 0 || it >= lineEnd })) {
                out.add(0, len, LiveToken.FENCE)
                val newMode = if (c0 == '`') MODE_FENCE_TICK else MODE_FENCE_TILDE
                return line(len, state, newMode or (run.coerceAtMost(255) shl FENCE_LEN_SHIFT), LiveLineKind.FENCE_OPEN, out, 0)
            }
        }

        // Setext underline under a paragraph line.
        if ((state and PARA) != 0 && indent <= 3 && isSetextUnderline(text, first, lineEnd)) {
            out.add(0, len, LiveToken.MARKER)
            return line(len, state, MODE_NORMAL, LiveLineKind.SETEXT_UNDERLINE, out, 0)
        }

        if (indent <= 3 && isRule(text, first, lineEnd)) {
            out.add(0, len, LiveToken.RULE)
            return line(len, state, MODE_NORMAL, LiveLineKind.RULE, out, 0)
        }

        // Table header: a line with a pipe followed by a delimiter row.
        if (nextEnd >= 0 && hasPipe(text, start, lineEnd) && isDelimiterRow(text, nextStart, trimCr(text, nextStart, nextEnd))) {
            tableRow(text, start, lineEnd, out)
            return line(len, state, MODE_TABLE, LiveLineKind.TABLE, out, 0)
        }

        // Block prefix: quote marks, then a list marker and an optional task box.
        var i = first
        var kind = LiveLineKind.PARAGRAPH
        var quoted = false
        while (i < lineEnd && text[i] == '>') {
            out.add(i - start, i - start + 1, LiveToken.QUOTE_MARK)
            quoted = true
            kind = LiveLineKind.QUOTE
            i++
            if (i < lineEnd && (text[i] == ' ' || text[i] == '\t')) i++
            val j = skipSpaces(text, i, lineEnd)
            if (j < lineEnd && text[j] == '>' && j - i <= 3) i = j
        }
        var prefixEnd = if (quoted) i - start else 0
        var taskDone = false
        var isTask = false
        val markerAt = skipSpaces(text, i, lineEnd)
        if (markerAt < lineEnd) {
            val m = text[markerAt]
            var markerEnd = -1
            var bullet = false
            if ((m == '-' || m == '*' || m == '+') && (markerAt + 1 == lineEnd || isSpace(text[markerAt + 1]))) {
                markerEnd = markerAt + 1
                bullet = true
            } else if (m in '0'..'9') {
                var d = markerAt
                while (d < lineEnd && text[d] in '0'..'9' && d - markerAt < 9) d++
                if (d < lineEnd && (text[d] == '.' || text[d] == ')') && (d + 1 == lineEnd || isSpace(text[d + 1]))) {
                    markerEnd = d + 1
                }
            }
            if (markerEnd > 0) {
                kind = LiveLineKind.LIST
                var after = markerEnd
                if (after < lineEnd) after++
                val box = skipSpaces(text, after, lineEnd)
                if (box + 2 < lineEnd && text[box] == '[' && text[box + 2] == ']' && text[box + 1] in " xX" &&
                    (box + 3 == lineEnd || isSpace(text[box + 3]))
                ) {
                    isTask = true
                    taskDone = text[box + 1] != ' '
                    if (bullet) out.add(markerAt - start, markerEnd - start, LiveToken.TASK_BULLET)
                    else out.add(markerAt - start, markerEnd - start, LiveToken.ORDERED)
                    out.add(box - start, box + 3 - start, if (taskDone) LiveToken.TASK_DONE else LiveToken.TASK_OPEN)
                    prefixEnd = box + 3 - start
                    i = if (box + 3 < lineEnd) box + 4 else box + 3
                } else {
                    out.add(markerAt - start, markerEnd - start, if (bullet) LiveToken.BULLET else LiveToken.ORDERED)
                    prefixEnd = markerEnd - start
                    i = after
                }
            }
        }

        // ATX heading after the prefix: 1-6 '#' followed by a space or the end of the line (`#hello` is not one).
        val h = skipSpaces(text, i, lineEnd)
        val hashes = runLength(text, h, lineEnd, '#')
        if (hashes in 1..6 && (h + hashes == lineEnd || isSpace(text[h + hashes])) && h - i <= 3) {
            val contentStart = skipSpaces(text, h + hashes, lineEnd)
            out.add(h - start, contentStart - start, LiveToken.MARKER)
            // Optional closing sequence: spaces then only '#'.
            var contentEnd = lineEnd
            var k = lineEnd
            while (k > contentStart && isSpace(text[k - 1])) k--
            var hashStart = k
            while (hashStart > contentStart && text[hashStart - 1] == '#') hashStart--
            if (hashStart < k && (hashStart == contentStart || isSpace(text[hashStart - 1]))) {
                var sp = hashStart
                while (sp > contentStart && isSpace(text[sp - 1])) sp--
                out.add(sp - start, lineEnd - start, LiveToken.MARKER)
                contentEnd = sp
            }
            if (contentEnd > contentStart) {
                out.add(contentStart - start, contentEnd - start, LiveToken.HEADING_1 + hashes - 1)
                scanInline(text, start, contentStart, contentEnd, out)
            }
            return line(len, state, MODE_NORMAL, LiveLineKind.HEADING, out, prefixEnd)
        }

        val contentStart = skipSpaces(text, i, lineEnd)
        // Setext heading: a plain paragraph line followed by `===` or `---`.
        if (kind == LiveLineKind.PARAGRAPH && nextEnd >= 0) {
            val ns = skipSpaces(text, nextStart, nextEnd)
            val ne = trimCr(text, nextStart, nextEnd)
            if (ns - nextStart <= 3 && isSetextUnderline(text, ns, ne)) {
                val level = if (text[ns] == '=') 1 else 2
                out.add(contentStart - start, trimTrailingSpaces(text, contentStart, lineEnd) - start, LiveToken.HEADING_1 + level - 1)
                scanInline(text, start, contentStart, lineEnd, out)
                return line(len, state, MODE_NORMAL or PARA, LiveLineKind.HEADING, out, 0)
            }
        }

        if (contentStart < lineEnd) {
            when {
                isTask && taskDone -> out.add(contentStart - start, lineEnd - start, LiveToken.TASK_DONE_TEXT)
                quoted -> out.add(contentStart - start, lineEnd - start, LiveToken.QUOTE_TEXT)
            }
            scanInline(text, start, contentStart, lineEnd, out)
        }
        val endState = if (kind == LiveLineKind.PARAGRAPH) MODE_NORMAL or PARA else MODE_NORMAL
        return line(len, state, endState, kind, out, prefixEnd)
    }

    private fun line(len: Int, start: Int, end: Int, kind: Int, out: TokenList, prefixEnd: Int) =
        LiveLine(len, start, end, kind, out.toArray(), prefixEnd)

    // ---- inline syntax -----------------------------------------------------------------------------------------

    private class Delim(
        val ch: Char,
        var start: Int,
        var len: Int,
        val orig: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
        val group: Int,
    ) {
        var active = true
    }

    /** Inline syntax of `text[from, to)`; offsets in [out] are relative to [lineStart]. */
    fun scanInline(text: String, lineStart: Int, from: Int, to: Int, out: TokenList) {
        var delims: ArrayList<Delim>? = null
        var i = from
        var group = 0
        var groups = 0
        var linkClose = -1
        var linkResume = -1
        while (i < to) {
            if (i == linkClose) {
                i = linkResume
                group = 0
                linkClose = -1
                continue
            }
            val c = text[i]
            // Inside a link's text nothing may run past its `]`.
            val lim = if (linkClose >= 0) linkClose else to
            when {
                c == '\\' && i + 1 < lim && isAsciiPunct(text[i + 1]) -> {
                    out.add(i - lineStart, i + 1 - lineStart, LiveToken.MARKER)
                    i += 2
                }
                c == '`' -> {
                    val run = runLength(text, i, lim, '`')
                    val close = findBacktickRun(text, i + run, lim, run)
                    if (close >= 0) {
                        out.add(i - lineStart, i + run - lineStart, LiveToken.MARKER)
                        if (close > i + run) out.add(i + run - lineStart, close - lineStart, LiveToken.CODE)
                        out.add(close - lineStart, close + run - lineStart, LiveToken.MARKER)
                        i = close + run
                    } else {
                        i += run
                    }
                }
                c == '<' -> {
                    val gt = autolinkEnd(text, i, lim)
                    if (gt > 0) {
                        out.add(i - lineStart, i + 1 - lineStart, LiveToken.MARKER)
                        out.add(i + 1 - lineStart, gt - lineStart, LiveToken.AUTOLINK)
                        out.add(gt - lineStart, gt + 1 - lineStart, LiveToken.MARKER)
                        i = gt + 1
                    } else {
                        i++
                    }
                }
                (c == '[' || (c == '!' && i + 1 < to && text[i + 1] == '[')) && linkClose < 0 -> {
                    val open = if (c == '!') i + 1 else i
                    val link = parseInlineLink(text, open, to)
                    if (link != null) {
                        val (close, paren) = link
                        out.add(i - lineStart, open + 1 - lineStart, LiveToken.MARKER)
                        if (close > open + 1) {
                            out.add(open + 1 - lineStart, close - lineStart, if (c == '!') LiveToken.IMAGE_ALT else LiveToken.LINK_TEXT)
                        }
                        out.add(close - lineStart, close + 2 - lineStart, LiveToken.MARKER)
                        if (paren > close + 2) out.add(close + 2 - lineStart, paren - lineStart, LiveToken.LINK_URL)
                        out.add(paren - lineStart, paren + 1 - lineStart, LiveToken.MARKER)
                        linkClose = close
                        linkResume = paren + 1
                        group = ++groups
                        i = open + 1
                    } else {
                        i = open + 1
                    }
                }
                (c == 'h' || c == 'w') && (i == from || !text[i - 1].isLetterOrDigit()) && bareUrlStart(text, i, lim) -> {
                    val e = bareUrlEnd(text, i, lim)
                    out.add(i - lineStart, e - lineStart, LiveToken.AUTOLINK)
                    i = e
                }
                c == '*' || c == '_' || c == '~' -> {
                    val run = runLength(text, i, lim, c)
                    val before = if (i > from) text[i - 1] else ' '
                    val after = if (i + run < to) text[i + run] else ' '
                    val left = !after.isWhitespace() && (!isPunct(after) || before.isWhitespace() || isPunct(before))
                    val right = !before.isWhitespace() && (!isPunct(before) || after.isWhitespace() || isPunct(after))
                    val canOpen: Boolean
                    val canClose: Boolean
                    if (c == '_') {
                        canOpen = left && (!right || isPunct(before))
                        canClose = right && (!left || isPunct(after))
                    } else {
                        canOpen = left
                        canClose = right
                    }
                    if ((canOpen || canClose) && (c != '~' || run <= 2)) {
                        val list = delims ?: ArrayList<Delim>().also { delims = it }
                        list.add(Delim(c, i, run, run, canOpen, canClose, group))
                    }
                    i += run
                }
                else -> i++
            }
        }
        delims?.let { processEmphasis(it, lineStart, out) }
    }

    private fun processEmphasis(delims: ArrayList<Delim>, lineStart: Int, out: TokenList) {
        for (ci in delims.indices) {
            val closer = delims[ci]
            if (!closer.canClose || !closer.active) continue
            while (closer.len > 0) {
                var found = -1
                var oi = ci - 1
                while (oi >= 0) {
                    val o = delims[oi]
                    if (o.active && o.len > 0 && o.ch == closer.ch && o.canOpen && o.group == closer.group) {
                        if (closer.ch == '~') {
                            if (o.len == closer.len) { found = oi; break }
                        } else {
                            val odd = (o.canClose || closer.canOpen) && (o.orig + closer.orig) % 3 == 0 &&
                                !(o.orig % 3 == 0 && closer.orig % 3 == 0)
                            if (!odd) { found = oi; break }
                        }
                    }
                    oi--
                }
                if (found < 0) break
                val o = delims[found]
                val use = when {
                    closer.ch == '~' -> closer.len
                    o.len >= 2 && closer.len >= 2 -> 2
                    else -> 1
                }
                val openEnd = o.start + o.len
                val type = when {
                    closer.ch == '~' -> LiveToken.STRIKE
                    use == 2 -> LiveToken.BOLD
                    else -> LiveToken.ITALIC
                }
                out.add(openEnd - use - lineStart, openEnd - lineStart, LiveToken.MARKER)
                if (closer.start > openEnd) out.add(openEnd - lineStart, closer.start - lineStart, type)
                out.add(closer.start - lineStart, closer.start + use - lineStart, LiveToken.MARKER)
                o.len -= use
                closer.start += use
                closer.len -= use
                for (k in found + 1 until ci) delims[k].active = false
            }
        }
    }

    /** `[text](dest "title")` starting at the `[` at [open]: the `]` and `)` offsets, or null. */
    private fun parseInlineLink(text: String, open: Int, to: Int): Pair<Int, Int>? {
        var depth = 0
        var i = open + 1
        var close = -1
        while (i < to) {
            val c = text[i]
            when {
                c == '\\' -> i++
                c == '[' -> depth++
                c == ']' -> if (depth == 0) { close = i; break } else depth--
                c == '`' -> {
                    val run = runLength(text, i, to, '`')
                    val end = findBacktickRun(text, i + run, to, run)
                    if (end >= 0) i = end + run - 1 else i += run - 1
                }
            }
            i++
        }
        if (close < 0 || close + 1 >= to || text[close + 1] != '(') return null
        i = skipSpaces(text, close + 2, to)
        if (i < to && text[i] == '<') {
            while (i < to && text[i] != '>' ) { if (text[i] == '\\') i++; i++ }
            if (i >= to) return null
            i++
        } else {
            var parens = 0
            while (i < to) {
                val c = text[i]
                if (c == '\\') { i += 2; continue }
                if (c.isWhitespace()) break
                if (c == '(') parens++
                if (c == ')') { if (parens == 0) break; parens-- }
                i++
            }
        }
        i = skipSpaces(text, i, to)
        if (i < to && (text[i] == '"' || text[i] == '\'' || text[i] == '(')) {
            val q = if (text[i] == '(') ')' else text[i]
            i++
            while (i < to && text[i] != q) { if (text[i] == '\\') i++; i++ }
            if (i >= to) return null
            i = skipSpaces(text, i + 1, to)
        }
        if (i >= to || text[i] != ')') return null
        return close to i
    }

    /** End (the `>`) of an autolink `<scheme:...>` or `<a@b.c>` starting at [lt], or -1. */
    private fun autolinkEnd(text: String, lt: Int, to: Int): Int {
        var i = lt + 1
        var colon = -1
        var at = -1
        while (i < to) {
            val c = text[i]
            if (c == '>') break
            if (c == '<' || c.isWhitespace()) return -1
            if (c == ':' && colon < 0) colon = i
            if (c == '@') at = i
            i++
        }
        if (i >= to || i == lt + 1) return -1
        if (colon > lt + 2 && (lt + 1 until colon).all { text[it].isLetterOrDigit() || text[it] in "+.-" } && text[lt + 1].isLetter()) return i
        if (at > lt + 1 && at < i - 1 && colon < 0) return i
        return -1
    }

    private fun bareUrlStart(text: String, i: Int, to: Int): Boolean =
        text.startsWith("https://", i) || text.startsWith("http://", i) ||
            (text.startsWith("www.", i) && i + 4 < to && text[i + 4].isLetterOrDigit())

    private fun bareUrlEnd(text: String, start: Int, to: Int): Int {
        var e = start
        while (e < to && !text[e].isWhitespace() && text[e] != '<') e++
        while (e > start) {
            val c = text[e - 1]
            if (c in "?!.,:*_~'\";") { e--; continue }
            if (c == ')') {
                var open = 0
                var close = 0
                for (k in start until e) { if (text[k] == '(') open++ else if (text[k] == ')') close++ }
                if (close > open) { e--; continue }
            }
            break
        }
        return e
    }

    private fun findBacktickRun(text: String, from: Int, to: Int, run: Int): Int {
        var i = from
        while (i < to) {
            if (text[i] == '`') {
                val r = runLength(text, i, to, '`')
                if (r == run) return i
                i += r
            } else {
                i++
            }
        }
        return -1
    }

    // ---- block helpers ---------------------------------------------------------------------------------------

    private fun tableRow(text: String, start: Int, end: Int, out: TokenList) {
        if (isDelimiterRow(text, start, end)) {
            out.add(0, end - start, LiveToken.TABLE_DELIM)
            return
        }
        out.add(0, end - start, LiveToken.TABLE)
        var i = start
        while (i < end) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == '|') out.add(i - start, i + 1 - start, LiveToken.TABLE_PIPE)
            i++
        }
    }

    fun isDelimiterRow(text: String, start: Int, end: Int): Boolean {
        var pipes = 0
        var dashes = 0
        var i = start
        while (i < end) {
            when (text[i]) {
                '|' -> pipes++
                '-' -> dashes++
                ':', ' ', '\t' -> {}
                else -> return false
            }
            i++
        }
        return pipes > 0 && dashes > 0
    }

    private fun hasPipe(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val c = text[i]
            if (c == '\\') { i += 2; continue }
            if (c == '|') return true
            i++
        }
        return false
    }

    private fun isSetextUnderline(text: String, first: Int, end: Int): Boolean {
        if (first >= end) return false
        val c = text[first]
        if (c != '=' && c != '-') return false
        val run = runLength(text, first, end, c)
        return skipSpaces(text, first + run, end) == end
    }

    private fun isRule(text: String, first: Int, end: Int): Boolean {
        val c = text[first]
        if (c != '-' && c != '*' && c != '_') return false
        var count = 0
        for (i in first until end) {
            val x = text[i]
            if (x == c) count++ else if (x != ' ' && x != '\t') return false
        }
        return count >= 3
    }

    private fun trimCr(text: String, start: Int, end: Int): Int = if (end > start && text[end - 1] == '\r') end - 1 else end

    private fun trimTrailingSpaces(text: String, start: Int, end: Int): Int {
        var e = end
        while (e > start && isSpace(text[e - 1])) e--
        return e
    }

    private fun skipSpaces(text: String, from: Int, to: Int): Int {
        var i = from
        while (i < to && isSpace(text[i])) i++
        return i
    }

    private fun runLength(text: String, from: Int, to: Int, c: Char): Int {
        var i = from
        while (i < to && text[i] == c) i++
        return i - from
    }

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t'

    private fun isAsciiPunct(c: Char): Boolean = c.code in 33..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126

    private fun isPunct(c: Char): Boolean = isAsciiPunct(c) || (!c.isLetterOrDigit() && !c.isWhitespace() && c.code > 127)
}

/** A growable `start, end, type` triple list. */
internal class TokenList {
    private var data = IntArray(12)
    private var size = 0

    fun add(start: Int, end: Int, type: Int) {
        if (end <= start) return
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size] = start
        data[size + 1] = end
        data[size + 2] = type
        size += 3
    }

    fun toArray(): IntArray = if (size == 0) EMPTY else data.copyOf(size)

    private companion object {
        val EMPTY = IntArray(0)
    }
}
