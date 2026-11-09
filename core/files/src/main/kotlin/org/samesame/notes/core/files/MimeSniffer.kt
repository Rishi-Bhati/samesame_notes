package org.samesame.notes.core.files

import org.samesame.notes.core.model.files.FileCategory
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Picks a file's MIME type and [FileCategory] from its first bytes (magic numbers) and its extension.
 * The content wins over the extension when it identifies a specific format (a `.jpg` that is really a PNG is
 * `image/png`); container formats (ZIP, OLE, RIFF) are refined by the extension (`.docx`, `.apk`, `.ssnote`, ...).
 * A declared MIME from the sender is only used when neither the content nor the extension says anything.
 */
object MimeSniffer {
    const val OCTET_STREAM = "application/octet-stream"

    data class Result(
        val mimeType: String,
        val category: FileCategory,
        /** Extension to add when the name has none ("" when unknown). */
        val suggestedExt: String,
    )

    fun sniff(head: ByteArray, ext: String, declaredMime: String? = null): Result {
        val e = ext.lowercase()
        val byExt = EXT_TO_MIME[e]
        val byMagic = magic(head, e)
        val declared = declaredMime?.lowercase()?.substringBefore(';')?.trim()
            ?.takeIf { it.isNotEmpty() && it != OCTET_STREAM && it != "*/*" && '/' in it }
        val mime = byMagic ?: byExt ?: declared ?: if (looksLikeText(head)) "text/plain" else OCTET_STREAM
        return Result(mime, categoryOf(mime, e), MIME_TO_EXT[mime].orEmpty())
    }

    fun categoryOf(mime: String, ext: String = ""): FileCategory {
        val m = mime.lowercase()
        val e = ext.lowercase()
        return when {
            m == "text/markdown" || e == "md" || e == "markdown" -> FileCategory.Markdown
            m.startsWith("image/") -> FileCategory.Image
            m == "application/pdf" -> FileCategory.Pdf
            m.startsWith("audio/") -> FileCategory.Audio
            m.startsWith("video/") -> FileCategory.Video
            m in DOCUMENT_MIMES -> FileCategory.Document
            m in ARCHIVE_MIMES -> FileCategory.Archive
            m.startsWith("text/") || m in TEXT_MIMES -> FileCategory.Text
            else -> FileCategory.Other
        }
    }

