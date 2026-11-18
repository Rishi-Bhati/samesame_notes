package org.samesame.notes.core.model.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PdfMarkupKind {
    @SerialName("highlight") Highlight,
    @SerialName("underline") Underline,
    @SerialName("strike") Strike,
}

/**
 * Four corners of one text line, in page units of the unrotated page ([PageSpec.rotation] is not applied), origin top
 * left, y down. Corner order: top-left, top-right, bottom-left, bottom-right (the order [fromRect] writes).
 */
@Serializable
data class QuadPu(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val x3: Float, val y3: Float, val x4: Float, val y4: Float,
) {
    val bounds: RectPu
        get() = RectPu(minOf(x1, x2, x3, x4), minOf(y1, y2, y3, y4), maxOf(x1, x2, x3, x4), maxOf(y1, y2, y3, y4))

    companion object {
        fun fromRect(r: RectPu): QuadPu = QuadPu(r.left, r.top, r.right, r.top, r.left, r.bottom, r.right, r.bottom)
    }
}

/**
 * A highlight, underline or strike-through over selected PDF text (v11). Lives in [PageSpec.textMarkup] and is anchored
 * to the page, so it survives rotating, reordering and deleting other pages.
 */
@Serializable
data class PdfTextMarkup(
    val id: String,
    val kind: PdfMarkupKind,
    /** ARGB packed in a Long, like the other colours in the document. */
    val color: Long,
    val quads: List<QuadPu>,
    /** The selected text, kept for copy and search; may be empty. */
    val text: String = "",
    val createdAtMs: Long = 0L,
)
