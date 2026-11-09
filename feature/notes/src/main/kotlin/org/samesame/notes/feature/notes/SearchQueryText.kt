package org.samesame.notes.feature.notes

private val LineBreaks = Regex("[\\r\\n\\u2028\\u2029]+")

/**
 * The search box is one line: text pasted into it (for example a copied note body) has its line breaks turned into
 * single spaces, like a platform single-line text field shows them. Without this the box kept the newlines, showed only
 * the first line and searched for a multi-line string that matched nothing.
 */
internal fun singleLineQuery(text: String): String =
    if (text.none { it == '\n' || it == '\r' || it == ' ' || it == ' ' }) text else text.replace(LineBreaks, " ")
