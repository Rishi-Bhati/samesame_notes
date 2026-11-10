package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.map.SdocxMapper
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord
import java.util.UUID
import kotlin.math.abs

/**
 * One handwriting stroke as the editor holds it: SameSame page units, a `PenBrushes` family key and
 * no androidx.ink types, so everything that decides what an export writes can run on the JVM. The
 * Android side only converts between this and an ink `Stroke`.
 */
class SdocxInkSample(
    /** The editor's element id; [SdocxInk.surface] derives it from the source object. */
    val id: Long,
    /** `samesame/<PenType>/<pressure|fixed>`. */
    val penKey: String,
    val argb: Long,
    /** Brush size in SameSame page units. */
    val size: Float,
    /** `.sdocx` tool type enum ([SdocxStrokeCodec.TOOL_SPEN], …). */
    val toolType: Int,
    /** `x0, y0, x1, y1, …` in SameSame page units. */
    val xy: FloatArray,
)

/**
 * The handwriting of a page as [SdocxInkSample]s and back (objects spec §4.3, core spec §14).
 *
 * [surface] is what an imported page shows; [pageStrokes] compares what the editor holds now with
 * that baseline and returns the page's strokes for [SdocxStrokeMerge]: a stroke the editor did not
 * change is returned as decoded, so it re-encodes to its source bytes.
 */
object SdocxInk {

    /**
     * Element id of a surfaced object. It is derived from the object's uuid, so the same object
     * gets the same id every time the page is surfaced and edits can be matched back to it.
     */
    fun elementId(pageId: String, uuid: String?, index: Int): Long {
        val seed = uuid ?: "$pageId:$index"
        val value = UUID.nameUUIDFromBytes("sdocx:$seed".toByteArray(Charsets.UTF_8)).mostSignificantBits
        return if (value == 0L) Long.MIN_VALUE else value
    }

    /** A decodable stroke of a page with the id it is surfaced under. */
    class Source(val id: Long, val entry: ObjectEntry, val stroke: SdocxStroke)

    /**
     * Every stroke of [page] the codec can decode, in drawing order (layers bottom to top, a
     * container before its children). Strokes that do not decode are not listed; their records
     * stay in the archive untouched.
     */
    fun sources(page: PageRecord): List<Source> {
        val out = ArrayList<Source>()
        val seen = HashSet<Long>()
        var index = 0
        fun visit(entry: ObjectEntry) {
            val stroke = SdocxStrokeCodec.decode(entry)?.takeIf(::showable)
            if (stroke != null) {
                val id = elementId(page.pageId, entry.uuid, index++)
                // Two objects with one uuid cannot be told apart later; only the first is editable.
                if (entry.uuid != null && seen.add(id)) out += Source(id, entry, stroke)
            }
            entry.children.forEach(::visit)
        }
        page.layers.forEach { layer -> layer.objects.forEach(::visit) }
        return out
    }

    /** Values an ink brush and input batch accept; anything else stays a raw record. */
    private fun showable(stroke: SdocxStroke): Boolean =
        stroke.penSize.isFinite() && stroke.penSize > 0f && stroke.penSize < 10_000f &&
            stroke.points.all { it.x.isFinite() && it.y.isFinite() && abs(it.x) < 1e7 && abs(it.y) < 1e7 }

    /** The pen class name of a stroke, from the note's string table (falls back to the note's last used pen). */
    fun penNameOf(archive: SdocxArchive, stroke: SdocxStroke): String? {
        val id = stroke.penNameId
        val fromTable = archive.note.properties.stringIdTable?.firstOrNull { it.id == id }?.text
        return fromTable?.takeIf { it.isNotEmpty() }
            ?: archive.note.properties.pen?.penName
            ?: archive.note.properties.legacyPen?.penName
    }

    /** The handwriting an imported [page] shows, scaled to SameSame page units. */
    fun surface(archive: SdocxArchive, page: PageRecord): List<SdocxInkSample> {
        val scale = SdocxMapper.geometryScale(archive)
        return sources(page).map { sample(archive, it, scale) }
    }

    private fun sample(archive: SdocxArchive, source: Source, scale: Float): SdocxInkSample {
        val stroke = source.stroke
        val xy = FloatArray(stroke.points.size * 2)
        stroke.points.forEachIndexed { i, p ->
            xy[i * 2] = (p.x * scale).toFloat()
            xy[i * 2 + 1] = (p.y * scale).toFloat()
        }
        return SdocxInkSample(
            id = source.id,
            penKey = SdocxPens.keyFor(penNameOf(archive, stroke)),
            argb = stroke.colour,
            size = stroke.penSize * scale,
            toolType = stroke.toolType,
            xy = xy,
        )
    }

