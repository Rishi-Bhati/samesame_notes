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
