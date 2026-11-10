package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.NotePropertyBlock
import org.samesame.notes.core.sdocx.record.NoteRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord
import org.samesame.notes.core.sdocx.record.RectF64
import java.util.UUID

/**
 * One page stroke as the editor currently has it. [sourceUuid] is the object it came from, or null
 * for a stroke drawn in SameSame. [pen] is set when the stroke data must be written from a pen
 * profile (a new stroke, or an imported one whose pen was changed); otherwise [stroke] carries the
 * source's raw fields and is re-encoded from them.
 */
class SdocxPageStroke(
    val sourceUuid: String?,
    val stroke: SdocxStroke,
    val pen: SdocxPenProfile? = null,
)

/**
 * Merges the editor's page ink into an archive (objects spec §4.3, core spec §12.3).
 *
 * Matching is by object uuid:
 * - a stroke whose re-encoded data equals its source data keeps its record and hash as on disk;
 * - a changed stroke is re-encoded through [SdocxStrokeCodec], keeps its uuid and base fields and
 *   gets a new modified time (and with it a new object hash);
 * - a decodable stroke that is absent from the page's list was deleted and is dropped;
 * - a stroke without a source is appended to the page's current layer as a new record, and its pen
 *   name and advanced-setting string are added to the note's string table when missing.
 *
 * Records the codec cannot decode, and every object that is not a stroke, are never touched. Only
 * pages present in the map are considered. A changed layer and page get a new modified time (their
 * hashes follow in the writer); the page's drawn rect grows to cover moved and new ink and its
 * canvas cache entry, a rendering of the old content, is removed; the note revision and end tag
 * follow as for a text edit.
 */
object SdocxStrokeMerge {

    /** A page whose ink changed loses its cached rendering so a reader draws the page from its objects. */
    private const val DROP_STALE_CANVAS_CACHE = true

    fun apply(
        archive: SdocxArchive,
        pageStrokes: Map<String, List<SdocxPageStroke>>,
        nowUs: Long,
        newUuid: () -> String = { UUID.randomUUID().toString() },
        /** False while a new note is being assembled: its revision and times are not an edit's. */
        countAsEdit: Boolean = true,
    ): SdocxArchive {
        if (pageStrokes.isEmpty()) return archive
        val strings = StringTable(archive.note.properties.stringIdTable.orEmpty())
        val template = archive.pages.asSequence()
            .flatMap { SdocxInk.sources(it).asSequence() }
            .firstNotNullOfOrNull { SdocxStrokeCodec.baseTemplateOf(it.entry.record) }
        var changed = false
        val pages = archive.pages.map { page ->
            val strokes = pageStrokes[page.pageId] ?: return@map page
            val merged = PageMerge(page, strokes, strings, template, nowUs, newUuid).run()
            if (merged !== page) changed = true
            merged
        }
        if (!changed) return archive

        val properties = if (strings.changed) withStringTable(archive.note, strings.entries) else null
        val note = NoteRecord(
            propOffset = archive.note.propOffset,
            noteFlags = archive.note.noteFlags,
            propMask = properties?.mask ?: archive.note.propMask,
            formatVersion = archive.note.formatVersion,
            noteId = archive.note.noteId,
            fileRevision = if (countAsEdit) archive.note.fileRevision + 1 else archive.note.fileRevision,
            createdTime = archive.note.createdTime,
            modifiedTime = if (countAsEdit) nowUs else archive.note.modifiedTime,
            width = archive.note.width,
            height = archive.note.height,
            pageHorizontalPadding = archive.note.pageHorizontalPadding,
            pageVerticalPadding = archive.note.pageVerticalPadding,
            minFormatVersion = archive.note.minFormatVersion,
            title = archive.note.title,
            body = archive.note.body,
            properties = properties ?: archive.note.properties,
            docHash = archive.note.docHash,
        )
        return archive.rebuilt(
            endTag = if (countAsEdit) {
                SdocxMirror.endTag(note, archive.endTag, modifiedTimeUs = nowUs, displayModifiedTimeUs = nowUs)
            } else {
                archive.endTag
            },
            note = note,
            pages = pages,
        )
    }

    // ---- string table ----------------------------------------------------------------------

