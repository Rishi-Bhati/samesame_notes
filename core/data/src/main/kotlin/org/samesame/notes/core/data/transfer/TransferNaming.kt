package org.samesame.notes.core.data.transfer

import org.samesame.notes.core.export.sanitizeFileName
import org.samesame.notes.core.model.Note

/** File naming for shared / saved notes: readable names, unique within one batch. */
object TransferNaming {
    /** Title of the note, else the first line of its preview, else the generic name (see [sanitizeFileName]). */
    fun baseName(note: Note): String {
        val title = note.title.trim()
        if (title.isNotEmpty()) return sanitizeFileName(title)
        val firstLine = note.previewText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return sanitizeFileName(firstLine.orEmpty())
    }

    /**
     * `Title.ext`, or `Title (1).ext`, `Title (2).ext` ... when that name (case-insensitive) is in [taken]. The chosen name
     * is added to [taken].
     */
    fun uniqueName(base: String, extension: String, taken: MutableSet<String>): String {
        var candidate = "$base.$extension"
        var n = 0
        while (!taken.add(candidate.lowercase())) {
            n++
            candidate = "$base ($n).$extension"
        }
        return candidate
    }

    /** The MIME type to put on a multi-file share: the shared one, else `type/*`, else `*/*`. */
    fun commonMimeType(mimeTypes: List<String>): String {
        val distinct = mimeTypes.distinct()
        if (distinct.size == 1) return distinct[0]
        val majors = distinct.map { it.substringBefore('/') }.distinct()
        return if (majors.size == 1) majors[0] + "/*" else "*/*"
    }
}
