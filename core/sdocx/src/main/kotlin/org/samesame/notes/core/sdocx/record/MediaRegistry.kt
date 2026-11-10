package org.samesame.notes.core.sdocx.record

/** One entry of `media/mediaInfo.dat` (core spec §8). */
class MediaEntry(
    val bindNumber: Int,
    val fileName: String,
    /** 64-char lowercase hex digest, or empty when the source had none. */
    val fileHash: String,
    val refCount: Int,
    val modifiedTime: Long,
    /** Extended ("EOFX") form only; null in the basic form. */
    val isFileAttached: Boolean?,
    /** Bytes after the modelled fields up to the extended `entryLen`, kept verbatim. */
    val extra: ByteArray = ByteArray(0),
)

/**
 * `media/mediaInfo.dat` (core spec §8). [extended] selects the "EOFX" layout: a leading
 * `formatVersion` and a per-entry `i32 entryLen` + trailing attached byte.
 */
class MediaRegistry(
    val extended: Boolean,
    val formatVersion: Int,
    val entries: List<MediaEntry>,
    /** Whether the file had a trailing terminator (`EOF`/`EOFX`); empty registries often have none. */
    val hasTerminator: Boolean,
)