    /** The note's id → text table; new ids continue after the largest one, newest entries first as in real files. */
    private class StringTable(source: List<NotePropertyBlock.IdText>) {
        var entries: List<NotePropertyBlock.IdText> = source
            private set
        var changed = false
            private set

/**
         * The pen key of [profile]: the id of its class name, whose advanced-setting string has the
         * next id (a stroke stores both, and the second is always the first plus one). A pair that
         * is not in the table yet is added with two new ids.
         */
        fun penKey(profile: SdocxPenProfile): Int {
            val byId = entries.associate { it.id to it.text }
            entries.firstOrNull { it.text == profile.className && byId[it.id + 1] == profile.advanced }?.let { return it.id }
            val id = (entries.maxOfOrNull { it.id } ?: -1) + 1
            entries = listOf(NotePropertyBlock.IdText(id + 1, profile.advanced), NotePropertyBlock.IdText(id, profile.className)) + entries
            changed = true
            return id
        }
    }

    /**
     * The note's property block with [entries] as its string table (core spec §3.4 bit 10). Every
     * other property keeps its bytes.
     */
    internal fun withStringTable(note: NoteRecord, entries: List<NotePropertyBlock.IdText>): NotePropertyBlock {
        val raw = note.properties.raw
        val mask = note.propMask
        val r = LeReader(raw)
        if (mask and 0x1 != 0) r.str16()
        if (mask and 0x2 != 0) { r.i32(); r.i32(); r.str16() }
        if (mask and 0x4 != 0) { r.str16(); r.str16(); r.str16(); r.i32() }
        if (mask and 0x8 != 0) r.bytes(16)
        if (mask and 0x40 != 0) r.str16()
        if (mask and 0x80 != 0) r.i32()
        if (mask and 0x200 != 0) r.bytes(12)
        val start = r.pos
        if (mask and 0x400 != 0) {
            val byteLen = r.i32()
            if (byteLen > 0) r.bytes(byteLen)
        }
        val end = r.pos
        val body = LeWriter().apply {
            u16(entries.size)
            for (entry in entries) { i32(entry.id); str16(entry.text) }
        }.toByteArray()
        val out = LeWriter(raw.size + body.size + 4)
        out.bytes(raw.copyOfRange(0, start))
        out.i32(body.size)
        out.bytes(body)
        out.bytes(raw.copyOfRange(end, raw.size))
        return parseNotePropertyBlock(out.toByteArray(), mask or 0x400)
    }

    // ---- page ------------------------------------------------------------------------------

