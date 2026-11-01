package org.samesame.notes.core.sdocx.record

/**
 * `end_tag.bin` (core spec §2). The optional tail is always emitted by the current writer; a short
 * input is accepted and the missing tail fields stay at their documented defaults.
 */
class EndTagRecord(
    val formatVersion: Int,
    val noteId: String,
    val modifiedTime: Long,
    val propertyFlags: Int,
    val coverImage: String,
    val noteWidth: Int,
    val noteHeight: Float,
    val appName: String,
    val appMajorVersion: Int,
    val appMinorVersion: Int,
    val appPatchName: String,
    val minFormatVersion: Int,
    val createdTime: Long,
    val lastViewedPageIndex: Int,
    val pageMode: Int,
    val documentType: Int,
    val ownerId: String,
    val reserved1: ByteArray,
    val reserved2: ByteArray,
    val displayCreatedTime: Long,
    val displayModifiedTime: Long,
    val lastRecognizedDataModifiedTime: Long,
    val fixedFont: String,
    val fixedTextDirection: Int,
    val fixedBackgroundTheme: Int,
    /** True when the optional tail was actually present in the source. */
    val hasTail: Boolean,
) {
    val isLandscape: Boolean get() = propertyFlags and 0x2 != 0
    val isLocked: Boolean get() = documentType == 5
}
