package org.samesame.notes.core.sdocx.record

/**
 * The text-common block (objects spec §6): rich text of the note title, note body or any shape's
 * text. Span and paragraph records are length-prefixed, so unknown kinds are preserved verbatim
 * ([SpanRecord.raw] / [ParagraphRecord.raw] are the full record bodies after the `u16` length).
 */
class TextCommon(
    val text: String,
    val spans: List<SpanRecord>,
    val paragraphs: List<ParagraphRecord>,
    val marginLeft: Float,
    val marginTop: Float,
    val marginRight: Float,
    val marginBottom: Float,
    val gravity: Int,
    val sections: List<SectionRange>,
    /** Present when the owning object's `objFormatVersion >= 2035` (spec §6). */
    val tailFlags: Int?,
    val reservedTail: Int?,
    val objectSpans: List<ObjectSpan>,
    /** Bytes after the modelled tail (unknown extensions), kept verbatim. */
    val tailExtra: ByteArray = ByteArray(0),
) {
    class SpanRecord(
        val kind: Int,
        val start: Int,
        val end: Int,
        val flag: Int,
        /** Full record body after the `u16 recLen`: kind/start/end/flag + kind payload. */
        val raw: ByteArray,
    )

    class ParagraphRecord(
        val kind: Int,
        val start: Int,
        val end: Int,
        /** Full record body after the `u16 recLen`: kind/start/end + payload. */
        val raw: ByteArray,
    )

    data class SectionRange(val start: Int, val length: Int)

    /** An inline/floating object anchored in the text (objects spec §6.4). */
    class ObjectSpan(
        val objectType: Int,
        /** Full object record, no trailing hash. */
        val record: ByteArray,
        val charPos: Int,
    )

    companion object {
        const val TAIL_FLAG_OBJECT_SPANS = 1
    }
}
