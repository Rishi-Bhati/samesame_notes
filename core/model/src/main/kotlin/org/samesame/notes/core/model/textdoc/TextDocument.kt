package org.samesame.notes.core.model.textdoc

/**
 * A text file as the editor holds it: text with `\n` line endings, plus the encoding and line ending it is written
 * back with. [isReadOnly] is set for files the editor must not overwrite (over the 5 MB edit cap, or a URI without a
 * write grant).
 */
data class TextDocument(
    val text: String,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
    val hasBom: Boolean,
    val isReadOnly: Boolean = false,
    val isTruncated: Boolean = false,
)
