package org.samesame.notes.feature.files.viewer

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What the text viewer shows: the file's lines, and whether the file was longer than the viewer reads. */
internal data class TextLoad(val lines: List<String>, val truncated: Boolean)

/** A search hit: [length] characters from [start] in line [line]. */
internal data class TextMatch(val line: Int, val start: Int, val length: Int)

/** Reading, decoding and searching of the read-only text viewer (pure, JVM-tested). */
internal object TextContent {
    /** The viewer reads at most this much of a file. */
    const val MAX_BYTES = 2 * 1024 * 1024
    /** Longer lines (minified files) are cut into rows of this length so one row never holds megabytes. */
    const val MAX_LINE_CHARS = 2000
    const val MAX_MATCHES = 5000

    fun read(input: InputStream, maxBytes: Int = MAX_BYTES): TextLoad {
        val buffer = ByteArray(maxBytes + 1)
        var size = 0
        while (size < buffer.size) {
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
        }
        val truncated = size > maxBytes
        val length = minOf(size, maxBytes)
        return TextLoad(lines(decode(buffer, length, truncated)), truncated)
    }

    /**
     * Byte-order mark first (UTF-8, UTF-16); then strict UTF-8; then Windows-1252, which decodes anything. A cut-off
     * file may end inside a UTF-8 character, so up to three trailing bytes are dropped before giving up on UTF-8.
     */
    fun decode(bytes: ByteArray, length: Int = bytes.size, truncated: Boolean = false): String {
        fun at(i: Int) = if (i < length) bytes[i].toInt() and 0xFF else -1
        if (at(0) == 0xEF && at(1) == 0xBB && at(2) == 0xBF) return lenient(bytes, 3, length - 3, Charsets.UTF_8)
        if (at(0) == 0xFF && at(1) == 0xFE) return lenient(bytes, 2, length - 2, Charsets.UTF_16LE)
        if (at(0) == 0xFE && at(1) == 0xFF) return lenient(bytes, 2, length - 2, Charsets.UTF_16BE)
        for (drop in 0..(if (truncated) 3 else 0)) {
            if (length - drop < 0) break
            strictUtf8(bytes, length - drop)?.let { return it }
        }
        val fallback = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
        return lenient(bytes, 0, length, fallback)
    }

    private fun strictUtf8(bytes: ByteArray, length: Int): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, length)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun lenient(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String = String(bytes, offset, length.coerceAtLeast(0), charset)

    /** Splits on LF, CRLF and CR; NUL characters are dropped; over-long lines are cut into rows. */
    fun lines(text: String, maxLine: Int = MAX_LINE_CHARS): List<String> {
        val out = ArrayList<String>()
        text.replace("\u0000", "").splitToSequence("\r\n", "\n", "\r").forEach { line ->
            if (line.length <= maxLine) out += line
            else {
                var i = 0
                while (i < line.length) {
                    var end = minOf(i + maxLine, line.length)
                    // Never split a surrogate pair.
                    if (end < line.length && Character.isHighSurrogate(line[end - 1])) end--
                    out += line.substring(i, end)
                    i = end
                }
            }
        }
        // A final newline doesn't make an extra empty row.
        if (out.size > 1 && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    /** Case-insensitive hits of [query] in reading order, at most [limit]. */
    fun find(lines: List<String>, query: String, limit: Int = MAX_MATCHES): List<TextMatch> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<TextMatch>()
        for ((index, line) in lines.withIndex()) {
            var from = 0
            while (true) {
                val at = line.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                out += TextMatch(index, at, query.length)
                if (out.size >= limit) return out
                from = at + query.length
            }
        }
        return out
    }

    /** The match after ([forward]) or before [current], wrapping around; -1 when there are none. */
    fun step(current: Int, count: Int, forward: Boolean): Int = when {
        count <= 0 -> -1
        current !in 0 until count -> if (forward) 0 else count - 1
        forward -> (current + 1) % count
        else -> (current - 1 + count) % count
    }
}
