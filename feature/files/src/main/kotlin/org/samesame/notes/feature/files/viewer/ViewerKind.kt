package org.samesame.notes.feature.files.viewer

import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.LibraryFile

/** Which in-app viewer shows a library file. [External] goes to the system "open with" intent. */
enum class ViewerKind {
    Image, Pdf, Text, Audio, External;

    companion object {
        /** Text-like extensions whose MIME type isn't `text/…` (the sniffer files some of them under Other). */
        private val TEXT_EXTS = setOf(
            "txt", "text", "log", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "kt", "kts",
            "java", "py", "c", "h", "cpp", "hpp", "rs", "go", "rb", "php", "sh", "bat", "yaml", "yml", "toml", "ini", "cfg",
            "conf", "properties", "gradle", "sql", "tex", "srt", "vcf", "ics",
        )
        private val TEXT_MIMES = setOf(
            "application/json", "application/xml", "application/yaml", "application/toml", "application/x-sh",
            "application/x-tex", "application/x-subrip", "application/javascript",
        )

        fun of(file: LibraryFile): ViewerKind {
            val mime = file.mimeType.lowercase()
            return when {
                file.category == FileCategory.Image -> if (file.isRenderableImage) Image else External
                file.category == FileCategory.Pdf -> Pdf
                file.category == FileCategory.Audio -> Audio
                file.category == FileCategory.Video || file.category == FileCategory.Archive -> External
                file.category == FileCategory.Text || file.category == FileCategory.Markdown -> Text
                mime.startsWith("text/") || mime in TEXT_MIMES -> Text
                // Rich text is a document format, not plain text.
                file.category == FileCategory.Document -> External
                file.ext.lowercase() in TEXT_EXTS -> Text
                else -> External
            }
        }
    }
}
