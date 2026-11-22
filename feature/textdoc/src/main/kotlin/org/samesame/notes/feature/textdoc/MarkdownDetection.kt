package org.samesame.notes.feature.textdoc

/** Markdown file and MIME type detection. */
internal object MarkdownDetection {
    private val MARKDOWN_EXTENSIONS = listOf(".md", ".markdown", ".mdown")
    private val MARKDOWN_MIME_TYPES = setOf("text/markdown", "text/x-markdown", "text/x-web-markdown")

    fun isMarkdown(displayName: String?, mimeType: String? = null): Boolean {
        if (mimeType != null && mimeType.substringBefore(';').trim().lowercase() in MARKDOWN_MIME_TYPES) {
            return true
        }
        if (displayName != null) {
            val lower = displayName.trim().lowercase()
            return MARKDOWN_EXTENSIONS.any { lower.endsWith(it) }
        }
        return false
    }
}
