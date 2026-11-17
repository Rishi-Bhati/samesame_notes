package org.samesame.notes.core.model.textimport

import org.samesame.notes.core.model.Limits
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Reading a plain text file (txt, md, csv, ...) as the body of a new note. Pure, so it is JVM-tested. */
object TextImport {
    val extensions: Set<String> = setOf("txt", "text", "md", "markdown", "log", "csv", "tsv")
    val mimeTypes: Set<String> = setOf(
        "text/plain", "text/markdown", "text/x-markdown", "text/csv", "text/tab-separated-values",
    )

    /** True when the file name's extension, or (without an extension) the MIME type, says plain text. */
    fun isTextFile(displayName: String?, mimeType: String?): Boolean {
        val ext = extensionOf(displayName)
        if (ext != null) return ext in extensions
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        return mime != null && mime in mimeTypes
    }

    /** The file name without its text extension; other dots stay ("a.b.txt" -> "a.b"). Blank stays blank. */
    fun titleOf(displayName: String?): String {
        val name = displayName?.trim().orEmpty()
        val ext = extensionOf(name) ?: return name
        return name.substring(0, name.length - ext.length - 1).trim()
    }

    /** Decodes [bytes]: BOM (UTF-8, UTF-16 LE/BE) first, else UTF-8 with replacement characters; line breaks become LF. */
    fun decode(bytes: ByteArray): String {
        val (charset, skip) = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        return normaliseLineBreaks(decodeLenient(charset, bytes, skip))
    }

    fun normaliseLineBreaks(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** True when [size] bytes exceed [Limits.MAX_TEXT_IMPORT_BYTES]. */
    fun isTooLarge(size: Long): Boolean = size > Limits.MAX_TEXT_IMPORT_BYTES

    private fun decodeLenient(charset: Charset, bytes: ByteArray, skip: Int): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, skip, bytes.size - skip, Charsets.UTF_8)
        }
    }

    private fun extensionOf(name: String?): String? {
        val n = name?.trim() ?: return null
        val dot = n.lastIndexOf('.')
        if (dot < 0 || dot == n.lastIndex) return null
        return n.substring(dot + 1).lowercase()
    }
}
