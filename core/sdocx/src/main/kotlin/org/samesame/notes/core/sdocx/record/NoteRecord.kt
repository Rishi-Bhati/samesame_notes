package org.samesame.notes.core.sdocx.record

import org.samesame.notes.core.sdocx.io.SizedInt

/** Legacy or new "pen info" (core spec §3.4, bits 12 and 15). */
class PenInfo(
    val penName: String,
    val size: Float,
    val colorArgb: Long,
    val curvable: Int,
    val advancedSetting: String,
    val eraserEnabled: Int,
    val sizeLevel: Int,
    val particleDensity: Int,
    /** Only in the new layout. */
    val particleSize: Float?,
    /** Only in the new layout. */
    val fixedWidth: Int?,
    val hsv: List<Float>,
    val colorUiInfo: Int,
)

/** A voice recording in the note property block (core spec §3.4 bit 13, objects §7). */
class VoiceDataRecord(
    val fileId: Int,
    val name: String,
    val playTime: String,
    val createdTime: Long,
    val events: List<Event>,
    val recordingTime: Long?,
    /** Bytes of the record past `recordingTime` (unknown trailing fields), kept when the record is re-emitted. */
    val extra: ByteArray = ByteArray(0),
) {
    data class Event(val action: Int, val timestamp: Long)
}

/** A title/body text box: the raw record (no trailing hash) plus its parsed text-common. */
class TextBoxRecord(
    /** Full object record bytes, as embedded in `note.note` (no trailing hash). */
    val raw: ByteArray,
    val text: TextCommon?,
)

/**
 * `note.note` property block (core spec §3.4): parsed values plus the raw block. Only the bits the
 * writer emits are modelled; unknown bits (4, 5, 8) never occur and cause a parse error.
 */
class NotePropertyBlock(
    val mask: Int,
    val appName: String?,
    val appVersion: Triple<Int, Int, String>?,
    val author: List<String>?,
    val authorInt: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val templateUri: String?,
    val lastEditedPageIndex: Int?,
    val lastEditedPageImageId: Int?,
    val lastEditedPageTime: Long?,
    val stringIdTable: List<IdText>?,
    val bodyTextFontSizeDelta: Int?,
    val legacyPen: PenInfo?,
    val voiceData: List<VoiceDataRecord>?,
    val attachedFiles: List<AttachedFile>?,
    val pen: PenInfo?,
    /** Raw bytes of the whole property block; writer re-emits these. */
    val raw: ByteArray,
    /** Offset in [raw] where the bit-13 voice section starts (or would start); == [voiceEnd] when absent. */
    val voiceStart: Int = 0,
    val voiceEnd: Int = 0,
) {
    data class IdText(val id: Int, val text: String)
    data class AttachedFile(val key: String, val fileId: Int)
}

/** The parsed `note.note` (core spec §3). */
class NoteRecord(
    var propOffset: Int,
    val noteFlags: SizedInt,
    val propMask: Int,
    val formatVersion: Int,
    val noteId: String,
    val fileRevision: Int,
    val createdTime: Long,
    val modifiedTime: Long,
    val width: Int,
    val height: Int,
    val pageHorizontalPadding: Int,
    val pageVerticalPadding: Int,
    val minFormatVersion: Int,
    val title: TextBoxRecord,
    val body: TextBoxRecord,
    val properties: NotePropertyBlock,
    /** 32-byte SHA-256 tail; recomputed on write. */
    val docHash: ByteArray,
) {
    /** Bit 3 (value 8): background colour inversion allowed, i.e. dark-mode adjustment enabled. */
    val adjustForDarkMode: Boolean get() = noteFlags.hasBit(3)
}
