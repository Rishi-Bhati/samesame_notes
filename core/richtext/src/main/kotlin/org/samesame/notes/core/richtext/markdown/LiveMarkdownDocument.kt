package org.samesame.notes.core.richtext.markdown

/**
 * The live-styling analysis of a Markdown text, line by line, kept up to date with [update] after each edit.
 *
 * An edit re-analyses only the lines it touched (plus the line before, whose table/setext look-ahead may have
 * changed) and then the following lines until the block state converges again, so typing costs a few lines however
 * long the document is. When an edit changes the state of more than [MAX_CASCADE_LINES] following lines (opening a
 * code fence near the top of a huge file) [update] stops early and reports it; the caller then runs [analyze] off the
 * main thread and replaces the document.
 */
class LiveMarkdownDocument private constructor(text: String, private val lines: ArrayList<LiveLine>) {
    var text: String = text
        private set

    private var starts = IntArray(0)

    /** Total number of tokens of all lines. */
    var tokenCount: Int = 0
        private set

    init {
        reindex()
    }

    val lineCount: Int get() = lines.size
    fun line(index: Int): LiveLine = lines[index]
    fun lineStart(index: Int): Int = starts[index]
    fun lineEnd(index: Int): Int = starts[index] + lines[index].length

    /** The line holding [offset] (a line's end, its `\n`, belongs to it). */
    fun lineAt(offset: Int): Int {
        var lo = 0
        var hi = lines.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Brings the analysis up to [newText]. Returns false when the re-analysis was cut short (see the class comment):
     * the lines after the cut keep their old, possibly stale, styling until a fresh [analyze].
     */
    fun update(newText: String, maxCascade: Int = MAX_CASCADE_LINES): Boolean {
        val old = text
        if (old == newText) return true
        val minLen = minOf(old.length, newText.length)
        var p = 0
        while (p < minLen && old[p] == newText[p]) p++
        var s = 0
        while (s < minLen - p && old[old.length - 1 - s] == newText[newText.length - 1 - s]) s++
        val delta = newText.length - old.length

        val first = maxOf(0, lineAt(p) - 1)
        val oldLast = lineAt(old.length - s)
        val stopAt = lineEnd(oldLast) + delta

        val fresh = ArrayList<LiveLine>()
        var state = lines[first].startState
        var pos = starts[first]
        var end: Int
        while (true) {
            end = newText.indexOf('\n', pos).let { if (it < 0) newText.length else it }
            val added = analyzeAt(newText, pos, end, state)
            fresh.add(added)
            state = added.endState
            if (end >= stopAt || end >= newText.length) break
            pos = end + 1
        }
        var nextOld = oldLast + 1
        var complete = true
        var cascade = 0
        while (nextOld < lines.size && end < newText.length && lines[nextOld].startState != state) {
            if (cascade++ >= maxCascade) {
                complete = false
                break
            }
            pos = end + 1
            end = newText.indexOf('\n', pos).let { if (it < 0) newText.length else it }
            val added = analyzeAt(newText, pos, end, state)
            fresh.add(added)
            state = added.endState
            nextOld++
        }
        // An edit at the very end may leave old lines past the new end (text got shorter); they are replaced too.
        if (end >= newText.length) nextOld = lines.size

        val removed = lines.subList(first, nextOld)
        for (l in removed) tokenCount -= l.tokenCount
        removed.clear()
        lines.addAll(first, fresh)
        for (l in fresh) tokenCount += l.tokenCount
        text = newText
        reindex(countTokens = false)
        return complete
    }

    /**
     * The range of lines whose syntax is shown for a selection from [selStart] to [selEnd]: the lines it touches,
     * widened to whole fenced code blocks.
     */
    fun revealLines(selStart: Int, selEnd: Int): IntRange {
        if (lines.isEmpty()) return IntRange.EMPTY
        var a = lineAt(minOf(selStart, selEnd).coerceIn(0, text.length))
        var b = lineAt(maxOf(selStart, selEnd).coerceIn(0, text.length))
        val ka = lines[a].kind
        if (ka == LiveLineKind.CODE || ka == LiveLineKind.FENCE_CLOSE) {
            var steps = 0
            while (a > 0 && steps++ < MAX_REVEAL_LINES) {
                a--
                if (lines[a].kind == LiveLineKind.FENCE_OPEN) break
            }
        }
        val kb = lines[b].kind
        if (kb == LiveLineKind.CODE || kb == LiveLineKind.FENCE_OPEN) {
            var steps = 0
            while (b < lines.size - 1 && steps++ < MAX_REVEAL_LINES) {
                b++
                if (lines[b].kind == LiveLineKind.FENCE_CLOSE) break
            }
        }
        return a..b
    }

    /**
     * The lines to style around [centerLine] so that at most about [budget] tokens are used: every line when the
     * whole document fits, otherwise a window growing both ways from the centre.
     */
    fun window(centerLine: Int, budget: Int): IntRange {
        if (lines.isEmpty()) return IntRange.EMPTY
        if (tokenCount <= budget) return 0 until lines.size
        val c = centerLine.coerceIn(0, lines.size - 1)
        var lo = c
        var hi = c
        var used = lines[c].tokenCount
        while (used < budget && (lo > 0 || hi < lines.size - 1)) {
            if (hi < lines.size - 1) {
                hi++
                used += lines[hi].tokenCount
            }
            if (lo > 0 && used < budget) {
                lo--
                used += lines[lo].tokenCount
            }
        }
        return lo..hi
    }

    /** Absolute offset of the `[` of the task box on [lineIndex], or -1. */
    fun taskBoxAt(lineIndex: Int): Int {
        val l = lines[lineIndex]
        for (t in 0 until l.tokenCount) {
            val type = l.tokenType(t)
            if (type == LiveToken.TASK_OPEN || type == LiveToken.TASK_DONE) return starts[lineIndex] + l.tokenStart(t)
        }
        return -1
    }

    /** The link at [offset] (its text, an image's alt text or an autolink), or null. */
    fun linkAt(offset: Int): LiveLink? {
        if (lines.isEmpty() || offset < 0 || offset > text.length) return null
        val index = lineAt(offset)
        val l = lines[index]
        val base = starts[index]
        val rel = offset - base
        for (t in 0 until l.tokenCount) {
            val type = l.tokenType(t)
            val ts = l.tokenStart(t)
            val te = l.tokenEnd(t)
            if (rel < ts || rel >= te) continue
            when (type) {
                LiveToken.AUTOLINK -> return LiveLink(base + ts, base + te, autolinkTarget(text.substring(base + ts, base + te)))
                LiveToken.LINK_TEXT, LiveToken.IMAGE_ALT -> {
                    // The destination follows the text's `](`.
                    for (u in 0 until l.tokenCount) {
                        if (l.tokenType(u) == LiveToken.LINK_URL && l.tokenStart(u) == te + 2) {
                            val raw = text.substring(base + l.tokenStart(u), base + l.tokenEnd(u))
                            return LiveLink(base + ts, base + te, destination(raw))
                        }
                    }
                    return LiveLink(base + ts, base + te, "")
                }
            }
        }
        return null
    }

    private fun analyzeAt(src: String, start: Int, end: Int, state: Int): LiveLine {
        val nextStart = end + 1
        val nextEnd = if (end >= src.length) -1 else src.indexOf('\n', nextStart).let { if (it < 0) src.length else it }
        return LiveMarkdownAnalyzer.analyzeLine(src, start, end, state, nextStart, nextEnd)
    }

    private fun reindex(countTokens: Boolean = true) {
        if (starts.size != lines.size) starts = IntArray(lines.size)
        var pos = 0
        var tokens = 0
        for (i in lines.indices) {
            starts[i] = pos
            pos += lines[i].length + 1
            if (countTokens) tokens += lines[i].tokenCount
        }
        if (countTokens) tokenCount = tokens
    }

    companion object {
        /** Following lines an edit may re-style on the spot before deferring to a full [analyze]. */
        const val MAX_CASCADE_LINES = 3000

        /** How far a code block is widened for revealing its fences. */
        const val MAX_REVEAL_LINES = 2000

        /** Analyses the whole of [text]; linear in its length, so call it off the main thread for big files. */
        fun analyze(text: String): LiveMarkdownDocument {
            val lines = ArrayList<LiveLine>()
            var state = 0
            var start = 0
            while (true) {
                val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                val nextStart = end + 1
                val nextEnd = if (end >= text.length) -1 else text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                val line = LiveMarkdownAnalyzer.analyzeLine(text, start, end, state, nextStart, nextEnd)
                lines.add(line)
                state = line.endState
                if (end >= text.length) break
                start = end + 1
            }
            return LiveMarkdownDocument(text, lines)
        }

        /** A link destination without its `<...>` and title. */
        internal fun destination(raw: String): String {
            val t = raw.trim()
            if (t.startsWith("<")) {
                val close = t.indexOf('>')
                return if (close > 0) t.substring(1, close) else t.substring(1)
            }
            val space = t.indexOfFirst { it.isWhitespace() }
            return if (space < 0) t else t.substring(0, space)
        }

        internal fun autolinkTarget(raw: String): String = when {
            raw.startsWith("www.") -> "https://$raw"
            '@' in raw && ':' !in raw -> "mailto:$raw"
            else -> raw
        }
    }
}

/** A link in the source: the range of its visible text and where it points. */
data class LiveLink(val start: Int, val end: Int, val target: String)
