package org.samesame.notes.core.sdocx.record

/**
 * `end_tag.bin` (core spec §2). The writer emits each optional group only when the source had it
 * (see [hasTail]/[hasFixedFontGroup]); a short input is accepted and the missing fields stay at
 * their documented defaults.
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
    /**
     * True when the last optional group (`fixedFont`/`fixedTextDirection`/`fixedBackgroundTheme`)
     * was present. The group is all-or-nothing (spec §2); samples stop before it. Defaults to
     * [hasTail] so archives built from parsed fields keep the writer's full layout.
     */
    val hasFixedFontGroup: Boolean = hasTail,
    /** Unknown bytes after the modelled optional groups, kept so an edited export is still byte-faithful. */
    val tailExtra: ByteArray = ByteArray(0),
) {
    val isLandscape: Boolean get() = propertyFlags and 0x2 != 0
    val isLocked: Boolean get() = documentType == 5
}
