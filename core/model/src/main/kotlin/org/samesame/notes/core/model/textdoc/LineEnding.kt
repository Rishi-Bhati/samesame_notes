package org.samesame.notes.core.model.textdoc

/** The line separator a text file uses. Text is normalised to `\n` in memory. */
enum class LineEnding(val token: String, val displayName: String) {
    Lf("\n", "LF"),
    Crlf("\r\n", "CRLF"),
    Cr("\r", "CR"),
}
