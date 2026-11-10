package org.samesame.notes.core.richtext.edit

/**
 * The sticky-style rule for link runs (format v8, `CharStyle.link`) when the editor replaces `[start, start + count)` by
 * `after` characters. Pure, so it is JVM-testable; `RichTextOps.typingStyle` is the same rule for a caret.
 */
object LinkTyping {
    /**
     * How many of the `after` new characters keep the [link] of the style they are typed with:
     * - no link: irrelevant (all of them; the style has no link to keep);
     * - a pure insertion (`count == 0`): the caller already chose the style with `RichTextOps.typingStyle`, so all keep it
     *   (it only has a link when the caret is inside the link);
     * - a replacement that doesn't grow, or one followed by more of the same link ([linkContinues]): all (editing the
     *   label, or a keyboard re-composing a word inside the link);
     * - a replacement that grows at the end of the link (a keyboard re-composing the last word of a link and appending to
     *   it): only the first [count]; the appended characters are plain text.
     */
    fun linkedLength(link: String?, count: Int, after: Int, linkContinues: Boolean): Int = when {
        link == null || count == 0 -> after
        after <= count || linkContinues -> after
        else -> count
    }
}
