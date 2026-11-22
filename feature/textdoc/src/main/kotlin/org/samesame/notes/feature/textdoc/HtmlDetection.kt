package org.samesame.notes.feature.textdoc

/** HTML file and MIME type detection: only these get the "Rendered" view. */
internal object HtmlDetection {
    private val HTML_EXTENSIONS = listOf(".html", ".htm", ".xhtml")
    private val HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")

    fun isHtml(displayName: String?, mimeType: String? = null): Boolean {
        if (mimeType != null && mimeType.substringBefore(';').trim().lowercase() in HTML_MIME_TYPES) return true
        val lower = displayName?.trim()?.lowercase() ?: return false
        return HTML_EXTENSIONS.any { lower.endsWith(it) }
    }
}
