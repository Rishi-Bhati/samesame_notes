package org.samesame.notes.core.model.richtext

import org.samesame.notes.core.model.Limits

/**
 * Pure editing operations on [RichText]. Offsets are clamped to `0..length` (and swapped if
 * reversed); every result is normalised.
 */
object RichTextOps {

    /**
     * Inserts plain [text] at [offset]. Style: [style] if given, else the style of the character
     * before the offset (after it at offset 0). `objectId` is never inherited, and U+FFFC in [text]
     * is dropped (use [insertObject]). Each `\n` splits the paragraph; the new paragraphs inherit
     * the split paragraph's style (list kind kept, `checked` cleared).
     */
    fun insert(rt: RichText, offset: Int, text: String, style: CharStyle? = null): RichText {
        val clean = text.replace(OBJECT_CHAR.toString(), "")
        if (clean.isEmpty()) return normalise(rt)
        val at = offset.coerceIn(0, rt.length)
        val base = (style ?: styleBefore(rt, at)).copy(objectId = null)
        val pIdx = paragraphIndexAt(rt, at)
        val inherited = rt.paragraphs.getOrElse(pIdx) { ParagraphStyle() }.copy(checked = false)
        val newlines = clean.count { it == '\n' }
        val fragment = RichText(
            text = clean,
            runs = listOf(TextRun(clean.length, base)),
            paragraphs = List(newlines + 1) { inherited },
        )
        return insertRich(rt, at, fragment)
    }

    /** Inserts a single inline object (U+FFFC with [objectId]) at [offset]. */
    fun insertObject(rt: RichText, offset: Int, objectId: String, style: CharStyle? = null): RichText {
        val at = offset.coerceIn(0, rt.length)
        val base = (style ?: styleBefore(rt, at)).copy(objectId = objectId)
        val fragment = RichText(OBJECT_CHAR.toString(), listOf(TextRun(1, base)))
        return insertRich(rt, at, fragment)
    }

    /**
     * Inserts a rich [fragment] (e.g. from [slice], for paste). Fragment runs are kept as is. When
     * the fragment contains newlines, the paragraph holding [offset] keeps its own style for the
     * head part, middle paragraphs take the fragment's styles, and the last paragraph (fragment's
     * last + the tail of the split one) takes the fragment's last style, or the split paragraph's
     * style when the fragment's last paragraph is plain default (so Enter-like pastes continue it).
     */
    fun insertRich(rt: RichText, offset: Int, fragment: RichText): RichText {
        val src = normalise(rt)
        val frag = normalise(fragment)
        if (frag.text.isEmpty()) return src
        val at = offset.coerceIn(0, src.length)
        val runs = sliceRuns(src.runs, 0, at) + frag.runs + sliceRuns(src.runs, at, src.length)
        val text = src.text.substring(0, at) + frag.text + src.text.substring(at)
        val p = paragraphIndexAt(src, at)
        val split = src.paragraphs[p]
        val fp = frag.paragraphs
        val paragraphs = ArrayList<ParagraphStyle>()
        paragraphs.addAll(src.paragraphs.subList(0, p))
        paragraphs.add(split) // head
        if (fp.size > 1) {
            for (i in 1 until fp.size - 1) paragraphs.add(fp[i])
            val last = fp.last()
            paragraphs.add(if (last == ParagraphStyle()) split.copy(checked = false) else last)
        }
        paragraphs.addAll(src.paragraphs.subList(p + 1, src.paragraphs.size))
        return normalise(RichText(text, runs, paragraphs))
    }

    /**
     * Deletes `[start, end)`. Removed newlines join paragraphs; the joined paragraph keeps the
     * style of the paragraph containing [start].
     */
    fun delete(rt: RichText, start: Int, end: Int): RichText {
        val src = normalise(rt)
        val (s, e) = range(src, start, end)
        if (s == e) return src
        val pStart = paragraphIndexAt(src, s)
        val pEnd = paragraphIndexAt(src, e)
        val paragraphs = src.paragraphs.subList(0, pStart + 1) + src.paragraphs.subList(pEnd + 1, src.paragraphs.size)
        return normalise(
            RichText(
                text = src.text.removeRange(s, e),
                runs = sliceRuns(src.runs, 0, s) + sliceRuns(src.runs, e, src.length),
                paragraphs = paragraphs,
            ),
        )
    }