    private class PageMerge(
        private val page: PageRecord,
        strokes: List<SdocxPageStroke>,
        private val strings: StringTable,
        private val template: SdocxStrokeCodec.BaseTemplate?,
        private val nowUs: Long,
        private val newUuid: () -> String,
    ) {
        private val editable: Map<String, SdocxInk.Source> =
            SdocxInk.sources(page).associateBy { it.entry.uuid!! }
        private val wanted = LinkedHashMap<String, SdocxPageStroke>()
        private val added = ArrayList<SdocxPageStroke>()
        private var inkBounds: RectF64? = null

        init {
            for (item in strokes) {
                val uuid = item.sourceUuid
                if (uuid != null && uuid in editable && uuid !in wanted) wanted[uuid] = item else added += item
            }
        }

        fun run(): PageRecord {
            var pageChanged = false
            val target = page.currentLayerIndex.coerceIn(0, maxOf(page.layers.lastIndex, 0))
            val layers = ArrayList<LayerRecord>(page.layers.size + 1)
            for ((index, layer) in page.layers.withIndex()) {
                val merged = mergeObjects(layer.objects)
                var objects = merged.objects
                var layerChanged = merged.changed
                if (index == target && added.isNotEmpty()) {
                    objects = objects + added.flatMap(::newObjects)
                    layerChanged = true
                }
                if (layerChanged) {
                    pageChanged = true
                    layers += SdocxPageImageFallback.layerWithTime(layer, objects, nowUs)
                } else {
                    layers += layer
                }
            }
            if (page.layers.isEmpty() && added.isNotEmpty()) {
                layers += SdocxDocumentBuilder.newLayer(newUuid(), nowUs, added.flatMap(::newObjects))
                pageChanged = true
            }
            if (!pageChanged) return page
            return PageRecord(
                orientation = page.orientation,
                width = page.width,
                height = page.height,
                offsetX = page.offsetX,
                offsetY = page.offsetY,
                pageId = page.pageId,
                // Page content changed: a new time gives the page a new hash for sync/diff.
                modifiedTime = nowUs,
                formatVersion = page.formatVersion,
                minFormatVersion = page.minFormatVersion,
                pageFlags = page.pageFlags,
                propMask = page.propMask,
                properties = page.properties,
                layers = layers,
                originalHash = page.originalHash,
                currentLayerIndex = page.currentLayerIndex,
            ).let { PageProperties.afterInkChange(it, inkBounds, DROP_STALE_CANVAS_CACHE) }
        }

        private class Merged(val objects: List<ObjectEntry>, val changed: Boolean)

        private fun mergeObjects(objects: List<ObjectEntry>): Merged {
            var changed = false
            val out = ArrayList<ObjectEntry>(objects.size)
            for (obj in objects) {
                val source = obj.uuid?.let(editable::get)?.takeIf { it.entry === obj }
                if (source != null) {
                    val item = wanted[obj.uuid]
                    if (item == null) {
                        changed = true // deleted in the editor
                        continue
                    }
                    val rewritten = rewrite(source, item)
                    if (rewritten != null) changed = true
                    out += rewritten ?: obj
                    continue
                }
                if (obj.children.isEmpty()) {
                    out += obj
                    continue
                }
                val children = mergeObjects(obj.children)
                when {
                    !children.changed -> out += obj
                    // A group whose members were all deleted goes with them.
                    children.objects.isEmpty() -> changed = true
                    else -> {
                        changed = true
                        val touched = touch(obj.record)
                        out += ObjectEntry(
                            objectType = obj.objectType,
                            childCount = children.objects.size,
                            record = touched ?: obj.record,
                            originalHash = obj.originalHash,
                            uuid = obj.uuid,
                            modifiedTime = if (touched != null) nowUs else obj.modifiedTime,
                            children = children.objects,
                        )
                    }
                }
            }
            return Merged(out, changed)
        }

        /** [record] with the base block's modified time set to now, when it has the usual base layout. */
        private fun touch(record: ByteArray): ByteArray? {
            if (SdocxStrokeCodec.baseTemplateOf(record) == null) return null
            val w = LeWriter(record.size)
            w.bytes(record)
            w.patchI64(60, nowUs)
            return w.toByteArray()
        }

        /** The entry for an edited stroke, or null when its data is unchanged or cannot be written. */
        private fun rewrite(source: SdocxInk.Source, item: SdocxPageStroke): ObjectEntry? {
            val entry = source.entry
            val base = LeReader(entry.record).i32()
            val stroke = item.stroke
            val encoded = runCatching { encode(item, stroke.points) }.getOrNull()
                // A reshaped stroke whose steps no longer fit a delta word gets points in between.
                ?: SdocxStrokeCodec.encodable(stroke.points).singleOrNull()?.let { points ->
                    runCatching { encode(item, points) }.getOrNull()
                }
                ?: return null
            val (points, payload) = encoded
            if (payload.contentEquals(entry.record.copyOfRange(base, entry.record.size))) return null
            cover(points, stroke.penSize)
            return ObjectEntry(
                objectType = entry.objectType,
                childCount = entry.childCount,
                record = SdocxStrokeCodec.rewrite(entry.record, points, payload, nowUs),
                originalHash = entry.originalHash,
                uuid = entry.uuid,
                modifiedTime = nowUs,
                children = entry.children,
            )
        }

        private fun encode(item: SdocxPageStroke, points: List<SdocxStrokePoint>): Pair<List<SdocxStrokePoint>, ByteArray> {
            val stroke = if (points === item.stroke.points) item.stroke else SdocxStrokeCodec.withPoints(item.stroke, points)
            val pen = item.pen
            val payload = if (pen != null || stroke.raw == null) {
                val profile = pen ?: SdocxPens.PEN
                val penKey = strings.penKey(profile)
                SdocxStrokeCodec.encodeNew(stroke, profile, penKey + 1, penKey)
            } else {
                SdocxStrokeCodec.encodePayload(stroke)
            }
            return points to payload
        }

        private fun newObjects(item: SdocxPageStroke): List<ObjectEntry> {
            val profile = item.pen ?: SdocxPens.PEN
            val penNameId = strings.penKey(profile)
            val advancedId = penNameId + 1
            return SdocxStrokeCodec.encodable(item.stroke.points).map { points ->
                val stroke = SdocxStroke(points, item.stroke.colour, item.stroke.penSize, item.stroke.toolType)
                val uuid = newUuid()
                cover(points, stroke.penSize)
                ObjectEntry(
                    objectType = SdocxStrokeCodec.OBJECT_TYPE_STROKE,
                    childCount = 0,
                    record = SdocxStrokeCodec.newRecord(
                        points = points,
                        payload = SdocxStrokeCodec.encodeNew(stroke, profile, advancedId, penNameId),
                        uuid = uuid,
                        modifiedTimeUs = nowUs,
                        ownerPageWidth = page.width,
                        ownerPageHeight = page.height,
                        objFormatVersion = template?.objFormatVersion ?: page.formatVersion,
                        attrFlags = template?.attrFlags ?: 0x01BE,
                    ),
                    originalHash = ByteArray(32),
                    uuid = uuid,
                    modifiedTime = nowUs,
                    children = emptyList(),
                )
            }
        }

        /** Grows the page's ink bounds by a stroke: its point box widened by the pen size plus a margin. */
        private fun cover(points: List<SdocxStrokePoint>, penSize: Float) {
            val box = SdocxStrokeCodec.boundsOf(points)
            val pad = penSize.toDouble() + DRAWN_RECT_MARGIN
            val grown = RectF64(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad)
            inkBounds = inkBounds?.let(grown::union) ?: grown
        }
    }

