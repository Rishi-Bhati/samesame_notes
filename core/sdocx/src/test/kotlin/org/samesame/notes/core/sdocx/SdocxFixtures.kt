package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.io.SizedInt
import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.MediaEntry
import org.samesame.notes.core.sdocx.record.MediaRegistry
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageIdInfo
import org.samesame.notes.core.sdocx.record.PageRecord
import org.samesame.notes.core.sdocx.record.TextBoxRecord
import org.samesame.notes.core.sdocx.record.VoiceDataRecord

/**
 * Builds synthetic `.sdocx` fixtures programmatically, strictly from the spec's field tables.
 * No real (.sdocx) files are used: the clean-room rule forbids copying sample notes.
 */
object SdocxFixtures {

    const val UUID_1 = "11111111-1111-1111-1111-111111111111"
    const val UUID_2 = "22222222-2222-2222-2222-222222222222"
    const val UUID_LAYER = "33333333-3333-3333-3333-333333333333"
    const val UUID_TITLE = "44444444-4444-4444-4444-444444444444"
    const val UUID_BODY = "55555555-5555-5555-5555-555555555555"
    const val UUID_STROKE = "66666666-6666-6666-6666-666666666666"
    const val ACCOUNT_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    const val CREATED_US = 1_653_625_035_083_788L
    const val MODIFIED_US = 1_653_625_035_092_604L