    private fun magic(h: ByteArray, ext: String): String? {
        fun at(offset: Int, vararg bytes: Int) =
            h.size >= offset + bytes.size && bytes.indices.all { (h[offset + it].toInt() and 0xFF) == bytes[it] }
        fun ascii(offset: Int, s: String) = at(offset, *s.map { it.code }.toIntArray())

        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "video/x-msvideo"
            ascii(0, "BM") && (ext == "bmp" || ext.isEmpty()) && h.size >= 14 -> "image/bmp"
            at(0, 0x49, 0x49, 0x2A, 0x00) || at(0, 0x4D, 0x4D, 0x00, 0x2A) -> "image/tiff"
            ascii(0, "%PDF-") -> "application/pdf"
            ascii(4, "ftyp") -> isoBrand(String(h, 8, minOf(4, (h.size - 8).coerceAtLeast(0)), Charsets.ISO_8859_1))
            ascii(0, "ID3") -> "audio/mpeg"
            at(0, 0xFF) && h.size > 1 && (h[1].toInt() and 0xE0) == 0xE0 && ext in setOf("mp3", "") -> "audio/mpeg"
            ascii(0, "OggS") -> if (ext == "ogv") "video/ogg" else if (ext == "opus") "audio/opus" else "audio/ogg"
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "#!AMR") -> "audio/amr"
            ascii(0, "MThd") -> "audio/midi"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> if (ext == "webm") "video/webm" else "video/x-matroska"
            at(0, 0x50, 0x4B, 0x03, 0x04) || at(0, 0x50, 0x4B, 0x05, 0x06) ->
                EXT_TO_MIME[ext]?.takeIf { ext in ZIP_BASED_EXTS } ?: "application/zip"
            at(0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1) ->
                EXT_TO_MIME[ext]?.takeIf { ext in OLE_BASED_EXTS } ?: "application/x-ole-storage"
            at(0, 0x1F, 0x8B) -> "application/gzip"
            at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "application/x-7z-compressed"
            ascii(0, "Rar!\u001A\u0007") -> "application/vnd.rar"
            ascii(0, "BZh") && ext in setOf("bz2", "tbz", "") -> "application/x-bzip2"
            at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> "application/x-xz"
            ascii(0, "{\\rtf") -> "application/rtf"
            ascii(0, "SQLite format 3\u0000") -> "application/vnd.sqlite3"
            else -> null
        }
    }

    private fun isoBrand(brand: String): String = when (brand) {
        "heic", "heix", "hevc", "hevx", "heim", "heis" -> "image/heic"
        "mif1", "msf1" -> "image/heif"
        "avif", "avis" -> "image/avif"
        "M4A ", "M4B ", "M4P " -> "audio/mp4"
        "qt  " -> "video/quicktime"
        else -> if (brand.startsWith("3g")) "video/3gpp" else "video/mp4"
    }

    /** No NUL bytes and valid UTF-8 (a multi-byte character cut at the end of [head] is fine). */
    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        if (head.any { it.toInt() == 0 }) return false
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        // Drop up to three trailing bytes that may be a truncated character.
        for (cut in 0..minOf(3, head.size - 1)) {
            try {
                decoder.reset().decode(ByteBuffer.wrap(head, 0, head.size - cut))
                return true
            } catch (_: CharacterCodingException) {
            }
        }
        return false
    }

    private val ZIP_BASED_EXTS = setOf(
        "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "apk", "jar", "ssnote", "sdocx", "kmz", "xpi", "aab",
    )
    private val OLE_BASED_EXTS = setOf("doc", "xls", "ppt", "msg", "msi")

    private val EXT_TO_MIME: Map<String, String> = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "tif" to "image/tiff", "tiff" to "image/tiff", "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "dng" to "image/x-adobe-dng",
        "pdf" to "application/pdf",
        "txt" to "text/plain", "text" to "text/plain", "log" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "xml" to "text/xml", "json" to "application/json", "js" to "text/javascript",
        "kt" to "text/x-kotlin", "java" to "text/x-java", "py" to "text/x-python", "c" to "text/x-c", "h" to "text/x-c",
        "cpp" to "text/x-c++", "sh" to "application/x-sh", "yaml" to "application/yaml", "yml" to "application/yaml",
        "toml" to "application/toml", "ini" to "text/plain", "tex" to "application/x-tex", "rtf" to "application/rtf",
        "srt" to "application/x-subrip", "vcf" to "text/vcard", "ics" to "text/calendar",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "oga" to "audio/ogg", "opus" to "audio/opus", "flac" to "audio/flac", "amr" to "audio/amr", "mid" to "audio/midi",
        "midi" to "audio/midi", "3ga" to "audio/3gpp",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "webm" to "video/webm", "avi" to "video/x-msvideo", "3gp" to "video/3gpp", "ogv" to "video/ogg",
        "zip" to "application/zip", "gz" to "application/gzip", "tgz" to "application/gzip", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar", "tar" to "application/x-tar", "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        "doc" to "application/msword", "xls" to "application/vnd.ms-excel", "ppt" to "application/vnd.ms-powerpoint",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text", "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation", "epub" to "application/epub+zip",
        "ssnote" to "application/x-ssnote", "sdocx" to "application/x-sdocx",
        "apk" to "application/vnd.android.package-archive", "jar" to "application/java-archive",
        "exe" to "application/vnd.microsoft.portable-executable", "msi" to "application/x-msi",
        "sqlite" to "application/vnd.sqlite3", "db" to "application/vnd.sqlite3",
    )

    private val MIME_TO_EXT: Map<String, String> = buildMap {
        EXT_TO_MIME.forEach { (ext, mime) -> putIfAbsent(mime, ext) }
        put("application/x-ole-storage", "")
        put("text/plain", "txt")
    }

    private val DOCUMENT_MIMES = setOf(
        "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation", "application/epub+zip", "application/rtf",
        "application/x-ssnote", "application/x-sdocx", "application/x-tex",
    )

    private val ARCHIVE_MIMES = setOf(
        "application/zip", "application/gzip", "application/x-7z-compressed", "application/vnd.rar", "application/x-tar",
        "application/x-bzip2", "application/x-xz",
    )

    private val TEXT_MIMES = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-sh", "application/yaml",
        "application/toml", "application/x-subrip",
    )
}
