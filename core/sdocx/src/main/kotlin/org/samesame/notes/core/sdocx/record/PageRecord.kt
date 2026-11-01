package org.samesame.notes.core.sdocx.record

import org.samesame.notes.core.sdocx.io.SizedInt

/** A `rectF64` as four doubles. */
data class RectF64(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/** A `rectI32`/`rectF32` placement of a PDF page on a page (core spec §5.3 bit 8). */
class PdfPlacement(
    val fileId: Int,
    val pdfPageIndex: Int,
    /** Raw 16-byte rect, `rectI32` (v>=2034) or `rectF32` (older). Preserved verbatim. */
    val rectRaw: ByteArray,
    val usesF32: Boolean,
)

/** Page property block (core spec §5.3): parsed values plus the raw block for lossless rewrite. */
class PagePropertyBlock(
    val mask: Int,
    val drawnRect: RectF64?,
    val tags: List<String>?,
    val templateUri: String?,
    val bgImageId: Int?,
    val bgImageMode: Int?,
    val bgColor: Long?,
    val bgWidth: Int?,
    val bgRotation: Int?,
    val pdf: List<PdfPlacement>?,
    val templateType: Int?,
    /** Raw canvas-cache map bytes (`n` entries) or null when the bit is absent. */
    val canvasCacheRaw: ByteArray?,
    val importedDataHeight: Int?,
    /** Unknown bit 12 (4 bytes), preserved. */
    val unknown12: ByteArray?,
    /** Raw bytes of the whole property block; writer re-emits these. */
    val raw: ByteArray,
)

/** A page file `<pageId>.page` (core spec §5). */
class PageRecord(
    val orientation: Int,
    val width: Int,
    val height: Int,
    val offsetX: Int,
    val offsetY: Int,
    val pageId: String,
    val modifiedTime: Long,
    val formatVersion: Int,
    val minFormatVersion: Int,
    val pageFlags: SizedInt,
    val propMask: Int,
    val properties: PagePropertyBlock,
    val layers: List<LayerRecord>,
    /** On-disk page hash; recomputed on write. */
    val originalHash: ByteArray,
    /** `currentLayerIndex` from the layer list header. */
    val currentLayerIndex: Int,
) {
    val isTextOnly: Boolean get() = pageFlags.bit(0)
    val isLandscape: Boolean get() = orientation == 1
}
