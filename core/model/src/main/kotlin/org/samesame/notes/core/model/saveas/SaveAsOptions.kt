package org.samesame.notes.core.model.saveas

import org.samesame.notes.core.model.files.FileNames

/** What a file opened with or shared to the app is, as far as its save options go. */
enum class SaveSourceKind {
    Markdown,
    /** Plain text, csv, code, html, ...: anything the text editor opens that is not Markdown. */
    Text,
    Pdf,
    Image,
    Audio,
    /** `.ssnote` or `.sdocx`. */
    NoteFile,
    /** Everything else: it can only be kept in Files or copied. */
    Other,
}

/** One way of keeping a file that was opened with the app. */
enum class SaveOption {
    /** Write the edits back to the opened document (a write grant was given). */
    SaveInPlace,
    /** A new note: Markdown note for Markdown, text note for other text, the existing import for the rest. */
    SaveAsNote,
    /** A copy in the Files library. */
    SaveToFiles,
    /** A copy in the original format, wherever the user picks (Storage Access Framework). */
    SaveCopy,
    ExportPdf,
    ExportHtml,
    ExportDocx,
}

/**
 * Which save options a file opened from another app gets, and the names suggested for the copies. Pure, so the choices
 * are JVM-tested; the screens only draw the list.
 */
object SaveAsOptions {
    private val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "mdown", "mkd")
    private val MARKDOWN_MIME_TYPES = setOf("text/markdown", "text/x-markdown")
    const val FALLBACK_NAME = "Document"

    /** True for a Markdown file: by extension, or by MIME type when the name has no extension. */
    fun isMarkdown(displayName: String?, mimeType: String?): Boolean {
        val ext = FileNames.extensionOf(displayName?.trim().orEmpty())
        if (ext.isNotEmpty()) return ext in MARKDOWN_EXTENSIONS
        return normalisedMime(mimeType) in MARKDOWN_MIME_TYPES
    }

    /**
     * The text editor's save options, in menu order. [writable]: the editor may write the document back;
     * [inLibrary]: the document already is a Files entry, so "Save to Files" would only duplicate it.
     */
    fun forTextDocument(writable: Boolean, inLibrary: Boolean): List<SaveOption> = buildList {
        if (writable) add(SaveOption.SaveInPlace)
        add(SaveOption.SaveAsNote)
        if (!inLibrary) add(SaveOption.SaveToFiles)
        add(SaveOption.SaveCopy)
        add(SaveOption.ExportPdf)
        add(SaveOption.ExportHtml)
        add(SaveOption.ExportDocx)
    }

    /** The share and Open-with sheet's destinations for the received files, in display order. */
    fun forReceived(kinds: List<SaveSourceKind>): List<SaveOption> {
        if (kinds.isEmpty()) return emptyList()
        return buildList {
            add(SaveOption.SaveToFiles)
            if (kinds.none { it == SaveSourceKind.Other }) add(SaveOption.SaveAsNote)
            if (kinds.size == 1) add(SaveOption.SaveCopy)
            val single = kinds.singleOrNull()
            if (kinds.all { it == SaveSourceKind.Image } || single == SaveSourceKind.Markdown || single == SaveSourceKind.Text) {
                add(SaveOption.ExportPdf)
            }
        }
    }

    /**
     * The file name suggested for a copy of [displayName]: sanitised (no path separators or control characters, see
     * [FileNames.sanitize]) and, when [extension] is given, with its extension replaced by it ("a.md" -> "a.pdf").
     * The name is only a suggestion to the system file picker; it is never used as a path.
     */
    fun suggestedName(displayName: String?, extension: String? = null): String {
        val name = FileNames.sanitize(displayName?.trim(), FALLBACK_NAME)
        if (extension.isNullOrEmpty()) return name
        val ext = extension.trim().trimStart('.').lowercase().filter { it.isLetterOrDigit() }
        if (ext.isEmpty()) return name
        val base = FileNames.baseName(name).trim().trim('.').ifEmpty { FALLBACK_NAME }
        return FileNames.sanitize("$base.$ext", "$FALLBACK_NAME.$ext")
    }

    /** The title of a note made from [displayName]: the name without its extension, sanitised; "" when there is none. */
    fun noteTitle(displayName: String?): String {
        val raw = displayName?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        return FileNames.baseName(FileNames.sanitize(raw, "")).trim()
    }

    private fun normalisedMime(mimeType: String?): String =
        mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
}
