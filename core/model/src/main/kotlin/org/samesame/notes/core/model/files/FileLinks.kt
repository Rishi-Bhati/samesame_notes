package org.samesame.notes.core.model.files

import java.io.ByteArrayOutputStream

/**
 * Links to library files: `samesame://file/<fileId>?n=<percent-encoded display name>`.
 * Only the id resolves the file; `n` is a hint for missing files and exports and is never used for lookup.
 */
object FileLinks {
    const val SCHEME = "samesame"
    const val HOST = "file"
    const val PREFIX = "$SCHEME://$HOST/"

    /** A parsed link. [nameHint] is null when the link carries no (or a malformed) `n`. */
    data class Target(val id: FileId, val nameHint: String?)

    /** Matches one link inside text (for link detection); group 1 is the id. */
    val PATTERN = Regex(
        "(?i:samesame)://file/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})" +
            "(?:\\?[^\\s#]*)?(?:#\\S*)?",
    )

    fun format(id: FileId, displayName: String): String = "$PREFIX${id.value}?n=${percentEncode(displayName)}"

    fun format(file: LibraryFile): String = format(file.id, file.displayName)

    /** Parses one whole link (surrounding whitespace allowed), or null. */
    fun parse(text: String): Target? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        return targetOf(match)
    }

    /**
     * The links when [text] consists only of one or more links separated by whitespace or newlines (the paste
     * interception rule), otherwise null.
     */
    fun parseLinksOnly(text: String): List<Target>? {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { parse(it) ?: return null }
    }

    private fun targetOf(match: MatchResult): Target {
        val id = FileId(match.groupValues[1].lowercase())
        val query = match.value.substringAfter('?', "").substringBefore('#')
        val n = query.split('&').firstOrNull { it.startsWith("n=") }?.substring(2)
        return Target(id, n?.let(::percentDecode))
    }

    /** RFC 3986 percent-encoding of UTF-8 bytes; only unreserved characters stay literal. */
    fun percentEncode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c.toChar().isUnreserved()) sb.append(c.toChar()) else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
        return sb.toString()
    }

    /** Decodes [percentEncode] output (and `+` as a space); null when malformed. */
    fun percentDecode(s: String): String? {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when (c) {
                '%' -> {
                    if (i + 2 >= s.length) return null
                    val hi = Character.digit(s[i + 1], 16)
                    val lo = Character.digit(s[i + 2], 16)
                    if (hi < 0 || lo < 0) return null
                    out.write(hi * 16 + lo)
                    i += 3
                }
                '+' -> { out.write(' '.code); i++ }
                else -> {
                    val cp = s.codePointAt(i)
                    out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(cp)
                }
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun Char.isUnreserved() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '-' ||
        this == '.' || this == '_' || this == '~'

    private const val HEX = "0123456789ABCDEF"
}