    fun archive(
        bodyText: String = "Hello sdocx\nSecond line",
        locked: Boolean = false,
        includeUnknownObject: Boolean = true,
        objectUuid: String = UUID_STROKE,
        objectModifiedUs: Long = MODIFIED_US,
        pageMode: Int = SdocxFormat.PageMode.LIST,
        voices: List<VoiceDataRecord> = emptyList(),
    ): SdocxArchive {
        val titleBox = textBox(UUID_TITLE, MODIFIED_US, "", titled = true)
        val bodyBox = textBox(UUID_BODY, MODIFIED_US, bodyText, titled = false)

        val notePropsRaw = LeWriter().apply {
            i32(0) // lastEditedPageIndex
            i32(-1) // lastEditedPageImageId
            i64(0) // lastEditedPageTime
            i32(0) // bodyTextFontSizeDelta
            if (voices.isNotEmpty()) {
                i32(voices.size)
                for (voice in voices) {
                    val record = LeWriter().apply {
                        i32(voice.fileId)
                        str16(voice.name)
                        str16(voice.playTime)
                        i64(voice.createdTime)
                        i32(voice.events.size)
                        for (event in voice.events) { i32(event.action); i64(event.timestamp) }
                        voice.recordingTime?.let { i64(it) }
                        if (voice.extra.isNotEmpty()) bytes(voice.extra)
                    }.toByteArray()
                    i32(record.size)
                    bytes(record)
                }
            }
        }.toByteArray()
        val notePropMask = 0x0A80 or (if (voices.isNotEmpty()) 0x2000 else 0)
        val noteProps = parseNotePropertyBlock(notePropsRaw, notePropMask)

        val note = NoteRecord(
            propOffset = 0,
            noteFlags = SizedInt(4, 8, byteArrayOf(8, 0, 0, 0)),
            propMask = notePropMask,
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            noteId = "",
            fileRevision = 1,
            createdTime = CREATED_US,
            modifiedTime = MODIFIED_US,
            width = 2000,
            height = 5700,
            pageHorizontalPadding = 0,
            pageVerticalPadding = 44,
            minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            title = TextBoxRecord(titleBox, parseTextCommonFromTextBox(titleBox)),
            body = TextBoxRecord(bodyBox, parseTextCommonFromTextBox(bodyBox)),
            properties = noteProps,
            docHash = ByteArray(32),
        )

        val pagePropsRaw = LeWriter().apply {
            i32(2) // bgImageMode
            i32(0xFFFCFCFC.toInt()) // bgColor
            i32(2000) // bgWidth
        }.toByteArray()
        val pageProps = parsePagePropertyBlock(pagePropsRaw, 0x70, SdocxFormat.WRITE_FORMAT_VERSION)

        val stroke = ObjectEntry(
            objectType = SdocxFormat.ObjectType.STROKE,
            childCount = 0,
            record = strokeRecord(objectUuid, objectModifiedUs),
            originalHash = ByteArray(32),
            uuid = objectUuid,
            modifiedTime = objectModifiedUs,
            children = emptyList(),
        )
        val unknown = ObjectEntry(
            objectType = 9,
            childCount = 0,
            record = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            originalHash = ByteArray(32) { (it + 1).toByte() },
            uuid = null,
            modifiedTime = null,
            children = emptyList(),
        )

        val layerOptional = LeWriter().apply {
            str16(UUID_LAYER)
            i64(MODIFIED_US)
        }.toByteArray()
        val layer = LayerRecord(
            flags = 0x02,
            optMask = 0x18,
            layerNumber = 0,
            optionalRaw = layerOptional,
            objects = listOfNotNull(stroke, if (includeUnknownObject) unknown else null),
            originalHash = ByteArray(32),
            layerId = UUID_LAYER,
            modifiedTime = MODIFIED_US,
        )

        val page = PageRecord(
            orientation = 0,
            width = 2000,
            height = 2828,
            offsetX = 0,
            offsetY = 0,
            pageId = UUID_1,
            modifiedTime = MODIFIED_US,
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            pageFlags = SizedInt(4, 0, byteArrayOf(0, 0, 0, 0)),
            propMask = 0x70,
            properties = pageProps,
            layers = listOf(layer),
            originalHash = ByteArray(32),
            currentLayerIndex = 0,
        )

        val endTag = EndTagRecord(
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            noteId = "",
            modifiedTime = MODIFIED_US,
            propertyFlags = 0,
            coverImage = "",
            noteWidth = 2000,
            noteHeight = 5700f,
            appName = "fixture",
            appMajorVersion = 1,
            appMinorVersion = 0,
            appPatchName = "",
            minFormatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            createdTime = CREATED_US,
            lastViewedPageIndex = 0,
            pageMode = pageMode,
            documentType = if (locked) SdocxFormat.DocumentType.LOCKED_SDOCX else SdocxFormat.DocumentType.UNLOCKED,
            ownerId = if (locked) ACCOUNT_ID else "",
            reserved1 = ByteArray(0),
            reserved2 = ByteArray(0),
            displayCreatedTime = CREATED_US,
            displayModifiedTime = MODIFIED_US,
            lastRecognizedDataModifiedTime = 0,
            fixedFont = "",
            fixedTextDirection = 2,
            fixedBackgroundTheme = 2,
            hasTail = true,
        )

        val mediaEntry = MediaEntry(
            bindNumber = 0,
            fileName = "pic.png",
            fileHash = "aa".repeat(32),
            refCount = 1,
            modifiedTime = MODIFIED_US,
            isFileAttached = null,
        )
        val media = MediaRegistry(
            extended = false,
            formatVersion = SdocxFormat.WRITE_FORMAT_VERSION,
            entries = listOf(mediaEntry) + voices.map { voice ->
                MediaEntry(voice.fileId, "voice-${voice.fileId}.m4a", "bb".repeat(32), 1, MODIFIED_US, null)
            },
            hasTerminator = true,
        )

        return SdocxArchive(
            endTag = endTag,
            note = note,
            pageIdInfo = PageIdInfo(note.docHash, listOf(PageIdInfo.Entry(UUID_1, page.originalHash))),
            pages = listOf(page),
            media = media,
            mediaFiles = mapOf("media/pic.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)) +
                voices.associate { "media/voice-${it.fileId}.m4a" to byteArrayOf(it.fileId.toByte(), 1, 2, 3) },
            extraEntries = emptyMap(),
        )
    }

    /** A text-box object record (base → shapeBase → shape → textBox), per objects spec §2/§3. */
    fun textBox(uuid: String, modifiedUs: Long, text: String, titled: Boolean): ByteArray {
        val base = baseBlock(uuid, modifiedUs)
        val shapeBase = shapeBaseBlock()
        val shape = shapeBlock(text, titled)
        val textBox = textBoxBlock()
        return base + shapeBase + shape + textBox
    }

    fun baseBlock(uuid: String, modifiedUs: Long): ByteArray {
        val w = LeWriter()
        w.i32(105)
        w.i16(0)
        w.i32(0)
        w.sizedInt(2, 0x01BF)
        w.sizedInt(4, 0)
        w.i32(SdocxFormat.WRITE_FORMAT_VERSION)
        w.uuid36(uuid)
        w.i64(modifiedUs)
        w.f64(0.0); w.f64(0.0); w.f64(2000.0); w.f64(2828.0)
        w.i32(0)
        w.u8(0)
        return w.toByteArray()
    }

    private fun shapeBaseBlock(): ByteArray {
        val w = LeWriter()
        w.i32(27)
        w.i16(6)
        w.i32(0)
        w.sizedInt(1, 0)
        w.sizedInt(1, 0)
        w.i32(0) // nConnectionPoints
        w.i32(0) // connectedBytes
        w.i32(0) // nConnected
        w.u8(0) // unnamed flag
        return w.toByteArray()
    }

    private fun shapeBlock(text: String, titled: Boolean): ByteArray {
        val tc = textCommon(text)
        val mask = if (titled) 0x1001 else 0x3001
        val fixed = LeWriter().apply {
            i32(0) // blockSize placeholder
            i16(7)
            i32(0) // optOffset placeholder
            sizedInt(1, 0x04) // shapeAttr: text editable
            sizedInt(4, mask.toLong())
            i32(4) // shapeType
            f64(0.0); f64(0.0); f64(2000.0); f64(2828.0) // originalRect
            f32(0f) // originalDegree
            i32(0) // pathLen
            u8(0) // nCtrl
        }
        val optOffset = fixed.size
        fixed.i32(tc.size)
        fixed.bytes(tc)
        fixed.u8(1) // bit 12: ellipsis dots
        if (!titled) fixed.u8(3) // bit 13: autofit both
        val blockSize = fixed.size
        fixed.patchI32(0, blockSize)
        fixed.patchI32(6, optOffset)
        return fixed.toByteArray()
    }

    private fun textBoxBlock(): ByteArray {
        val w = LeWriter()
        w.i32(15)
        w.i16(2)
        w.i32(0)
        w.sizedInt(1, 0)
        w.sizedInt(2, 0)
        return w.toByteArray()
    }

    /** Serializes a text-common block (objects spec §6) for the fixture text boxes. */
    fun textCommon(text: String): ByteArray {
        val w = LeWriter()
        val units = text.toCharArray()
        w.i32(units.size)
        for (c in units) {
            w.u8(c.code and 0xFF)
            w.u8((c.code ushr 8) and 0xFF)
        }
        val spans = if (text.isEmpty()) emptyList() else listOf(
            span(3, 0, text.length, LeWriter().apply { f32(17f); i32(0) }.toByteArray()),
            span(1, 0, text.length, LeWriter().apply { i32(0xFF252525.toInt()); i32(0) }.toByteArray()),
        )
        w.i32(spans.size)
        for (s in spans) {
            w.u16(s.size)
            w.bytes(s)
        }
        val paras = if (text.isEmpty()) emptyList() else listOf(
            paragraph(3, 0, text.length - 1, LeWriter().apply { i32(0); i32(0) }.toByteArray()),
            paragraph(6, 0, text.length - 1, LeWriter().apply { i32(0); i32(0) }.toByteArray()),
        )
        w.i32(paras.size)
        for (p in paras) {
            w.u16(p.size)
            w.bytes(p)
        }
        w.f32(16f); w.f32(10f); w.f32(16f); w.f32(10f)
        w.u8(0) // gravity top
        w.u16(1)
        w.i32(0); w.i32(text.length) // one section
        w.i32(0) // tailFlags: no object spans
        w.i32(0) // reserved
        return w.toByteArray()
    }

    private fun span(kind: Int, start: Int, end: Int, payload: ByteArray): ByteArray =
        LeWriter().apply {
            i32(kind); i32(start); i32(end); i32(1); bytes(payload)
        }.toByteArray()

    private fun paragraph(kind: Int, start: Int, end: Int, payload: ByteArray): ByteArray =
        LeWriter().apply {
            i32(kind); i32(start); i32(end); bytes(payload)
        }.toByteArray()

    /** A current stroke record: a valid base block plus an opaque payload. */
    fun strokeRecord(uuid: String, modifiedUs: Long): ByteArray =
        baseBlock(uuid, modifiedUs) + byteArrayOf(0x10, 0x00, 0x00, 0x00, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

}

internal fun ObjectEntry.withIdentity(uuid: String?, modifiedTime: Long?): ObjectEntry =
    ObjectEntry(objectType, childCount, record, originalHash, uuid, modifiedTime, children)
