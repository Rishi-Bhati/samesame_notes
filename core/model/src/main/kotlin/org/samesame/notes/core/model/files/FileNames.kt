package org.samesame.notes.core.model.files

import org.samesame.notes.core.model.Limits
import java.text.Normalizer

/**
 * Display names of library files and Files folders. A name is only ever a label (blobs are stored by hash, provider
 * URIs carry the id), so sanitising is about safe display and later export, not path safety:
 * - NFC; `/` and `\` become `_`; NUL, other control characters, line/paragraph separators and bidi controls (which
 *   can disguise an extension, e.g. `photo<RLO>gpj.exe`) are dropped;
 * - surrounding whitespace is trimmed; `.`, `..` and empty names fall back to a default;
 * - at most [Limits.MAX_FILE_NAME_BYTES] UTF-8 bytes, cut on a code point boundary and keeping the extension.
 */
object FileNames {
    const val FALLBACK_NAME = "file"

    /** Longest extension that is kept as an extension (longer suffixes are treated as part of the name). */
    private const val MAX_EXT_LENGTH = 16

    /** Sanitises a file name; never returns an empty string. */
    fun sanitize(raw: String?, fallback: String = FALLBACK_NAME): String {
        val cleaned = clean(raw.orEmpty())
        val name = if (cleaned.isEmpty() || cleaned.all { it == '.' }) fallback else cleaned
        return truncateUtf8(name, Limits.MAX_FILE_NAME_BYTES)
    }

    /** Sanitises a Files folder name: the same character rules, at most [Limits.MAX_FILE_FOLDER_NAME_LENGTH] code points. */
    fun sanitizeFolderName(raw: String): String {
        val cleaned = clean(raw)
        val cut = cleaned.take(cleaned.offsetByCodePointsSafe(Limits.MAX_FILE_FOLDER_NAME_LENGTH)).trimEnd()
        require(cut.isNotEmpty() && !cut.all { it == '.' }) { "Folder name is empty" }
        return cut
    }

    /** Lower-case extension of [name] without the dot, or "" (no dot, a leading dot only, or an implausible suffix). */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        val ext = name.substring(dot + 1)
        if (ext.length > MAX_EXT_LENGTH || ext.any { it.isWhitespace() }) return ""
        return ext.lowercase()
    }

    /** [name] without its extension (see [extensionOf]). */
    fun baseName(name: String): String {
        val ext = extensionOf(name)
        return if (ext.isEmpty()) name else name.substring(0, name.length - ext.length - 1)
    }

    /** Adds `.ext` when [name] has no extension, keeping the byte limit. */
    fun withExtension(name: String, ext: String): String =
        if (ext.isEmpty() || extensionOf(name).isNotEmpty()) name else sanitize("$name.$ext")

    /**
     * [name], or `base (2).ext`, `base (3).ext`, ... : the first that is not in [taken] (compared ignoring case).
     */
    fun unique(name: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        val ext = extensionOf(name)
        val base = baseName(name)
        var n = 2
        while (true) {
            val suffix = " ($n)" + if (ext.isEmpty()) "" else ".$ext"
            val candidate = truncateBase(base, suffix)
            if (candidate.lowercase() !in lower) return candidate
            n++
        }
    }

    private fun clean(raw: String): String {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val out = StringBuilder(nfc.length)
        var i = 0
        while (i < nfc.length) {
            val cp = nfc.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '/'.code || cp == '\\'.code -> out.append('_')
                isDropped(cp) -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString().trim()
    }

    private fun isDropped(cp: Int): Boolean {
        when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SURROGATE.toInt() -> return true
        }
        return cp == 0x061C || cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069
    }

    /** Cuts [name] to [maxBytes] UTF-8 bytes, shortening the base name and keeping a short extension. */
    private fun truncateUtf8(name: String, maxBytes: Int): String {
        if (utf8Length(name) <= maxBytes) return name
        val ext = extensionOf(name)
        val keepExt = ext.isNotEmpty() && utf8Length(ext) + 2 <= maxBytes / 2
        return if (keepExt) {
            truncateBase(baseName(name), ".$ext", maxBytes)
        } else {
            cutToBytes(name, maxBytes).trimEnd().ifEmpty { FALLBACK_NAME }
        }
    }

    private fun truncateBase(base: String, suffix: String, maxBytes: Int = Limits.MAX_FILE_NAME_BYTES): String {
        val room = maxBytes - utf8Length(suffix)
        val cut = cutToBytes(base, room).trimEnd().ifEmpty { FALLBACK_NAME }
        return cut + suffix
    }

    /** The longest prefix of [s] (whole code points) that fits in [maxBytes] UTF-8 bytes. */
    private fun cutToBytes(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = utf8Length(cp)
            if (bytes + len > maxBytes) break
            bytes += len
            i += Character.charCount(cp)
        }
        return s.substring(0, i)
    }

    private fun String.offsetByCodePointsSafe(count: Int): Int =
        if (codePointCount(0, length) <= count) length else offsetByCodePoints(0, count)

    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            n += utf8Length(cp)
            i += Character.charCount(cp)
        }
        return n
    }

    private fun utf8Length(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }
}
