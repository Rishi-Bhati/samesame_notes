package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.NoteRecord

/**
 * Mirrors note-header fields into `end_tag.bin` (spec §2 "where end-tag values come from").
 *
 * The end tag is a copy of the note header plus a few trailer-only fields. Every path that changes
 * the note or a page (text edit, voice list, page-image fallback) must rebuild it from the final
 * note so the two never disagree; unknown trailer fields are carried over unchanged.
 */
internal object SdocxMirror {

    fun endTag(
        note: NoteRecord,
        source: EndTagRecord,
        modifiedTimeUs: Long = note.modifiedTime,
        displayModifiedTimeUs: Long = modifiedTimeUs,
        lastViewedPageIndex: Int = source.lastViewedPageIndex,
    ): EndTagRecord = EndTagRecord(
        formatVersion = note.formatVersion,
        noteId = note.noteId,
        modifiedTime = modifiedTimeUs,
        propertyFlags = source.propertyFlags,
        coverImage = source.coverImage,
        noteWidth = note.width,
        noteHeight = note.height.toFloat(),
        appName = source.appName,
        appMajorVersion = source.appMajorVersion,
        appMinorVersion = source.appMinorVersion,
        appPatchName = source.appPatchName,
        minFormatVersion = note.minFormatVersion,
        createdTime = note.createdTime,
        lastViewedPageIndex = lastViewedPageIndex,
        pageMode = source.pageMode,
        documentType = source.documentType,
        ownerId = source.ownerId,
        reserved1 = source.reserved1,
        reserved2 = source.reserved2,
        displayCreatedTime = source.displayCreatedTime,
        displayModifiedTime = displayModifiedTimeUs,
        lastRecognizedDataModifiedTime = source.lastRecognizedDataModifiedTime,
        fixedFont = source.fixedFont,
        fixedTextDirection = source.fixedTextDirection,
        fixedBackgroundTheme = source.fixedBackgroundTheme,
        hasTail = source.hasTail,
        hasFixedFontGroup = source.hasFixedFontGroup,
        tailExtra = source.tailExtra,
    )

    /** [tag] with other display times; every other field is identical. */
    fun withDisplayTimes(tag: EndTagRecord, displayCreated: Long, displayModified: Long): EndTagRecord = EndTagRecord(
        formatVersion = tag.formatVersion,
        noteId = tag.noteId,
        modifiedTime = tag.modifiedTime,
        propertyFlags = tag.propertyFlags,
        coverImage = tag.coverImage,
        noteWidth = tag.noteWidth,
        noteHeight = tag.noteHeight,
        appName = tag.appName,
        appMajorVersion = tag.appMajorVersion,
        appMinorVersion = tag.appMinorVersion,
        appPatchName = tag.appPatchName,
        minFormatVersion = tag.minFormatVersion,
        createdTime = tag.createdTime,
        lastViewedPageIndex = tag.lastViewedPageIndex,
        pageMode = tag.pageMode,
        documentType = tag.documentType,
        ownerId = tag.ownerId,
        reserved1 = tag.reserved1,
        reserved2 = tag.reserved2,
        displayCreatedTime = displayCreated,
        displayModifiedTime = displayModified,
        lastRecognizedDataModifiedTime = tag.lastRecognizedDataModifiedTime,
        fixedFont = tag.fixedFont,
        fixedTextDirection = tag.fixedTextDirection,
        fixedBackgroundTheme = tag.fixedBackgroundTheme,
        hasTail = tag.hasTail,
        hasFixedFontGroup = tag.hasFixedFontGroup,
        tailExtra = tag.tailExtra,
    )
}
