package org.samesame.notes.core.model.richtext

import org.samesame.notes.core.model.files.FileLinks

/**
 * Which link targets may be opened or written out. Link targets come from untrusted text (shared files, imported notes),
 * so a tap or an export must never turn `javascript:`, `file:`, `content:`, `intent:` or an app's private scheme into
 * something that runs or opens.
 */
object LinkSchemes {
    /** What a tap on a link may hand to another app (after the user sees it). */
    val OPENABLE: Set<String> = setOf("http", "https", "mailto")

    /** What an exported document (HTML, Word) may keep as a live link. */
    val EXPORTABLE: Set<String> = setOf("http", "https", "mailto", "tel", "sms", "geo", FileLinks.SCHEME)

    /**
     * The lower-case scheme of [url] the way a browser reads it (leading and trailing spaces and control characters
     * dropped, tabs and line breaks removed anywhere), "" for a relative reference, or null for an empty target.
     */
    fun schemeOf(url: String): String? {
        val cleaned = url.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        if (cleaned.isEmpty()) return null
        val colon = cleaned.indexOf(':')
        if (colon <= 0) return ""
        val candidate = cleaned.substring(0, colon)
        // Not a scheme (a '/', '?', '#' or any other character first): a browser resolves it as a relative path.
        if (!candidate[0].isAsciiLetter() || !candidate.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '+' || it == '-' || it == '.' }) return ""
        return candidate.lowercase()
    }

    /** True when a tap may open [url]: an absolute http(s) or mailto link. */
    fun isOpenable(url: String): Boolean = schemeOf(url)?.let { it in OPENABLE } == true

    /** True when an export may write [url] as a link: an allowed scheme, or a relative reference when [allowRelative]. */
    fun isExportable(url: String, allowRelative: Boolean = true): Boolean {
        val scheme = schemeOf(url) ?: return false
        return if (scheme.isEmpty()) allowRelative else scheme in EXPORTABLE
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiDigit() = this in '0'..'9'
}
