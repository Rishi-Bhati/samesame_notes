package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.MediaEntry
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord
import java.security.MessageDigest
import java.util.UUID

/**
 * Lossy page-image fallback for ink (spec §13.3 item 4). The stroke payload layout is still unknown,
 * so a caller that needs to export handwriting can rasterise each page elsewhere (Android side) and
 * hand the PNG bytes here. This API embeds each image as a full-page image object on its page and
 * registers it in `media/`, leaving the original opaque stroke objects untouched.
 *
 * This is deliberately separate from [SdocxWriter]'s lossless path: it changes page content and is
 * only a visual fallback for page content that has no object mapping.
 */
object SdocxPageImageFallback {

    /**
     * @param pageImages page id → PNG bytes.
     * @param newUuid object id generator (override in tests for determinism).
     * @param nowUs microsecond clock (override in tests for determinism).
     */
    fun apply(
        archive: SdocxArchive,
        pageImages: Map<String, ByteArray>,
        newUuid: () -> String = { UUID.randomUUID().toString() },
        nowUs: () -> Long = { System.currentTimeMillis() * 1000 },
    ): SdocxArchive {
        if (pageImages.isEmpty()) return archive
        var nextBind = (archive.media.entries.maxOfOrNull { it.bindNumber } ?: -1) + 1
        val newMedia = ArrayList(archive.media.entries)
        val newFiles = LinkedHashMap(archive.mediaFiles)
        val stamp = nowUs()

        val newPages = archive.pages.map { page ->
            val image = pageImages[page.pageId] ?: return@map page
            val bind = nextBind++
            val fileName = "page-${page.pageId}.png"
            val entryName = "media/$fileName"
            newMedia += MediaEntry(
                bindNumber = bind,
                fileName = fileName,
                fileHash = hexSha256(image),
                refCount = 1,
                modifiedTime = stamp,
                isFileAttached = if (archive.media.extended) true else null,
            )
            newFiles[entryName] = image

            val objectRecord = imageObjectRecord(
                uuid = newUuid(),
                modifiedUs = stamp,
                width = page.width.toDouble(),
                height = page.height.toDouble(),
                imageId = bind,
            )
            val obj = ObjectEntry(
                objectType = SdocxFormat.ObjectType.IMAGE,
                childCount = 0,
                record = objectRecord,
                originalHash = ByteArray(32),
                uuid = objectRecordUuid(objectRecord),
                modifiedTime = stamp,
                children = emptyList(),
            )
            copyPageWithExtraObject(page, obj, stamp, newUuid)
        }

        val media = MediaRegistry(
            extended = archive.media.extended,
            formatVersion = archive.media.formatVersion,
            entries = newMedia,
            hasTerminator = archive.media.hasTerminator,
        )
        // Adding objects changes every derived hash; bump the note stamp so the end tag mirrors it.
        val note = org.samesame.notes.core.sdocx.record.NoteRecord(
            propOffset = archive.note.propOffset,
            noteFlags = archive.note.noteFlags,
            propMask = archive.note.propMask,
            formatVersion = archive.note.formatVersion,
            noteId = archive.note.noteId,
            fileRevision = archive.note.fileRevision + 1,
            createdTime = archive.note.createdTime,
            modifiedTime = stamp,
            width = archive.note.width,
            height = archive.note.height,
            pageHorizontalPadding = archive.note.pageHorizontalPadding,
            pageVerticalPadding = archive.note.pageVerticalPadding,
            minFormatVersion = archive.note.minFormatVersion,
            title = archive.note.title,
            body = archive.note.body,
            properties = archive.note.properties,
            docHash = archive.note.docHash,
        )
        return SdocxArchive(
            endTag = SdocxMirror.endTag(note, archive.endTag, modifiedTimeUs = stamp, displayModifiedTimeUs = stamp),
            note = note,
            pageIdInfo = archive.pageIdInfo,
            pages = newPages,
            media = media,
            mediaFiles = newFiles,
            extraEntries = archive.extraEntries,
            trailer = archive.trailer,
        )
    }

