package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.MediaEntry
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord

/** Serializers for the fixed structures. Object records and property blocks are re-emitted raw. */
internal object SdocxSerializers {

    /** Builds `note.note`: header + title/body (raw) + property block (raw) + recomputed docHash. */
    fun note(record: NoteRecord): ByteArray {
        val w = LeWriter()
        w.i32(0) // propOffset placeholder
        w.sizedIntRaw(record.noteFlags)
        w.sizedInt(4, record.propMask.toLong())
        w.i32(record.formatVersion)
        w.str16(record.noteId)
        w.i32(record.fileRevision)
        w.i64(record.createdTime)
        w.i64(record.modifiedTime)
        w.i32(record.width)
        w.i32(record.height)
        w.i32(record.pageHorizontalPadding)
        w.i32(record.pageVerticalPadding)
        w.i32(record.minFormatVersion)

        w.i32(record.title.raw.size)
        w.bytes(record.title.raw)
        w.i32(record.body.raw.size)
        w.bytes(record.body.raw)

        val propOffset = w.size
        w.bytes(record.properties.raw)
        w.patchI32(0, propOffset)

        val content = w.toByteArray()
        val out = LeWriter(content.size + 32)
        out.bytes(content)
        out.bytes(sha256(content))
        return out.toByteArray()
    }

    class SerializedPage(val bytes: ByteArray, val hash: ByteArray)

    /** Builds a `<pageId>.page`: header + raw property block + layers + recomputed page hash. */
    fun page(page: PageRecord): SerializedPage {
        val w = LeWriter()
        w.i32(0) // layerListOffset placeholder
        w.i32(0) // propOffset placeholder
        w.sizedIntRaw(page.pageFlags)
        w.sizedInt(4, page.propMask.toLong())
        w.i32(page.orientation)
        w.i32(page.width)
        w.i32(page.height)
        w.i32(page.offsetX)
        w.i32(page.offsetY)
        w.str16(page.pageId)
        w.i64(page.modifiedTime)
        w.i32(page.formatVersion)
        w.i32(page.minFormatVersion)

        val propOffset = w.size
        w.bytes(page.properties.raw)
        val layerListOffset = w.size

        w.u16(page.layers.size)
        w.u16(page.currentLayerIndex)

        val layerHashes = ArrayList<ByteArray>(page.layers.size)
        for (layer in page.layers) {
            val layerStart = w.size
            w.i32(0) // headerSize placeholder
            w.i32(0) // optAbsOffset placeholder
            w.sizedInt(1, layer.flags.toLong())
            w.sizedInt(1, layer.optMask.toLong())
            w.i32(layer.layerNumber)
            w.bytes(layer.optionalRaw)
            val headerSize = w.size - layerStart
            w.i32(layer.objects.size)

            val objectHashes = ArrayList<ByteArray>()
            for (obj in layer.objects) writeObject(w, obj, objectHashes)

            val inner = sha256("${layer.layerId ?: ""}${layer.modifiedTime ?: 0L}")
            val layerHash = sha256(concat(objectHashes) + inner)
            w.bytes(layerHash)
            layerHashes += layerHash

            w.patchI32(layerStart, headerSize)
            w.patchI32(layerStart + 4, layerStart + 16)
        }

        val pageInner = sha256("${page.pageId}${page.modifiedTime}")
        val pageHash = sha256(concat(layerHashes) + pageInner)
        w.bytes(pageHash)
        w.bytes(SdocxFormat.PAGE_MAGIC.toByteArray(Charsets.US_ASCII))

        w.patchI32(0, layerListOffset)
        w.patchI32(4, propOffset)
        return SerializedPage(w.toByteArray(), pageHash)
    }