    /** Space the reference app leaves around the ink in a page's drawn rect, beyond the pen size (approximate). */
    private const val DRAWN_RECT_MARGIN = 4.0
}

internal fun RectF64.union(other: RectF64): RectF64 = RectF64(
    minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom),
)

/** Edits of the page property block (core spec §5.3), which is not self-delimiting and so is walked. */
internal object PageProperties {

    /**
     * [page] after its ink changed: the drawn rect (bit 0, values stored as float-precision doubles)
     * covers [inkBounds] as well, and with [dropCanvasCache] the canvas cache map (bit 10) is removed.
     */
    fun afterInkChange(page: PageRecord, inkBounds: RectF64?, dropCanvasCache: Boolean): PageRecord {
        var raw = page.properties.raw
        var mask = page.propMask
        if (dropCanvasCache && mask and 0x400 != 0) {
            val range = canvasCacheRange(raw, mask) ?: return page
            raw = raw.copyOfRange(0, range.first) + raw.copyOfRange(range.second, raw.size)
            mask = mask and 0x400.inv()
        }
        if (inkBounds != null) {
            val rect = page.properties.drawnRect?.union(inkBounds) ?: inkBounds
            val bytes = LeWriter(32).apply {
                f64(rect.left.toFloat().toDouble()); f64(rect.top.toFloat().toDouble())
                f64(rect.right.toFloat().toDouble()); f64(rect.bottom.toFloat().toDouble())
            }.toByteArray()
            raw = if (mask and 0x1 != 0) bytes + raw.copyOfRange(32, raw.size) else bytes + raw
            mask = mask or 0x1
        }
        if (mask == page.propMask && raw.contentEquals(page.properties.raw)) return page
        return PageRecord(
            orientation = page.orientation,
            width = page.width,
            height = page.height,
            offsetX = page.offsetX,
            offsetY = page.offsetY,
            pageId = page.pageId,
            modifiedTime = page.modifiedTime,
            formatVersion = page.formatVersion,
            minFormatVersion = page.minFormatVersion,
            pageFlags = page.pageFlags,
            propMask = mask,
            properties = parsePagePropertyBlock(raw, mask, page.formatVersion),
            layers = page.layers,
            originalHash = page.originalHash,
            currentLayerIndex = page.currentLayerIndex,
        )
    }

    /** `[start, end)` of the canvas cache map inside [raw], or null when the block cannot be walked. */
    private fun canvasCacheRange(raw: ByteArray, mask: Int): Pair<Int, Int>? = try {
        val r = LeReader(raw)
        if (mask and 0x1 != 0) r.bytes(32)
        if (mask and 0x2 != 0) repeat(r.u16()) { r.str16() }
        if (mask and 0x4 != 0) r.str16()
        for (bit in intArrayOf(0x8, 0x10, 0x20, 0x40, 0x80)) if (mask and bit != 0) r.i32()
        if (mask and 0x100 != 0) r.bytes(r.u16() * 24)
        if (mask and 0x200 != 0) r.i32()
        val start = r.pos
        val n = r.i32()
        val entrySize = r.u16()
        r.bytes(n * entrySize)
        start to r.pos
    } catch (_: Exception) {
        null
    }
}
