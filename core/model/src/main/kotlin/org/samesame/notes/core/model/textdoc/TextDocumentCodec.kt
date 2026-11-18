package org.samesame.notes.core.model.textdoc

import org.samesame.notes.core.model.Limits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reads and writes plain text files for the text document editor.
 *
 * Reading detects a byte-order mark, otherwise tries strict UTF-8 and falls back to Windows-1252 (or ISO-8859-1 when
 * the bytes are identical in both). The dominant line ending is remembered so a save keeps the file's style. Text is
 * held internally with `\n`; [write] puts the original line ending back.
 */
object TextDocumentCodec {

    /**
     * Reads at most [maxBytes] from [input] (the stream is not closed). A longer file is cut at the cap and returned
     * read-only with `isTruncated = true`: the editor must never save a half-read file over the whole one.
     */
    fun read(input: InputStream, maxBytes: Long = Limits.MAX_TEXTDOC_EDIT_BYTES): TextDocument {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        val cap = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong() - 1L).toInt()
        val buffer = ByteArray(cap + 1)
        var size = 0
        while (size < buffer.size) {
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
        }
        val truncated = size > cap
        val length = minOf(size, cap)
        val bom = bomAt(buffer, length)
        val decoded = if (bom != null) {
            Decoded(decodeBom(buffer, bom, length), bom.encoding)
        } else {
            decodeWithoutBom(buffer, length, truncated)
        }
        return TextDocument(
            text = normalizeNewlines(decoded.text),
            encoding = decoded.encoding,
            lineEnding = detectLineEnding(decoded.text),
            hasBom = bom != null,
            isReadOnly = truncated,
            isTruncated = truncated,
        )
    }

    /** Writes [document] with its own line endings, byte-order mark and encoding. */
    fun write(document: TextDocument, output: OutputStream) {
        val text = normalizeNewlines(document.text).replace("\n", document.lineEnding.token)
        if (document.hasBom) {
            when (document.encoding) {
                TextEncoding.Utf8 -> output.write(UTF8_BOM)
                TextEncoding.Utf16Le -> output.write(UTF16LE_BOM)
                TextEncoding.Utf16Be -> output.write(UTF16BE_BOM)
                // Single-byte encodings have no byte-order mark; ignore the flag rather than write a broken file.
                TextEncoding.Windows1252, TextEncoding.Iso88591 -> Unit
            }
        }
        output.write(text.toByteArray(document.encoding.charset))
        output.flush()
    }

    /** The bytes [write] would produce, for tests and for saving through a stream-only API. */
    fun roundTripBytes(text: String, encoding: TextEncoding, lineEnding: LineEnding, hasBom: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        write(TextDocument(text, encoding, lineEnding, hasBom), out)
        return out.toByteArray()
    }

    private class Bom(val length: Int, val encoding: TextEncoding)

    private class Decoded(val text: String, val encoding: TextEncoding)

    private fun bomAt(bytes: ByteArray, length: Int): Bom? {
        fun at(i: Int) = if (i < length) bytes[i].toInt() and 0xFF else -1
        return when {
            at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF -> Bom(3, TextEncoding.Utf8)
            at(0) == 0xFF && at(1) == 0xFE -> Bom(2, TextEncoding.Utf16Le)
            at(0) == 0xFE && at(1) == 0xFF -> Bom(2, TextEncoding.Utf16Be)
            else -> null
        }
    }

    private fun decodeBom(bytes: ByteArray, bom: Bom, length: Int): String {
        var body = (length - bom.length).coerceAtLeast(0)
        // A truncated UTF-16 file can end inside a code unit; drop the dangling byte.
        if (bom.encoding == TextEncoding.Utf16Le || bom.encoding == TextEncoding.Utf16Be) body -= body % 2
        return String(bytes, bom.length, body, bom.encoding.charset)
    }

    /**
     * Without a byte-order mark the bytes must be UTF-8. A truncated file can end inside a character, so up to three
     * trailing bytes are dropped before giving up. Invalid UTF-8 is treated as an ANSI text file.
     */
    private fun decodeWithoutBom(bytes: ByteArray, length: Int, truncated: Boolean): Decoded {
        for (drop in 0..(if (truncated) 3 else 0)) {
            val end = length - drop
            if (end < 0) break
            strictUtf8(bytes, end)?.let { return Decoded(it, TextEncoding.Utf8) }
        }
        val encoding = fallbackEncoding(bytes, length)
        return Decoded(String(bytes, 0, length, encoding.charset), encoding)
    }

    /** Windows-1252 for the C1 range (0x80..0x9F), which it maps to punctuation; ISO-8859-1 otherwise. */
    private fun fallbackEncoding(bytes: ByteArray, length: Int): TextEncoding =
        if ((0 until length).any { (bytes[it].toInt() and 0xFF) in 0x80..0x9F }) TextEncoding.Windows1252 else TextEncoding.Iso88591

    private fun strictUtf8(bytes: ByteArray, length: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun detectLineEnding(text: String): LineEnding {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i++ } else cr++
                '\n' -> lf++
            }
            i++
        }
        return when {
            crlf > 0 && crlf >= lf && crlf >= cr -> LineEnding.Crlf
            cr > 0 && cr >= lf -> LineEnding.Cr
            else -> LineEnding.Lf
        }
    }

    private fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
