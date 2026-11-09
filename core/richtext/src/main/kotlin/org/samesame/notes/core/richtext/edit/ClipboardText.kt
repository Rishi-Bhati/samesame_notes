package org.samesame.notes.core.richtext.edit

import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.ParagraphStyle
import org.samesame.notes.core.model.richtext.listMarkerPrefix

/**
 * The plain text a Copy / Cut puts on the system clipboard. List markers are not characters in the body (the editor
 * draws them in the paragraph's leading margin), so every paragraph whose start lies inside `[start, end)` gets its
 * marker (`• `, `N. `, `[x] ` / `[ ] `, two spaces per indent level: [listMarkerPrefix], shared with the .txt export).
 * [numbers] holds each paragraph's item number ([ParagraphRules.numbering]; 0 = not numbered). Inline-object
 * placeholders are dropped. Pure, so the clipboard text is JVM-testable.
 */
object ClipboardText {

    fun withListMarkers(text: CharSequence, paragraphs: List<ParagraphStyle>, numbers: IntArray, start: Int, end: Int): String {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        if (s == e) return ""
        val out = StringBuilder(e - s + 16)
        var paragraph = 0
        for (i in 0 until s) if (text[i] == '\n') paragraph++
        for (i in s until e) {
            if (i == 0 || text[i - 1] == '\n') {
                val number = numbers.getOrNull(paragraph)?.takeIf { it > 0 }
                out.append(listMarkerPrefix(paragraphs.getOrElse(paragraph) { ParagraphStyle() }, number))
            }
            val c = text[i]
            if (c == '\n') paragraph++
            if (c != OBJECT_CHAR) out.append(c)
        }
        return out.toString()
    }
}
