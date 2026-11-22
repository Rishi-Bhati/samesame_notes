package org.samesame.notes.feature.files

import org.samesame.notes.core.model.files.FileNames
import org.samesame.notes.core.model.saveas.SaveAsOptions
import org.samesame.notes.core.model.saveas.SaveOption
import org.samesame.notes.core.model.saveas.SaveSourceKind
import org.samesame.notes.core.model.textimport.TextImport

/** What a received file is, for the share-receive sheet (docs/architecture/files-feature.md 2.7 and 3.3). */
enum class ReceiveKind {
    /** `.ssnote` or `.sdocx`: can become a note, is also kept in Files. */
    NoteFile,
    Pdf,
    /** txt, md, csv and similar: opens as a note whose body is the text. */
    Text,
    Image,
    /** mp3, m4a, ...: opens as a note with the file as a voice recording. */
    Audio,
    /** Everything else: saved to Files only. */
    Other,
}

object ReceiveRouting {
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "3gp")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    fun kindOf(displayName: String?, mimeType: String?): ReceiveKind {
        val ext = FileNames.extensionOf(displayName.orEmpty()).lowercase()
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            ext == "ssnote" || ext == "sdocx" || mime == "application/sdoc" -> ReceiveKind.NoteFile
            ext in TextImport.extensions || ext.isEmpty() && mime in TextImport.mimeTypes -> ReceiveKind.Text
            ext == "pdf" || mime == "application/pdf" -> ReceiveKind.Pdf
            ext in AUDIO_EXT && !mime.startsWith("video/") || mime.startsWith("audio/") && ext !in IMAGE_EXT -> ReceiveKind.Audio
            ext in IMAGE_EXT || mime.startsWith("image/") && ext.isEmpty() -> ReceiveKind.Image
            else -> ReceiveKind.Other
        }
    }

    /** The stored extension of a received picture: from the name, else the MIME type; jpeg -> jpg; "" when neither says. */
    fun imageExtension(displayName: String?, mimeType: String?): String {
        val ext = FileNames.extensionOf(displayName.orEmpty()).lowercase()
        val e = if (ext in IMAGE_EXT) ext else mimeType?.substringBefore(';')?.trim()?.lowercase()?.substringAfter("image/", "").orEmpty()
        return if (e == "jpeg") "jpg" else e
    }

    /** The stored extension of received audio: from the name, else the MIME type's subtype, else m4a. */
    fun audioExtension(displayName: String?, mimeType: String?): String {
        val ext = FileNames.extensionOf(displayName.orEmpty()).lowercase()
        if (ext in AUDIO_EXT) return ext
        return when (val sub = mimeType?.substringBefore(';')?.trim()?.lowercase()?.substringAfter("audio/", "").orEmpty()) {
            "mpeg", "mp3" -> "mp3"
            "mp4", "x-m4a", "m4a" -> "m4a"
            "x-wav", "wav" -> "wav"
            "" -> "m4a"
            else -> sub.removePrefix("x-").filter { it.isLetterOrDigit() }.ifEmpty { "m4a" }
        }
    }

    /** True when a file of this kind can be offered "New note" (the existing note importer reads it). */
    fun canBecomeNote(kind: ReceiveKind) = kind == ReceiveKind.NoteFile || kind == ReceiveKind.Pdf || kind == ReceiveKind.Text ||
        kind == ReceiveKind.Image || kind == ReceiveKind.Audio

    /** What [SaveAsOptions] calls a received item: Text splits into Markdown and other text by name and type. */
    fun saveKindOf(kind: ReceiveKind, name: String?, mimeType: String?): SaveSourceKind = when (kind) {
        ReceiveKind.NoteFile -> SaveSourceKind.NoteFile
        ReceiveKind.Pdf -> SaveSourceKind.Pdf
        ReceiveKind.Text -> if (SaveAsOptions.isMarkdown(name, mimeType)) SaveSourceKind.Markdown else SaveSourceKind.Text
        ReceiveKind.Image -> SaveSourceKind.Image
        ReceiveKind.Audio -> SaveSourceKind.Audio
        ReceiveKind.Other -> SaveSourceKind.Other
    }

    /** The sheet's destinations for [items] (Files, New note, Save a copy, PDF), in display order. */
    fun options(items: List<ReceivedItem>): List<SaveOption> = SaveAsOptions.forReceived(items.map { saveKindOf(it.kind, it.name, it.mimeType) })

    /** Default destination: note, text, picture and audio files open as notes; everything else is kept in Files. */
    // Text, images and audio become notes; Other files stay in Files.
    fun defaultToNewNote(kinds: List<ReceiveKind>): Boolean = kinds.isNotEmpty() && kinds.all { it == ReceiveKind.NoteFile || it == ReceiveKind.Text || it == ReceiveKind.Image || it == ReceiveKind.Audio }
}
