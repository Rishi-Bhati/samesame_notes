package org.samesame.notes.core.richtext.edit

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.ParagraphStyle

/** Pure paragraph bookkeeping shared by the engine and its JVM tests. */
object ParagraphRules {

    /**
     * Paragraph list after replacing a range that starts in paragraph [first], removed
     * [removedNewlines] `\n` and inserted [insertedNewlines]. Same rules as `RichTextOps`: the joined
     * paragraph keeps the style of the paragraph containing the start, new paragraphs inherit it
     * with `checked = false`.
     */
    fun applyReplace(
        paragraphs: List<ParagraphStyle>,
        first: Int,
        removedNewlines: Int,
        insertedNewlines: Int,
    ): List<ParagraphStyle> {
        if (removedNewlines == 0 && insertedNewlines == 0) return paragraphs
        val head = paragraphs[first]
        val out = ArrayList<ParagraphStyle>(paragraphs.size - removedNewlines + insertedNewlines)
        out.addAll(paragraphs.subList(0, first))
        out.add(head)
        repeat(insertedNewlines) { out.add(head.copy(checked = false)) }
        out.addAll(paragraphs.subList(first + 1 + removedNewlines, paragraphs.size))
        return out
    }

    /**
     * Numbers for `Numbered` paragraphs (0 for others): consecutive numbered paragraphs at the same
     * indent count up; deeper paragraphs don't interrupt a shallower list; anything else at the same
     * or a shallower indent restarts it (doc §1.3: derived at layout time, never stored).
     */
    fun numbering(paragraphs: List<ParagraphStyle>): IntArray {
        val out = IntArray(paragraphs.size)
        val counters = IntArray(Limits.MAX_INDENT_LEVEL + 1)
        for ((i, p) in paragraphs.withIndex()) {
            val level = p.indent.coerceIn(0, Limits.MAX_INDENT_LEVEL)
            if (p.list == ListKind.Numbered) {
                counters[level]++
                out[i] = counters[level]
                for (d in level + 1..Limits.MAX_INDENT_LEVEL) counters[d] = 0
            } else {
                for (d in level..Limits.MAX_INDENT_LEVEL) counters[d] = 0
            }
        }
        return out
    }

    /** What Enter does in a paragraph. */
    enum class EnterAction { InsertNewline, EndList, Outdent }

    /**
     * Enter on an empty list item ends the list instead of adding another item; when it is indented it
     * outdents one level first and only ends the list at level 0.
     */
    fun onEnter(paragraph: ParagraphStyle, paragraphIsEmpty: Boolean): EnterAction = when {
        paragraph.list == ListKind.None || !paragraphIsEmpty -> EnterAction.InsertNewline
        paragraph.indent > 0 -> EnterAction.Outdent
        else -> EnterAction.EndList
    }

    /**
     * Backspace with the caret at the very start of a paragraph: a list item loses its marker first
     * (text and indent kept), otherwise the paragraph joins the previous one.
     * NOTE(WP-F): assumed from common editors; not captured.
     */
    fun backspaceRemovesMarker(paragraph: ParagraphStyle): Boolean = paragraph.list != ListKind.None

    fun indent(p: ParagraphStyle): ParagraphStyle = p.copy(indent = (p.indent + 1).coerceAtMost(Limits.MAX_INDENT_LEVEL))
    fun outdent(p: ParagraphStyle): ParagraphStyle = p.copy(indent = (p.indent - 1).coerceAtLeast(0))
}
