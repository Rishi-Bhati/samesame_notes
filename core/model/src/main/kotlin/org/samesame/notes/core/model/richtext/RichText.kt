package org.samesame.notes.core.model.richtext

import kotlinx.serialization.Serializable

/** Character that stands for an inline object in [RichText.text]; its run carries the objectId. */
const val OBJECT_CHAR: Char = '￼'

/**
 * Runs-based rich text. Invariants (kept by [RichTextOps], restored by [RichTextOps.normalise]):
 * run lengths sum to `text.length`, no empty or mergeable adjacent runs, and
 * `paragraphs.size == count('\n') + 1`.
 */
@Serializable
data class RichText(
    val text: String = "",
    val runs: List<TextRun> = emptyList(),
    val paragraphs: List<ParagraphStyle> = listOf(ParagraphStyle()),
) {
    val length: Int get() = text.length

    /** Plain text with inline-object placeholders removed. */
    fun plainText(): String = text.replace(OBJECT_CHAR.toString(), "")

    companion object {
        fun of(text: String, style: CharStyle = CharStyle()): RichText =
            RichTextOps.normalise(RichText(text, listOf(TextRun(text.length, style))))
    }
}

@Serializable
data class TextRun(val length: Int, val style: CharStyle)

@Serializable
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    /** null = automatic (follows page / dark mode). */
    val color: Long? = null,
    val highlight: Long? = null,
    /** null = note default ([org.samesame.notes.core.model.Limits.DEFAULT_FONT_SIZE]). */
    val fontSize: Float? = null,
    /** Only on a U+FFFC character. */
    val objectId: String? = null,
    /**
     * Explicit hyperlink of the run (format v8): the run's text is the visible label, this is the URI, e.g. a library file
     * link `samesame://file/<id>?n=<name>` ([org.samesame.notes.core.model.files.FileLinks]). Part of the merge key like
     * every field, so two adjacent runs merge only when they link to the same URI. Typing at either end of a link does
     * not extend it ([RichTextOps.typingStyle]).
     */
    val link: String? = null,
)

@Serializable
enum class TextAlign { Left, Center, Right }

@Serializable
enum class ListKind { None, Bullet, Numbered, Checklist }

@Serializable
data class ParagraphStyle(
    val align: TextAlign = TextAlign.Left,
    val indent: Int = 0,
    val list: ListKind = ListKind.None,
    val checked: Boolean = false,
)

/**
 * Plain-text marker a list paragraph gets outside the editor (the .txt export and the clipboard): two spaces per
 * indent level, then `• `, `1. ` or `[x] `/`[ ] `. [number] is the 1-based item number of a [ListKind.Numbered]
 * paragraph (null when the caller has no numbering; it then falls back to 1).
 */
fun listMarkerPrefix(style: ParagraphStyle, number: Int?): String {
    val marker = when (style.list) {
        ListKind.None -> ""
        ListKind.Bullet -> "• "
        ListKind.Numbered -> "${number ?: 1}. "
        ListKind.Checklist -> if (style.checked) "[x] " else "[ ] "
    }
    return "  ".repeat(style.indent.coerceAtLeast(0)) + marker
}
