package org.samesame.notes.feature.files

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.textimport.TextImport

/** Plain text shared from another app (EXTRA_TEXT, no file): becomes a new note. Pure, so it is JVM-tested. */
data class SharedText(val title: String, val body: String) {
    companion object {
        /** [subject] is EXTRA_SUBJECT (blank = untitled); [texts] the EXTRA_TEXT value(s). Null when there is nothing to keep. */
        fun of(subject: CharSequence?, texts: List<CharSequence?>): SharedText? {
            val body = texts.mapNotNull { it?.toString()?.let(TextImport::normaliseLineBreaks)?.trim('\n')?.takeIf { s -> s.isNotBlank() } }
                .joinToString("\n\n")
            if (body.isBlank()) return null
            val title = subject?.toString()?.lineSequence()?.firstOrNull().orEmpty().trim().take(MAX_TITLE)
            return SharedText(title, body.take(Limits.MAX_TEXT_IMPORT_BYTES.toInt()))
        }

        private const val MAX_TITLE = 200
    }
}