    /** Replace = delete + insert of [text] (style from [style] or the char before [start]). */
    fun replace(rt: RichText, start: Int, end: Int, text: String, style: CharStyle? = null): RichText {
        val src = normalise(rt)
        val (s, e) = range(src, start, end)
        val st = style ?: styleBefore(src, s)
        return insert(delete(src, s, e), s, text, st)
    }

    /**
     * Copy of `[start, end)` as a standalone RichText. Paragraph styles are those of the paragraphs
     * the range touches (first = the one containing [start]).
     */
    fun slice(rt: RichText, start: Int, end: Int): RichText {
        val src = normalise(rt)
        val (s, e) = range(src, start, end)
        val pStart = paragraphIndexAt(src, s)
        val pEnd = paragraphIndexAt(src, e)
        return normalise(
            RichText(
                text = src.text.substring(s, e),
                runs = sliceRuns(src.runs, s, e),
                paragraphs = src.paragraphs.subList(pStart, pEnd + 1),
            ),
        )
    }

    /**
     * Applies [transform] to the style of every character in `[start, end)`. The `objectId` of a
     * character can't be changed by it. Collapsed ranges are a no-op.
     */
    fun applyCharStyle(rt: RichText, start: Int, end: Int, transform: (CharStyle) -> CharStyle): RichText {
        val src = normalise(rt)
        val (s, e) = range(src, start, end)
        if (s == e) return src
        val mid = sliceRuns(src.runs, s, e).map { run ->
            TextRun(run.length, transform(run.style).copy(objectId = run.style.objectId))
        }
        return normalise(src.copy(runs = sliceRuns(src.runs, 0, s) + mid + sliceRuns(src.runs, e, src.length)))
    }