    /**
     * The strokes of [page] as the editor has them now ([current], in z-order), for
     * [SdocxStrokeMerge]. A sample whose id is a surfaced stroke becomes that stroke with the
     * editor's changes applied (moved, reshaped, recoloured, resized, other pen); any other sample is
     * a new stroke. Surfaced strokes missing from [current] are simply not returned, which the merge
     * reads as deleted.
     */
    fun pageStrokes(
        archive: SdocxArchive,
        page: PageRecord,
        current: List<SdocxInkSample>,
        /** Ids the editor was actually given; a surfaced stroke outside this set is kept as it is. Null = all. */
        shown: Set<Long>? = null,
    ): List<SdocxPageStroke> {
        val scale = SdocxMapper.geometryScale(archive)
        val all = sources(page)
        val sources = all.associateBy { it.id }
        val used = HashSet<Long>()
        val out = ArrayList<SdocxPageStroke>(current.size)
        if (shown != null) {
            for (source in all) if (source.id !in shown && used.add(source.id)) out += SdocxPageStroke(source.entry.uuid, source.stroke)
        }
        for (sample in current) {
            if (sample.xy.size < 2) continue
            val source = sources[sample.id]?.takeIf { used.add(it.id) }
            out += if (source == null) newStroke(sample, scale) else edited(source, sample(archive, source, scale), sample, scale)
        }
        return out
    }

    /** All of [samples] as new strokes ([scale] = SameSame page units per `.sdocx` page unit). */
    fun newStrokes(samples: List<SdocxInkSample>, scale: Float): List<SdocxPageStroke> =
        samples.filter { it.xy.size >= 2 }.map { newStroke(it, scale) }

    private fun newStroke(sample: SdocxInkSample, scale: Float): SdocxPageStroke = SdocxPageStroke(
        sourceUuid = null,
        stroke = SdocxStroke(
            points = pointsOf(sample, scale),
            colour = sample.argb and 0xFFFFFFFFL,
            penSize = sample.size / scale,
            // The only tool type seen in files; the others are known from the enum alone.
            toolType = SdocxStrokeCodec.TOOL_SPEN,
        ),
        pen = SdocxPens.profileForKey(sample.penKey),
    )

    private fun pointsOf(sample: SdocxInkSample, scale: Float): List<SdocxStrokePoint> =
        List(sample.xy.size / 2) { i ->
            SdocxStrokePoint((sample.xy[i * 2] / scale).toDouble(), (sample.xy[i * 2 + 1] / scale).toDouble())
        }

    private fun edited(source: Source, shown: SdocxInkSample, now: SdocxInkSample, scale: Float): SdocxPageStroke {
        val uuid = source.entry.uuid
        if (!SdocxPens.sameFamily(shown.penKey, now.penKey)) {
            // Another pen: the header and style constants are the new pen's, so write it as new
            // stroke data under the same object.
            return SdocxPageStroke(uuid, newStroke(now, scale).stroke, SdocxPens.profileForKey(now.penKey))
        }
        var stroke = source.stroke
        val colour = if (now.argb and 0xFFFFFFFFL == shown.argb and 0xFFFFFFFFL) stroke.colour else now.argb and 0xFFFFFFFFL
        val size = if (sameSize(now.size, shown.size)) stroke.penSize else now.size / scale
        if (colour != stroke.colour || size != stroke.penSize) stroke = SdocxStrokeCodec.withStyle(stroke, colour, size)

        when (val move = geometryChange(shown.xy, now.xy)) {
            GeometryChange.None -> Unit
            is GeometryChange.Moved -> stroke = SdocxStrokeCodec.translated(stroke, (move.dx / scale).toDouble(), (move.dy / scale).toDouble())
            GeometryChange.Reshaped -> stroke = SdocxStrokeCodec.withPoints(stroke, pointsOf(now, scale))
        }
        return SdocxPageStroke(uuid, stroke)
    }

    private fun sameSize(a: Float, b: Float): Boolean = abs(a - b) <= 1e-4f * maxOf(abs(a), abs(b), 1f)

    private sealed interface GeometryChange {
        data object None : GeometryChange
        class Moved(val dx: Float, val dy: Float) : GeometryChange
        data object Reshaped : GeometryChange
    }

    /**
     * Compares the points the page was surfaced with against the editor's. The editor stores its ink
     * through a codec that may round coordinates, so points count as unchanged within a tolerance
     * tied to the stroke's extent ([POSITION_TOLERANCE_FRACTION]); a uniform offset beyond it is a
     * move, anything else a new shape.
     */
    private fun geometryChange(shown: FloatArray, now: FloatArray): GeometryChange {
        if (shown.size != now.size) return GeometryChange.Reshaped
        val n = shown.size / 2
        var minX = shown[0]; var maxX = minX; var minY = shown[1]; var maxY = minY
        var sumX = 0.0; var sumY = 0.0
        for (i in 0 until n) {
            val x = shown[i * 2]; val y = shown[i * 2 + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            sumX += now[i * 2] - x
            sumY += now[i * 2 + 1] - y
        }
        val tolerance = maxOf(MIN_POSITION_TOLERANCE, maxOf(maxX - minX, maxY - minY) * POSITION_TOLERANCE_FRACTION)
        val dx = (sumX / n).toFloat()
        val dy = (sumY / n).toFloat()
        for (i in 0 until n) {
            if (abs(now[i * 2] - shown[i * 2] - dx) > tolerance || abs(now[i * 2 + 1] - shown[i * 2 + 1] - dy) > tolerance) {
                return GeometryChange.Reshaped
            }
        }
        return if (abs(dx) <= tolerance && abs(dy) <= tolerance) GeometryChange.None else GeometryChange.Moved(dx, dy)
    }

    /** Smallest position difference, in SameSame page units, that counts as an edit. */
    private const val MIN_POSITION_TOLERANCE = 0.02f

    /** The same as a fraction of the stroke's larger extent (the ink codec stores about 12 bits of it). */
    private const val POSITION_TOLERANCE_FRACTION = 1f / 1024f
}
