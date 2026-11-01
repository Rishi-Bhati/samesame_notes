package org.samesame.notes.feature.composer.session

import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.RichTextOps
import org.samesame.notes.feature.composer.history.BodyTextTarget
import org.samesame.notes.feature.composer.history.TextSel

/** A [BodyTextTarget] over a plain [RichText] value: the stand-in until the text engine binds, and the test fake. */
class InMemoryBodyText(initial: RichText = RichText()) : BodyTextTarget {
    var text: RichText = RichTextOps.normalise(initial)
        private set
    var selection: TextSel = TextSel(0)
        private set

    override fun replaceSlice(start: Int, removedLength: Int, inserted: RichText, selection: TextSel) {
        val end = start + removedLength
        val p0 = RichTextOps.paragraphIndexAt(text, start)
        val p1 = RichTextOps.paragraphIndexAt(text, end)
        val head = RichTextOps.slice(text, 0, start)
        val tail = RichTextOps.slice(text, end, text.length)
        text = RichTextOps.normalise(
            RichText(
                text = head.text + inserted.text + tail.text,
                runs = head.runs + inserted.runs + tail.runs,
                paragraphs = text.paragraphs.subList(0, p0) + inserted.paragraphs + text.paragraphs.subList(p1 + 1, text.paragraphs.size),
            ),
        )
        this.selection = selection
    }

    override fun restoreCharStyles(start: Int, fragment: RichText, selection: TextSel) {
        var rt = text
        var pos = start
        for (run in fragment.runs) {
            rt = RichTextOps.applyCharStyle(rt, pos, pos + run.length) { run.style }
            pos += run.length
        }
        text = rt
        this.selection = selection
    }

    override fun restoreParagraphStyles(firstParagraph: Int, styles: List<ParagraphStyle>, selection: TextSel) {
        text = RichTextOps.normalise(
            text.copy(paragraphs = text.paragraphs.toMutableList().also { p -> styles.forEachIndexed { i, s -> p[firstParagraph + i] = s } }),
        )
        this.selection = selection
    }

    override fun toRichText(): RichText = text

    /** Test/legacy helper: applies a user edit and returns it as (start, removed, inserted) fragments. */
    fun edit(start: Int, end: Int, replacement: String): Triple<Int, RichText, RichText> {
        val removed = RichTextOps.slice(text, start, end)
        val style = RichTextOps.styleBefore(text, start)
        text = RichTextOps.replace(text, start, end, replacement, style)
        val inserted = RichTextOps.slice(text, start, start + replacement.length)
        selection = TextSel(start + replacement.length)
        return Triple(start, removed, inserted)
    }
}
