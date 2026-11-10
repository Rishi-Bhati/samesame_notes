package org.samesame.notes.feature.files

import org.samesame.notes.core.model.files.FileNames

/** What a received file is, for the share-receive sheet (docs/architecture/files-feature.md 2.7 and 3.3). */
enum class ReceiveKind {
    /** `.ssnote` or `.sdocx`: can become a note, is also kept in Files. */
    NoteFile,
    Pdf,
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
            ext == "pdf" || mime == "application/pdf" -> ReceiveKind.Pdf
            ext in IMAGE_EXT || mime.startsWith("image/") && ext.isEmpty() -> ReceiveKind.Image
            else -> ReceiveKind.Other
        }
    }

    /** True when a file of this kind can be offered "New note" (the existing note importer reads it). */
    fun canBecomeNote(kind: ReceiveKind) = kind == ReceiveKind.NoteFile || kind == ReceiveKind.Pdf

    /** Default destination: note files open as notes (that is what opening one has always done); everything else is kept in Files. */
    fun defaultToNewNote(kinds: List<ReceiveKind>): Boolean = kinds.isNotEmpty() && kinds.all { it == ReceiveKind.NoteFile }
}
