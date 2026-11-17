package org.samesame.notes.core.model.files

import org.samesame.notes.core.model.Limits
import java.text.Normalizer

/**
 * Tags and the short description of a library file. Tags are normalised so that "Receipts", " receipts " and "RECEIPTS"
 * are the same tag: NFC, trimmed, inner whitespace collapsed, lower case, without a leading '#'. The stored value is the
 * normalised one; the Files UI shows it as stored.
 */
object FileTags {
    /** The normalised form of [raw], or null for a blank tag. At most [Limits.MAX_FILE_TAG_LENGTH] code points. */
    fun normalize(raw: String): String? {
        val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC).trim().trimStart('#').trim()
        if (nfc.isEmpty()) return null
        val collapsed = nfc.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return null
        return collapsed.lowercase().takeCodePoints(Limits.MAX_FILE_TAG_LENGTH).trim()
    }

    /** [raw] as normalised, distinct tags, in first-seen order, at most [Limits.MAX_FILE_TAGS]. */
    fun normalizeAll(raw: Collection<String>): List<String> =
        raw.mapNotNull(::normalize).distinct().take(Limits.MAX_FILE_TAGS)

    /** Trims [raw] and keeps at most [Limits.MAX_FILE_DESCRIPTION_LENGTH] code points. */
    fun normalizeDescription(raw: String): String =
        raw.trim().takeCodePoints(Limits.MAX_FILE_DESCRIPTION_LENGTH).trimEnd()

    private fun String.takeCodePoints(max: Int): String {
        if (codePointCount(0, length) <= max) return this
        return substring(0, offsetByCodePoints(0, max))
    }

    private val WHITESPACE = Regex("\\s+")
}
