package org.samesame.notes.core.model.textdoc

import java.nio.charset.Charset

/** How a text document's bytes are encoded. The names are the ones shown in the editor's menu. */
enum class TextEncoding(val charsetName: String, val displayName: String) {
    Utf8("UTF-8", "UTF-8"),
    Utf16Le("UTF-16LE", "UTF-16 LE"),
    Utf16Be("UTF-16BE", "UTF-16 BE"),
    Windows1252("windows-1252", "ANSI (Windows-1252)"),
    Iso88591("ISO-8859-1", "ISO-8859-1"),
    ;

    /** The Java charset, falling back to UTF-8 when the platform lacks the encoding. */
    val charset: Charset get() = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
}
