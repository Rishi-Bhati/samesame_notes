package org.samesame.notes.core.richtext.edit

import org.samesame.notes.core.model.richtext.ListKind

/** A list marker typed at the start of a line, ready to be consumed. */
data class BlockMarker(val marker: String, val list: ListKind, val checked: Boolean, val checkbox: Boolean = false)

/** Inline `*bold*`-style marker pair; [start, end) is the styled content in the line text. */
data class InlineMarker(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind { Bold, Italic, Strikethrough }
}

/** A typed ASCII arrow that should become one arrow character: replace [length] chars before the caret with [arrow]. */
data class ArrowReplacement(val length: Int, val arrow: String, val typed: String)

/**
 * Pure recognition of the WhatsApp/Markdown-style typing shortcuts, kept out of [BodyText] so all the
 * string edge cases can be tested on the JVM.
 */
object TypingShortcuts {
    private val block = Regex("""^(?:[-*\u2022] |\d{1,3}[.)] |\[\] |\[ ] |\[[xX]] )$""")

    /** The list marker when [prefix] (line start up to the caret) is exactly a consumed marker, else null. */
    fun blockMarker(prefix: String): BlockMarker? {
        if (!block.matches(prefix)) return null
        // A checkbox marker only sets the checkbox; a number or bullet only sets the list part (they combine on one paragraph).
        val checkbox = prefix.startsWith("[")
        val list = when {
            checkbox -> ListKind.None
            prefix.first().isDigit() -> ListKind.Numbered
            else -> ListKind.Bullet
        }
        val checked = prefix.startsWith("[x") || prefix.startsWith("[X")
        return BlockMarker(prefix, list, checked, checkbox)
    }

    /**
     * Inline marker that ends right before the typed space: `*bold*`, `_italic_`, `~strike~`.
     * [textBeforeCaret] is the line text up to (not including) the space, so the closing marker is
     * its last character. Returns null for `snake_case_names`, `2*3*4`, URLs and empty spans.
     */
    fun inline(textBeforeCaret: String): InlineMarker? {
        if (textBeforeCaret.length < 3) return null
        val closeIndex = textBeforeCaret.length - 1
        val close = textBeforeCaret[closeIndex]
        val kind = when (close) {
            '*' -> InlineMarker.Kind.Bold
            '_' -> InlineMarker.Kind.Italic
            '~' -> InlineMarker.Kind.Strikethrough
            else -> return null
        }
        val openIndex = textBeforeCaret.lastIndexOf(close, closeIndex - 1)
        if (openIndex < 0 || openIndex + 1 >= closeIndex) return null
        val content = textBeforeCaret.substring(openIndex + 1, closeIndex)
        if (content.isBlank() || content.any { it == close }) return null
        // Not inside a word (`snake_case`) and not purely numeric (`2*3*4`).
        if (openIndex > 0 && textBeforeCaret[openIndex - 1].isLetterOrDigit()) return null
        if (content.none { it.isLetter() }) return null
        // Never inside a URL.
        val tokenStart = maxOf(0, openIndex - 24)
        val before = textBeforeCaret.substring(tokenStart, openIndex)
        if (before.contains("://") || before.contains("www.")) return null
        return InlineMarker(openIndex + 1, closeIndex, kind)
    }

    /** Leading spaces/tabs of the current line, kept by Enter on a plain paragraph. */
    fun autoIndent(textBeforeCaret: String): String {
        val end = textBeforeCaret.indexOfFirst { it != ' ' && it != '\t' }
        return if (end <= 0) "" else textBeforeCaret.substring(0, end)
    }

    /**
     * An ASCII arrow sequence ending at the caret to be replaced with a single arrow character.
     * [textBeforeCaret] is the current line text up to and including the character just typed.
     * Returns null if no arrow matches or if within a URL-like token.
     */
    fun arrow(textBeforeCaret: String): ArrowReplacement? {
        if (textBeforeCaret.isEmpty()) return null
        val candidate = when (textBeforeCaret.last()) {
            '>' -> when {
                textBeforeCaret.endsWith("<=>") -> ArrowReplacement(3, "⇔", "<=>")
                textBeforeCaret.endsWith("←>") -> ArrowReplacement(2, "↔", "<->")
                textBeforeCaret.endsWith("<->") -> ArrowReplacement(3, "↔", "<->")
                textBeforeCaret.endsWith("->") -> ArrowReplacement(2, "→", "->")
                textBeforeCaret.endsWith("=>") -> ArrowReplacement(2, "⇒", "=>")
                else -> null
            }
            '-' -> when {
                textBeforeCaret.endsWith("<--") -> null
                textBeforeCaret.endsWith("<-") -> ArrowReplacement(2, "←", "<-")
                else -> null
            }
            else -> null
        } ?: return null

        val tokenStart = textBeforeCaret.indexOfLast { it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
        val token = textBeforeCaret.substring(tokenStart)
        if (token.contains("://") || token.startsWith("www.")) return null

        return candidate
    }
}