    private fun writeObject(w: LeWriter, obj: ObjectEntry, hashes: MutableList<ByteArray>) {
        val hash = if (obj.uuid != null && obj.modifiedTime != null) {
            objectHash(obj.uuid, obj.modifiedTime)
        } else {
            obj.originalHash
        }
        w.u8(obj.objectType)
        w.u16(obj.childCount)
        w.i32(obj.record.size + 32)
        w.bytes(obj.record)
        w.bytes(hash)
        hashes += hash
        for (child in obj.children) writeObject(w, child, hashes)
    }

    /** Builds `pageIdInfo.dat` from the doc hash and the (already serialized) page records. */
    fun pageIdInfo(docHash: ByteArray, pageHashes: List<Pair<String, ByteArray>>): ByteArray {
        val w = LeWriter()
        w.bytes(docHash)
        w.u16(pageHashes.size)
        for ((id, hash) in pageHashes) {
            w.str16(id)
            w.bytes(hash)
        }
        return w.toByteArray()
    }

    /** Builds `media/mediaInfo.dat`, matching the extended/basic form of the source. */
    fun mediaInfo(registry: MediaRegistry): ByteArray {
        val w = LeWriter()
        if (registry.extended) w.i32(registry.formatVersion)
        w.u16(registry.entries.size)
        for (entry in registry.entries) {
            if (registry.extended) {
                val body = LeWriter()
                writeMediaEntryBody(body, entry)
                val bodyBytes = body.toByteArray()
                w.i32(bodyBytes.size)
                w.bytes(bodyBytes)
            } else {
                writeMediaEntryBody(w, entry)
            }
        }
        if (registry.extended) {
            w.bytes("EOFX".toByteArray(Charsets.US_ASCII))
        } else if (registry.entries.isNotEmpty()) {
            w.bytes("EOF".toByteArray(Charsets.US_ASCII))
        }
        return w.toByteArray()
    }

    private fun writeMediaEntryBody(w: LeWriter, entry: MediaEntry) {
        w.i32(entry.bindNumber)
        w.str16(entry.fileName)
        val hash = ByteArray(64)
        val ascii = entry.fileHash.toByteArray(Charsets.US_ASCII)
        System.arraycopy(ascii, 0, hash, 0, minOf(64, ascii.size))
        w.bytes(hash)
        w.u16(entry.refCount)
        w.i64(entry.modifiedTime)
        if (entry.isFileAttached != null) w.u8(if (entry.isFileAttached) 1 else 0)
    }

    /** Builds `end_tag.bin`; the tail is emitted only when the source had it. */
    fun endTag(tag: EndTagRecord): ByteArray {
        val w = LeWriter()
        w.u16(0) // bodyLen placeholder
        w.i32(tag.formatVersion)
        w.str16(tag.noteId)
        w.i64(tag.modifiedTime)
        w.i32(tag.propertyFlags)
        w.str16(tag.coverImage)
        w.i32(tag.noteWidth)
        w.f32(tag.noteHeight)
        w.str16(tag.appName)
        w.i32(tag.appMajorVersion)
        w.i32(tag.appMinorVersion)
        w.str16(tag.appPatchName)
        w.i32(tag.minFormatVersion)
        w.i64(tag.createdTime)
        w.i32(tag.lastViewedPageIndex)
        w.u16(tag.pageMode)
        if (tag.hasTail) {
            w.u16(tag.documentType)
            w.str16(tag.ownerId)
            w.i32(tag.reserved1.size); w.bytes(tag.reserved1)
            w.i32(tag.reserved2.size); w.bytes(tag.reserved2)
            w.i64(tag.displayCreatedTime)
            w.i64(tag.displayModifiedTime)
            w.i64(tag.lastRecognizedDataModifiedTime)
            w.str16(tag.fixedFont)
            w.i32(tag.fixedTextDirection)
            w.i32(tag.fixedBackgroundTheme)
        }
        w.bytes(SdocxFormat.END_TAG_MAGIC.toByteArray(Charsets.US_ASCII))
        w.patchU16(0, w.size - 2)
        return w.toByteArray()
    }
}
