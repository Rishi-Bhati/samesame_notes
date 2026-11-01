package org.samesame.notes.core.sdocx.record

import org.samesame.notes.core.sdocx.io.SizedInt

/**
 * One object entry inside a layer (core spec §7): the type code, child count, the raw record bytes
 * (excluding the trailing 32-byte object hash) and the hash itself. Children (containers only) are
 * kept recursively. Unknown types survive byte-for-byte.
 */
class ObjectEntry(
    val objectType: Int,
    val childCount: Int,
    /** Object record bytes, excluding the trailing 32-byte hash. */
    val record: ByteArray,
    /** The on-disk 32-byte object hash (reused when the uuid/time cannot be parsed). */
    val originalHash: ByteArray,
    /** Parsed from the object-base block (tag 0); null when not parseable (e.g. legacy stroke). */
    val uuid: String?,
    val modifiedTime: Long?,
    val children: List<ObjectEntry>,
) {
    val isContainer: Boolean get() = childCount > 0
}

/**
 * One layer of a page (core spec §6). The optional-field area is kept raw (it is not individually
 * delimited), with the two values needed for the layer hash parsed out for convenience.
 */
class LayerRecord(
    val flags: Int,
    val optMask: Int,
    val layerNumber: Int,
    /** Raw bytes of the optional-field area (`optAbsOffset`..`headerSize`). */
    val optionalRaw: ByteArray,
    val objects: List<ObjectEntry>,
    /** On-disk layer hash; recomputed on write. */
    val originalHash: ByteArray,
    /** From optional bit 3 (uuid string), when present. */
    val layerId: String?,
    /** From optional bit 4 (µs), when present. */
    val modifiedTime: Long?,
) {
    val hidden: Boolean get() = flags and 0x1 != 0
    val eventForwardable: Boolean get() = flags and 0x2 != 0
    val locked: Boolean get() = flags and 0x4 != 0
}
