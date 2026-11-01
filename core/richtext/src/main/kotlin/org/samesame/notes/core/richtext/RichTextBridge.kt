package org.samesame.notes.core.richtext

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextRun
import org.samesame.notes.core.richtext.spans.CharStyleSpan
import org.samesame.notes.core.richtext.spans.InlineObjectSpan
import org.samesame.notes.core.richtext.spans.ParagraphSpan
import org.samesame.notes.core.richtext.spans.TextRenderEnv

/**
 * RichText ↔ Spannable. Character styles are [CharStyleSpan]s (one per run, default runs have none),
 * inline objects are [InlineObjectSpan]s on U+FFFC, paragraph styles are [ParagraphSpan]s over the
 * paragraph including its `\n` (default paragraphs and an empty last paragraph have none, see
 * [BodyText] which keeps the authoritative paragraph list). Foreign spans (IME underlines,
 * suggestions) are ignored when reading.
 */
object RichTextBridge {

    fun toSpannable(rt: RichText, env: TextRenderEnv): SpannableStringBuilder {
        val n = RichTextOps.normalise(rt)
        val ssb = SpannableStringBuilder(n.text)
        setRuns(ssb, 0, n.runs, env)
        var start = 0
        for (style in n.paragraphs) {
            val nl = n.text.indexOf('\n', start)
            val end = if (nl < 0) n.text.length else nl + 1
            if (end > start && style != ParagraphStyle()) {
                ssb.setSpan(ParagraphSpan(style, env), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = end
            if (nl < 0) break
        }
        return ssb
    }

    /** Reads a Spannable written by [toSpannable] (paragraph styles from spans unless [paragraphs] is given). */
    fun toRichText(text: Spanned, paragraphs: List<ParagraphStyle>? = null): RichText {
        val s = text.toString()
        val ps = paragraphs ?: readParagraphs(text)
        return RichTextOps.normalise(RichText(s, readRuns(text, 0, s.length), ps))
    }

    fun readParagraphs(text: Spanned): List<ParagraphStyle> {
        val out = ArrayList<ParagraphStyle>()
        var start = 0
        while (true) {
            val nl = indexOf(text, '\n', start)
            val end = if (nl < 0) text.length else nl + 1
            val span = if (end > start) {
                text.getSpans(start, end, ParagraphSpan::class.java).firstOrNull { text.getSpanStart(it) <= start && text.getSpanEnd(it) > start }
            } else null
            out += span?.style ?: ParagraphStyle()
            if (nl < 0) break
            start = end
        }
        return out
    }

    /** Runs of `[start, end)` (not merged; normalise afterwards). */
    fun readRuns(text: Spanned, start: Int, end: Int): List<TextRun> {
        val out = ArrayList<TextRun>()
        var i = start
        while (i < end) {
            val next = text.nextSpanTransition(i, end, CharStyleSpan::class.java)
            val style = charStyleAt(text, i)
            // Object characters get their own one-char runs carrying the objectId.
            var j = i
            while (j < next) {
                val obj = indexOf(text, OBJECT_CHAR, j).let { if (it < 0 || it >= next) -1 else it }
                if (obj < 0) {
                    out += TextRun(next - j, style)
                    j = next
                } else {
                    if (obj > j) out += TextRun(obj - j, style)
                    val id = text.getSpans(obj, obj + 1, InlineObjectSpan::class.java).firstOrNull()?.objectId
                    out += TextRun(1, style.copy(objectId = id))
                    j = obj + 1
                }
            }
            i = next
        }
        return out
    }

    /** Style of the character at [offset] (default when uncovered). */
    fun charStyleAt(text: Spanned, offset: Int): CharStyle {
        if (offset < 0 || offset >= text.length) return CharStyle()
        val spans = text.getSpans(offset, offset + 1, CharStyleSpan::class.java)
        var found: CharStyleSpan? = null
        for (sp in spans) if (text.getSpanStart(sp) <= offset && text.getSpanEnd(sp) > offset) found = sp
        return found?.style ?: CharStyle()
    }

    /**
     * Replaces the character styles and inline objects of `[start, start + Σ runs)` with [runs].
     * Adjacent equal [CharStyleSpan]s at the edges are merged so typing doesn't fragment spans.
     */
    fun setRuns(text: Spannable, start: Int, runs: List<TextRun>, env: TextRenderEnv) {
        val end = start + runs.sumOf { it.length }
        if (end <= start) return
        clearCharStyles(text, start, end, env)
        for (sp in text.getSpans(start, end, InlineObjectSpan::class.java)) {
            if (text.getSpanStart(sp) >= start && text.getSpanEnd(sp) <= end) text.removeSpan(sp)
        }
        var pos = start
        for (run in runs) {
            val style = run.style.copy(objectId = null)
            if (style != CharStyle()) {
                text.setSpan(CharStyleSpan(style, env), pos, pos + run.length, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            }
            val id = run.style.objectId
            if (id != null) {
                for (k in pos until pos + run.length) {
                    if (text[k] == OBJECT_CHAR) text.setSpan(InlineObjectSpan(id, env), k, k + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            pos += run.length
        }
        var p = start
        for (run in runs) {
            mergeCharStylesAt(text, p, env)
            p += run.length
        }
        mergeCharStylesAt(text, end, env)
    }

    /** Applies one [style] to `[start, end)` (typing). */
    fun setStyle(text: Spannable, start: Int, end: Int, style: CharStyle, env: TextRenderEnv) {
        if (end <= start) return
        // Fast path: already covered by a single span of that style (or uncovered and default).
        val spans = text.getSpans(start, end, CharStyleSpan::class.java).filter { text.getSpanEnd(it) > start && text.getSpanStart(it) < end }
        if (style == CharStyle() && spans.isEmpty()) return
        if (spans.size == 1 && spans[0].style == style && text.getSpanStart(spans[0]) <= start && text.getSpanEnd(spans[0]) >= end) return
        setRuns(text, start, listOf(TextRun(end - start, style)), env)
    }

    /** Removes character styling from `[start, end)`, splitting spans that cross the edges. */
    fun clearCharStyles(text: Spannable, start: Int, end: Int, env: TextRenderEnv) {
        for (sp in text.getSpans(start, end, CharStyleSpan::class.java)) {
            val ss = text.getSpanStart(sp)
            val se = text.getSpanEnd(sp)
            if (ss == se) {
                text.removeSpan(sp)
                continue
            }
            if (se <= start || ss >= end) continue
            text.removeSpan(sp)
            if (ss < start) text.setSpan(CharStyleSpan(sp.style, env), ss, start, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            if (se > end) text.setSpan(CharStyleSpan(sp.style, env), end, se, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
    }

    /** Merges a span ending at [pos] with an equal one starting there; drops empty spans at [pos]. */
    fun mergeCharStylesAt(text: Spannable, pos: Int, env: TextRenderEnv) {
        val spans = text.getSpans(maxOf(0, pos - 1), minOf(text.length, pos + 1), CharStyleSpan::class.java)
        var left: CharStyleSpan? = null
        var right: CharStyleSpan? = null
        for (sp in spans) {
            val ss = text.getSpanStart(sp)
            val se = text.getSpanEnd(sp)
            when {
                ss == se -> text.removeSpan(sp)
                se == pos -> left = sp
                ss == pos -> right = sp
            }
        }
        val l = left
        val r = right
        if (l != null && r != null && l.style == r.style) {
            val ls = text.getSpanStart(l)
            val re = text.getSpanEnd(r)
            text.removeSpan(l)
            text.removeSpan(r)
            text.setSpan(CharStyleSpan(l.style, env), ls, re, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
    }

    internal fun indexOf(text: CharSequence, c: Char, from: Int): Int {
        for (i in from until text.length) if (text[i] == c) return i
        return -1
    }
}
