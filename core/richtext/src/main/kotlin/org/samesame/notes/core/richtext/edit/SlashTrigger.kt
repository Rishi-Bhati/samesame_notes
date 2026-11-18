package org.samesame.notes.core.richtext.edit

/**
 * The `///` token that opens the inline file picker. Pure: the editor state feeds it the text before the caret.
 */
object SlashTrigger {
    /** [start] is the offset (in the given text) of the first slash; [query] is what follows `///` up to the caret. */
    data class Match(val start: Int, val query: String) {
        /** Offset just after the query, i.e. the caret the editor detected the token at. */
        val caret: Int get() = start + TOKEN.length + query.length
    }

    private const val TOKEN = "///"

    /**
     * The token the caret sits in, or null. [beforeCaret] is the text before the caret (earlier lines don't matter).
     * Null when: there is no `///` on the caret's line, it is part of a longer slash run (`////`) or of a URL
     * (`file:///`, `x+y:///`), or the query holds a double space (a second space in a row closes the picker).
     */
    fun detect(beforeCaret: CharSequence): Match? {
        val text = beforeCaret.toString()
        val lineStart = text.lastIndexOf('\n') + 1
        val i = text.lastIndexOf(TOKEN)
        if (i < lineStart) return null
        if (i > 0 && text[i - 1] == '/') return null
        if (isUrlScheme(text, i)) return null
        val query = text.substring(i + TOKEN.length)
        if (query.contains("  ")) return null
        return Match(i, query)
    }

    /** True when a scheme (`file:`, `http:`, `x+y:`) ends right before [index]. */
    private fun isUrlScheme(text: String, index: Int): Boolean {
        if (index < 2 || text[index - 1] != ':') return false
        val c = text[index - 2]
        return c.isLetterOrDigit() || c == '+' || c == '-' || c == '.'
    }
}