    /**
     * Applies [transform] to every paragraph touched by `[start, end)`. A collapsed range affects the
     * paragraph at the caret. A range ending exactly at the start of a paragraph (just after a `\n`)
     * does not include that paragraph. Indent is clamped to `0..MAX_INDENT_LEVEL`.
     */
    fun setParagraphStyle(rt: RichText, start: Int, end: Int, transform: (ParagraphStyle) -> ParagraphStyle): RichText {
        val src = normalise(rt)
        val (first, last) = paragraphSpan(src, start, end)
        val paragraphs = src.paragraphs.mapIndexed { i, p ->
            if (i in first..last) transform(p).let { it.copy(indent = it.indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)) } else p
        }
        return src.copy(paragraphs = paragraphs)
    }

    /**
     * If every touched paragraph already has [kind], turns the list off (None); otherwise sets [kind]
     * on all of them. `checked` is cleared whenever the kind changes.
     */
    fun toggleList(rt: RichText, start: Int, end: Int, kind: ListKind): RichText {
        val src = normalise(rt)
        val (first, last) = paragraphSpan(src, start, end)
        val allSame = (first..last).all { src.paragraphs[it].list == kind }
        val target = if (allSame) ListKind.None else kind
        return setParagraphStyle(src, start, end) {
            if (it.list == target && !it.checked) it else it.copy(list = target, checked = false)
        }
    }

    /**
     * Restores the invariants: drops empty runs, pads/truncates runs to the text length (padding uses
     * the last run's style), merges equal adjacent runs (never runs with an objectId), clears
     * `objectId` on non-U+FFFC characters and splits object runs to one char each, and resizes
     * paragraphs to `count('\n') + 1` (padding with default, truncating the tail).
     */
    fun normalise(rt: RichText): RichText {
        val len = rt.text.length
        var runs = rt.runs.filter { it.length > 0 }
        val total = runs.sumOf { it.length }
        runs = when {
            len == 0 -> emptyList()
            total < len -> runs + TextRun(len - total, runs.lastOrNull()?.style?.copy(objectId = null) ?: CharStyle())
            total > len -> sliceRuns(runs, 0, len)
            else -> runs
        }
        val out = ArrayList<TextRun>(runs.size)
        var pos = 0
        for (run in runs) {
            if (run.style.objectId == null) {
                pos += run.length
                appendMerged(out, run)
            } else {
                for (i in 0 until run.length) {
                    val st = if (rt.text[pos + i] == OBJECT_CHAR) run.style else run.style.copy(objectId = null)
                    appendMerged(out, TextRun(1, st))
                }
                pos += run.length
            }
        }
        val pCount = rt.text.count { it == '\n' } + 1
        val paragraphs = when {
            rt.paragraphs.size == pCount -> rt.paragraphs
            rt.paragraphs.size > pCount -> rt.paragraphs.take(pCount)
            else -> rt.paragraphs + List(pCount - rt.paragraphs.size) { ParagraphStyle() }
        }.map { it.copy(indent = it.indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)) }
        return RichText(rt.text, out, paragraphs)
    }

    // ---- helpers ----

    /** Index of the paragraph containing [offset] (= number of `\n` before it). */
    fun paragraphIndexAt(rt: RichText, offset: Int): Int {
        val end = offset.coerceIn(0, rt.text.length)
        var n = 0
        for (i in 0 until end) if (rt.text[i] == '\n') n++
        return n
    }

    /** Character range `[start, end)` of paragraph [index], excluding its trailing `\n`. */
    fun paragraphRange(rt: RichText, index: Int): IntRange {
        var start = 0
        var i = 0
        while (i < index) {
            val nl = rt.text.indexOf('\n', start)
            if (nl < 0) return rt.text.length until rt.text.length
            start = nl + 1
            i++
        }
        val nl = rt.text.indexOf('\n', start)
        return start until (if (nl < 0) rt.text.length else nl)
    }

    /** Style of the character before [offset], or after it at 0; default when empty. */
    fun styleBefore(rt: RichText, offset: Int): CharStyle {
        if (rt.text.isEmpty()) return CharStyle()
        val idx = if (offset <= 0) 0 else minOf(offset, rt.text.length) - 1
        var pos = 0
        for (run in rt.runs) {
            if (idx < pos + run.length) return run.style.copy(objectId = null)
            pos += run.length
        }
        return rt.runs.lastOrNull()?.style?.copy(objectId = null) ?: CharStyle()
    }

    private fun range(rt: RichText, start: Int, end: Int): Pair<Int, Int> {
        val a = start.coerceIn(0, rt.text.length)
        val b = end.coerceIn(0, rt.text.length)
        return if (a <= b) a to b else b to a
    }

    private fun paragraphSpan(rt: RichText, start: Int, end: Int): Pair<Int, Int> {
        val (s, e) = range(rt, start, end)
        val first = paragraphIndexAt(rt, s)
        var last = paragraphIndexAt(rt, e)
        if (e > s && rt.text[e - 1] == '\n') last = maxOf(first, last - 1)
        return first to last
    }

    private fun appendMerged(out: MutableList<TextRun>, run: TextRun) {
        val prev = out.lastOrNull()
        if (prev != null && prev.style == run.style && run.style.objectId == null) {
            out[out.lastIndex] = TextRun(prev.length + run.length, prev.style)
        } else {
            out.add(run)
        }
    }

    /** Runs covering `[start, end)`, cut at the boundaries (not merged). */
    private fun sliceRuns(runs: List<TextRun>, start: Int, end: Int): List<TextRun> {
        if (end <= start) return emptyList()
        val out = ArrayList<TextRun>()
        var pos = 0
        for (run in runs) {
            val rs = pos
            val re = pos + run.length
            pos = re
            val s = maxOf(rs, start)
            val e = minOf(re, end)
            if (e > s) out.add(TextRun(e - s, run.style))
            if (re >= end) break
        }
        return out
    }
}
