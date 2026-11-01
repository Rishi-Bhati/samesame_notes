package org.samesame.notes.core.export.text

import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.RichText

/** A piece of text with one style; an inline object is a span whose text is [OBJECT_CHAR] and whose style has an objectId. */
data class Span(val text: String, val style: CharStyle) {
    val isObject: Boolean get() = style.objectId != null && text == OBJECT_CHAR.toString()
}

/** One `\n`-separated paragraph with its derived list number (1-based, only for Numbered). */
data class Para(val style: ParagraphStyle, val spans: List<Span>, val number: Int?) {
    val plain: String get() = spans.filterNot { it.isObject }.joinToString("") { it.text }
}

/** Splits a [RichText] into paragraphs of uniformly styled spans and numbers the Numbered lists. */
fun RichText.toParagraphs(): List<Para> {
    // run boundaries
    val styleAt = ArrayList<CharStyle>(text.length)
    for (run in runs) repeat(run.length) { styleAt.add(run.style) }
    while (styleAt.size < text.length) styleAt.add(CharStyle())

    val result = ArrayList<Para>()
    var start = 0
    var index = 0
    val counters = HashMap<Int, Int>()
    var previousNumbered = false
    while (start <= text.length) {
        var end = text.indexOf('\n', start)
        if (end < 0) end = text.length
        val spans = ArrayList<Span>()
        var i = start
        while (i < end) {
            val style = styleAt[i]
            if (text[i] == OBJECT_CHAR) {
                spans += Span(OBJECT_CHAR.toString(), style)
                i++
                continue
            }
            var j = i + 1
            while (j < end && styleAt[j] == style && text[j] != OBJECT_CHAR) j++
            spans += Span(text.substring(i, j), style)
            i = j
        }
        val pstyle = paragraphs.getOrElse(index) { ParagraphStyle() }
        var number: Int? = null
        if (pstyle.list == ListKind.Numbered) {
            if (!previousNumbered) counters.clear()
            counters.keys.removeAll { it > pstyle.indent }
            number = (counters[pstyle.indent] ?: 0) + 1
            counters[pstyle.indent] = number
            previousNumbered = true
        } else {
            previousNumbered = false
            counters.clear()
        }
        result += Para(pstyle, spans, number)
        index++
        if (end >= text.length) break
        start = end + 1
    }
    return result
}

/** `#RRGGBB` of an ARGB long (alpha dropped). */
fun Long.rgbHex(): String = "%06X".format(this and 0xFFFFFF)
