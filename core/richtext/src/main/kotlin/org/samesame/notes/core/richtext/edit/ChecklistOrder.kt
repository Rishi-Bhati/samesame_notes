package org.samesame.notes.core.richtext.edit

import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.core.model.richtext.TextRun

/**
 * Pure ordering rules for "Move checked items to the bottom" (0.3.1-alpha D).
 *
 * A run is a maximal stretch of checkbox paragraphs at the same indent; paragraphs nested more deeply
 * are carried with the item above them. Ticking moves the item's block to just after the last unchecked
 * item of the run, unticking moves it to just after the last unchecked item (the end of the unchecked
 * group). Both directions use the same target, so the two groups stay contiguous.
 *
 * All indices here are paragraph indices. [moveAfterToggle] computes the target index, [orderAfterToggle]
 * applies it to the whole list, and [reorder] rebuilds a rich-text region in the new order. Keeping this
 * pure lets the JVM tests cover the ordering without the Android editor.
 */
object ChecklistOrder {

    /** Move the block of [size] paragraphs starting at [from] so it starts at [to]. Indices are before removal. */
    data class Move(val from: Int, val size: Int, val to: Int)

    /**
     * Where the block containing [index] should go after its checkbox becomes [checkedAfter], or null
     * when it should stay where it is. [styles] still holds the old `checked` value; only `checkbox`
     * and `indent` decide the run.
     */
    fun moveAfterToggle(styles: List<ParagraphStyle>, index: Int, checkedAfter: Boolean): Move? {
        val p = styles.getOrNull(index) ?: return null
        if (!p.checkbox) return null
        val base = p.indent

        var blockEnd = index + 1
        while (blockEnd < styles.size && styles[blockEnd].indent > base) blockEnd++
        val size = blockEnd - index

        var runStart = index
        while (runStart > 0) {
            val s = styles[runStart - 1]
            if (s.indent < base || (s.indent == base && !s.checkbox)) break
            runStart--
        }
        var runEnd = blockEnd
        while (runEnd < styles.size) {
            val s = styles[runEnd]
            if (s.indent < base || (s.indent == base && !s.checkbox)) break
            runEnd++
        }

        // The last unchecked item of the run, excluding the block being moved.
        var lastUnchecked = -1
        for (i in runStart until runEnd) {
            if (i in index until blockEnd) continue
            val s = styles[i]
            if (s.indent == base && s.checkbox && !s.checked) lastUnchecked = i
        }

        val insertAt = if (lastUnchecked >= 0) {
            var end = lastUnchecked + 1
            while (end < runEnd && styles[end].indent > base) end++
            end
        } else if (checkedAfter) {
            runEnd // everything else is checked: join the checked group at the bottom
        } else {
            runStart // nothing is unchecked: become the first item of the run
        }
        // [insertAt] is an index in the original list. Removing the block first shifts it down.
        val to = if (insertAt <= index) insertAt else insertAt - size
        if (to == index) return null
        return Move(index, size, to)
    }

    /** The paragraph indices of [styles] in their new order, or null when nothing moves. */
    fun orderAfterToggle(styles: List<ParagraphStyle>, index: Int, checkedAfter: Boolean): List<Int>? {
        val move = moveAfterToggle(styles, index, checkedAfter) ?: return null
        val out = styles.indices.toMutableList()
        val block = out.subList(move.from, move.from + move.size).toList()
        repeat(move.size) { out.removeAt(move.from) }
        out.addAll(move.to, block)
        return out
    }

    /**
     * Rebuilds [region] (one or more whole paragraphs) in [order], using [stylesAfter] for the new
     * paragraph styles. Runs, links and objects travel with their text; only the order changes.
     */
    fun reorder(region: RichText, order: List<Int>, stylesAfter: List<ParagraphStyle>): RichText {
        require(order.size == region.paragraphs.size) { "order must cover every paragraph of the region" }
        require(stylesAfter.size == region.paragraphs.size) { "one style per paragraph of the region" }

        data class Chunk(val text: String, val runs: List<TextRun>, val newline: TextRun?)
        val chunks = ArrayList<Chunk>(region.paragraphs.size)
        var pos = 0
        for (i in region.paragraphs.indices) {
            val nl = region.text.indexOf('\n', pos)
            val end = if (nl < 0) region.text.length else nl
            val slice = RichTextOps.slice(region, pos, end)
            val newline = if (nl < 0) null else {
                val nlSlice = RichTextOps.slice(region, end, end + 1)
                nlSlice.runs.firstOrNull() ?: TextRun(1, CharStyle())
            }
            chunks += Chunk(slice.text, slice.runs, newline)
            pos = if (nl < 0) region.text.length else nl + 1
        }

        val text = StringBuilder()
        val runs = ArrayList<TextRun>()
        val paragraphs = ArrayList<ParagraphStyle>(order.size)
        order.forEachIndexed { k, i ->
            text.append(chunks[i].text)
            runs.addAll(chunks[i].runs)
            paragraphs += stylesAfter[i]
            if (k < order.lastIndex) {
                text.append('\n')
                runs += chunks[i].newline ?: TextRun(1, CharStyle())
            }
        }
        return RichTextOps.normalise(RichText(text.toString(), runs, paragraphs))
    }
}
