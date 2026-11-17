package org.samesame.notes.feature.files

import org.samesame.notes.core.model.files.FileNames
import org.samesame.notes.core.model.textimport.TextImport

/** What a received file is, for the share-receive sheet (docs/architecture/files-feature.md 2.7 and 3.3). */
enum class ReceiveKind {
    /** `.ssnote` or `.sdocx`: can become a note, is also kept in Files. */
    NoteFile,
    Pdf,
    /** txt, md, csv and similar: opens as a note whose body is the text. */
    Text,
    Image,
    /** Everything else: saved to Files only. */
    Other,
}

object ReceiveRouting {
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    fun kindOf(displayName: String?, mimeType: String?): ReceiveKind {
        val ext = FileNames.extensionOf(displayName.orEmpty()).lowercase()
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            ext == "ssnote" || ext == "sdocx" || mime == "application/sdoc" -> ReceiveKind.NoteFile
            ext in TextImport.extensions || ext.isEmpty() && mime in TextImport.mimeTypes -> ReceiveKind.Text
            ext == "pdf" || mime == "application/pdf" -> ReceiveKind.Pdf
            ext in IMAGE_EXT || mime.startsWith("image/") && ext.isEmpty() -> ReceiveKind.Image
            else -> ReceiveKind.Other
        }
    }

    /** True when a file of this kind can be offered "New note" (the existing note importer reads it). */
    fun canBecomeNote(kind: ReceiveKind) = kind == ReceiveKind.NoteFile || kind == ReceiveKind.Pdf || kind == ReceiveKind.Text

    /** Default destination: note files and text files open as notes; everything else is kept in Files. */
    fun defaultToNewNote(kinds: List<ReceiveKind>): Boolean = kinds.isNotEmpty() && kinds.all { it == ReceiveKind.NoteFile || it == ReceiveKind.Text }
}
