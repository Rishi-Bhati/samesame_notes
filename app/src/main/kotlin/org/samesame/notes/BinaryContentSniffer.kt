package org.samesame.notes

/**
 * Tells text from binary by looking at the first bytes of a file, so the text editor never shows raw bytes of a PDF
 * or a picture. Pure functions; reading the bytes is the caller's job.
 */
object BinaryContentSniffer {

    /** How many bytes the caller should read for [sniff]. */
    const val SNIFF_BYTES = 8192

    const val GENERIC_BINARY = "application/octet-stream"

    /** Needs at least this many non-ASCII bytes before the invalid-UTF-8 ratio counts (short Latin-1 text stays text). */
    private const val MIN_HIGH_BYTES = 8
    private const val MAX_INVALID_RATIO = 0.3
    private const val MAX_CONTROL_RATIO = 0.1

    /**
     * Null when the first [length] bytes of [bytes] look like text; otherwise a MIME type for the binary kind
     * (`application/pdf`, `image/png`, ..., else [GENERIC_BINARY]).
     */
    fun sniff(bytes: ByteArray, length: Int = bytes.size): String? {
        val n = minOf(length, bytes.size)
        if (n == 0) return null
        magicType(bytes, n)?.let { return it }
        if (hasTextBom(bytes, n)) return null
        var controls = 0
        var high = 0
        for (i in 0 until n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return GENERIC_BINARY
            if (b < 0x20 && b != '\t'.code && b != '\n'.code && b != '\r'.code && b != 0x0C && b != 0x1B && b != 0x08) controls++
            if (b >= 0x80) high++
        }
        if (controls.toDouble() / n > MAX_CONTROL_RATIO) return GENERIC_BINARY
        if (high >= MIN_HIGH_BYTES && invalidUtf8Bytes(bytes, n).toDouble() / n > MAX_INVALID_RATIO) return GENERIC_BINARY
        return null
    }

    fun isBinary(bytes: ByteArray, length: Int = bytes.size): Boolean = sniff(bytes, length) != null

    private fun hasTextBom(b: ByteArray, n: Int): Boolean =
        (n >= 3 && b.at(0) == 0xEF && b.at(1) == 0xBB && b.at(2) == 0xBF) ||
            (n >= 2 && b.at(0) == 0xFF && b.at(1) == 0xFE) ||
            (n >= 2 && b.at(0) == 0xFE && b.at(1) == 0xFF)

    private fun magicType(b: ByteArray, n: Int): String? = when {
        startsWith(b, n, "%PDF-") -> "application/pdf"
        n >= 8 && b.at(0) == 0x89 && b.at(1) == 'P'.code && b.at(2) == 'N'.code && b.at(3) == 'G'.code -> "image/png"
        n >= 3 && b.at(0) == 0xFF && b.at(1) == 0xD8 && b.at(2) == 0xFF -> "image/jpeg"
        startsWith(b, n, "GIF87a") || startsWith(b, n, "GIF89a") -> "image/gif"
        n >= 4 && b.at(0) == 'P'.code && b.at(1) == 'K'.code && b.at(2) == 3 && b.at(3) == 4 -> "application/zip"
        n >= 12 && startsWith(b, n, "RIFF") && b.at(8) == 'W'.code && b.at(9) == 'E'.code && b.at(10) == 'B'.code && b.at(11) == 'P'.code -> "image/webp"
        n >= 4 && b.at(0) == 0x7F && b.at(1) == 'E'.code && b.at(2) == 'L'.code && b.at(3) == 'F'.code -> GENERIC_BINARY
        n >= 2 && b.at(0) == 0x1F && b.at(1) == 0x8B -> "application/gzip"
        else -> null
    }

    private fun ByteArray.at(i: Int): Int = this[i].toInt() and 0xFF

    private fun startsWith(b: ByteArray, n: Int, prefix: String): Boolean =
        n >= prefix.length && prefix.indices.all { b.at(it) == prefix[it].code }

    /** Bytes that are not part of a well-formed UTF-8 sequence; a sequence cut off by the end of the sample is not counted. */
    private fun invalidUtf8Bytes(b: ByteArray, n: Int): Int {
        var invalid = 0
        var i = 0
        while (i < n) {
            val c = b.at(i)
            val need = when {
                c < 0x80 -> 0
                c in 0xC2..0xDF -> 1
                c in 0xE0..0xEF -> 2
                c in 0xF0..0xF4 -> 3
                else -> -1
            }
            if (need == 0) { i++; continue }
            if (need < 0) { invalid++; i++; continue }
            if (i + need >= n) {
                // Cut off by the end of the sample: fine when every byte present is a continuation byte.
                val rest = (i + 1 until n).all { b.at(it) and 0xC0 == 0x80 }
                if (rest) i = n else { invalid++; i++ }
                continue
            }
            var ok = true
            for (k in 1..need) if (b.at(i + k) and 0xC0 != 0x80) { ok = false; break }
            if (ok) i += need + 1 else { invalid++; i++ }
        }
        return invalid
    }
}