    private fun copyPageWithExtraObject(
        page: PageRecord,
        obj: ObjectEntry,
        stamp: Long,
        newUuid: () -> String,
    ): PageRecord {
        val layers = if (page.layers.isEmpty()) {
            val layerId = newUuid()
            listOf(
                LayerRecord(
                    flags = 0x02,
                    optMask = 0x18,
                    layerNumber = 0,
                    optionalRaw = LeWriter().apply {
                        str16(layerId)
                        i64(stamp)
                    }.toByteArray(),
                    objects = listOf(obj),
                    originalHash = ByteArray(32),
                    layerId = layerId,
                    modifiedTime = stamp,
                ),
            )
        } else {
            page.layers.mapIndexed { index, layer ->
                if (index != 0) layer else layerWithTime(layer, layer.objects + obj, stamp)
            }
        }
        return PageRecord(
            orientation = page.orientation,
            width = page.width,
            height = page.height,
            offsetX = page.offsetX,
            offsetY = page.offsetY,
            pageId = page.pageId,
            // The page content changed: bump its stamp so the page hash changes for sync/diff.
            modifiedTime = stamp,
            formatVersion = page.formatVersion,
            minFormatVersion = page.minFormatVersion,
            pageFlags = page.pageFlags,
            propMask = page.propMask,
            properties = page.properties,
            layers = layers,
            originalHash = page.originalHash,
            currentLayerIndex = page.currentLayerIndex,
        )
    }

    /** Adds [objects] to [layer] and bumps its stamp when its optional area can be rewritten safely. */
    internal fun layerWithTime(layer: LayerRecord, objects: List<ObjectEntry>, stamp: Long): LayerRecord {
        val fields = LayerOptionalCodec.parse(layer.optionalRaw, layer.optMask)
        val time = if (fields?.time != null) {
            fields.withTime(stamp)
        } else {
            fields
        }
        return LayerRecord(
            flags = layer.flags,
            optMask = layer.optMask,
            layerNumber = layer.layerNumber,
            optionalRaw = time?.write() ?: layer.optionalRaw,
            objects = objects,
            originalHash = layer.originalHash,
            layerId = layer.layerId,
            modifiedTime = time?.time ?: layer.modifiedTime,
        )
    }

    /**
     * Builds `base(0) → shapeBase(6) → shape(7, image fill kind 2) → image(3)` per spec §13.3 item 2
     * and objects spec §2/§3.1/§3.2/§3.7/§3.8.
     */
    fun imageObjectRecord(
        uuid: String,
        modifiedUs: Long,
        width: Double,
        height: Double,
        imageId: Int,
    ): ByteArray {
        val base = LeWriter().apply {
            i32(109) // 105 fixed + 4-byte attachedFileId
            i16(0)
            i32(105) // optional fields right after the fixed part
            sizedInt(2, 0x01BF)
            sizedInt(4, 0x40) // bit 6: attachedFileId
            i32(SdocxFormat.WRITE_FORMAT_VERSION)
            uuid36(uuid)
            i64(modifiedUs)
            f64(0.0); f64(0.0); f64(width); f64(height)
            i32(0) // timeStamp
            u8(0) // resizable
            i32(imageId) // attachedFileId
        }.toByteArray()

        val shapeBase = LeWriter().apply {
            i32(27)
            i16(6)
            i32(0)
            sizedInt(1, 0)
            sizedInt(1, 0)
            i32(0); i32(0); i32(0)
            u8(0)
        }.toByteArray()

        val shape = shapeBlock(width, height, imageId)
        val image = LeWriter().apply {
            i32(17)
            i16(3)
            i32(0)
            sizedInt(1, 0)
            sizedInt(4, 0)
        }.toByteArray()
        return base + shapeBase + shape + image
    }

    private fun shapeBlock(width: Double, height: Double, imageId: Int): ByteArray {
        val w = LeWriter()
        w.i32(0) // blockSize placeholder
        w.i16(7)
        w.i32(0) // optOffset placeholder
        w.sizedInt(1, 0) // shapeAttr
        w.sizedInt(4, (1L shl 5)) // bit 5: fill
        w.i32(0) // shapeType 0 for images
        w.f64(0.0); w.f64(0.0); w.f64(width); w.f64(height)
        w.f32(0f)
        w.i32(0) // pathLen
        w.u8(0) // nCtrl
        val optOffset = w.size

        // image fill: 63 bytes = kind byte + 62-byte image effect
        w.i32(63)
        w.u8(2) // fillKind: image
        w.u8(0) // imageType
        w.i32(imageId)
        w.bytes(ByteArray(16)) // stretchOffset rectF32
        w.bytes(ByteArray(8)) // tilingOffset pointF32
        w.f32(1f); w.f32(1f) // tiling scale
        w.f32(0f) // transparency
        w.u8(1) // rotatable
        w.bytes(ByteArray(16)) // ninePatchRect rectI32
        w.i32(0) // ninePatchWidth

        w.patchI32(0, w.size)
        w.patchI32(6, optOffset)
        return w.toByteArray()
    }

    private fun objectRecordUuid(record: ByteArray): String? = parseObjectBase(record)?.uuid

    private fun hexSha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
