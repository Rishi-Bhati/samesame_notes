package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.RectF64
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One point of a stroke payload, in `.sdocx` page units (px at the capture zoom; spec objects §4.3.1). */
data class SdocxStrokePoint(val x: Double, val y: Double)

/**
 * A decoded (or newly built) type-1 stroke. [raw] is present for a stroke loaded from a file and holds
 * every field that is not fully understood; a new stroke has none and is written from a
 * [SdocxPenProfile].
 */
class SdocxStroke(
    val points: List<SdocxStrokePoint>,
    val colour: Long,
    val penSize: Float,
    val toolType: Int = SdocxStrokeCodec.TOOL_SPEN,
    val raw: SdocxStrokeRaw? = null,
) {
    val isFromFile: Boolean get() = raw != null

    /** Index into the note's string table of this stroke's pen name; null for a new stroke. */
    val penNameId: Int? get() = raw?.style?.penNameId
}

/**
 * The stroke style block. It starts with four fixed words and continues with fields selected by the
 * style flag byte of the payload header (see [SdocxStrokeCodec]); those are kept as [rest].
 *
 * Verified on the device fixtures (five pens, three sizes, three colours, highlighter):
 * [advancedId] and [penNameId] are ids into the note's string table (the pen's advanced-setting
 * string and its class name), [colour] is ARGB and [penSize] the stroke width in page units.
 */
class SdocxStrokeStyle(
    val advancedId: Int,
    val colour: Long,
    val penSize: Float,
    val penNameId: Int,
    /** Everything after the first 16 bytes, verbatim. */
    val rest: ByteArray,
)

/**
 * Everything in a stroke payload that is not a point, the colour or the size, kept exactly as read.
 * A rewrite of an imported stroke copies these; it never invents a value for them.
 *
 * [deltaWords] holds the raw `(dx, dy)` words for the points after the first, so an unchanged stroke
 * re-encodes to identical bytes even where the page coordinates are not exactly representable in 1/32.
 */
class SdocxStrokeRaw(
    val version: Int,
    /** The seven bytes after the tool type: a flag word (4 bytes) and the style flags (3 bytes). */
    val header: ByteArray,
    /** Raw bits of the `f32` after the point deltas (1.0 for synthetic input). */
    val scaleBits: Int,
    /** The `8n + 6` bytes between that word and the style block (per-point data, not understood). */
    val auxArea: ByteArray,
    val style: SdocxStrokeStyle,
    val deltaWords: IntArray,
)

/**
 * Decodes and encodes the stroke data block of a type-1 object (objects spec §4.3).
 *
 * ```
 * +0  i32 dataLen            = 42 + 12n + styleLen
 * +4  u16 version
 * +6  i32 styleOffset        = 42 + 12n
 * +10 u8  toolType
 * +11 4 bytes flag word      25 00 04 8E for synthetic strokes, 65 .. for a hand-drawn one
 * +15 3 bytes style flags    bit 0: second size word, bit 2: an i32, bit 5: a trailing f32
 * +18 u16 n
 * +20 f64 x0, f64 y0
 * +36 (n-1) x {u16 dx, u16 dy}   1/32 page unit, sign-magnitude, zero stored as 0x8000
 *     f32 scale
 *     8n + 6 bytes               per-point area (zeros, last "aux" word 0x9000, trailer 0,0,1)
 * @styleOffset  i32 advancedId, u32 colour, f32 size, i32 penNameId, then the flag-selected fields
 * ```
 *
 * Only the points, colour and size are edited; everything else is carried verbatim, so encoding a
 * stroke decoded from a file reproduces the source bytes exactly. A payload that does not have this
 * shape decodes to `null` and its record is kept as raw bytes by every writer.
 */
object SdocxStrokeCodec {

    /** Tool type enum (objects spec §4.3): 0 unknown, 1 finger, 2 pen, 3 mouse, 4 eraser. */
    const val TOOL_UNKNOWN = 0
    const val TOOL_FINGER = 1
    const val TOOL_SPEN = 2
    const val TOOL_MOUSE = 3
    const val TOOL_ERASER = 4

    /** Bytes of a payload before the style block that do not depend on the point count. */
    const val FIXED_SIZE = 42

    /** The fixed head of the style block: advancedId, colour, size, penNameId. */
    const val STYLE_HEAD = 16

    /** Largest point count one record can hold (`u16`). */
    const val MAX_POINTS = 0xFFFF

    /** Largest coordinate step one delta word can hold, in page units. */
    const val MAX_DELTA = 0x7FFF / 32.0

    private const val DELTA_SCALE = 32.0
    private const val ZERO_DELTA = 0x8000

    /** Style flag (payload +15) bit 0: the style block repeats the pen size after the pen name id. */
    private const val STYLE_FLAG_SECOND_SIZE = 0x01

    // ---- object record helpers -------------------------------------------------------------

    /** Object type code of a current stroke (core spec §7). Legacy strokes are type 15 and stay opaque. */
    const val OBJECT_TYPE_STROKE = 1

    private const val BASE_TIME_OFFSET = 60
    private const val BASE_RECT_OFFSET = 68

    /**
     * Size of the object-base block at the start of a record, or 0 when the record does not start
     * with a base block of the usual layout (flag words of width 2 and 4, objects spec §2).
     */
    private fun baseSize(record: ByteArray): Int {
        if (record.size < 108) return 0
        val r = LeReader(record)
        val size = r.i32()
        if (size !in 105..record.size) return 0
        if (r.i16() != 0) return 0
        if (record[10].toInt() != 2 || record[13].toInt() != 4) return 0
        return size
    }

    /** Decodes a type-1 object record (base block + stroke payload). Null for legacy/unknown records. */
    fun decode(entry: ObjectEntry): SdocxStroke? =
        if (entry.objectType == OBJECT_TYPE_STROKE) decode(entry.record) else null

    /**
     * Decodes an object record. The record must be exactly an object base block followed by the
     * stroke payload, which is how a layer entry is stored (the 32-byte hash is not part of [record]).
     */
    fun decode(record: ByteArray): SdocxStroke? {
        val base = baseSize(record)
        if (base <= 0 || base >= record.size) return null
        return decodePayload(record.copyOfRange(base, record.size))
    }

    /** The object-base `rectF64` of a stroke record (objects spec §2, §4.3.1), or null. */
    fun rectOf(record: ByteArray): RectF64? {
        if (baseSize(record) <= 0) return null
        return try {
            val r = LeReader(record, BASE_RECT_OFFSET)
            RectF64(r.f64(), r.f64(), r.f64(), r.f64())
        } catch (_: Exception) {
            null
        }
    }

    /** The base block's format version, attribute flags and optional mask of a record, or null. */
    internal fun baseTemplateOf(record: ByteArray): BaseTemplate? {
        if (baseSize(record) <= 0) return null
        val r = LeReader(record, 11)
        val attr = r.u16()
        r.u8()
        val mask = r.i32()
        return BaseTemplate(objFormatVersion = r.i32(), attrFlags = attr, optMask = mask)
    }

    internal class BaseTemplate(val objFormatVersion: Int, val attrFlags: Int, val optMask: Int)

    /**
     * Rewrites the stroke payload of [original] with [payload], patching the base rect to [points]'
     * bounding box and the object's modified time to [modifiedTimeUs] (a changed object must get a
     * new time or its change token stays the same, core spec §12.1). The rest of the base block is
     * copied unchanged.
     */
    fun rewrite(original: ByteArray, points: List<SdocxStrokePoint>, payload: ByteArray, modifiedTimeUs: Long): ByteArray {
        val base = baseSize(original)
        require(base > 0) { "not a stroke object record" }
        val w = LeWriter(base + payload.size)
        w.bytes(original.copyOfRange(0, base))
        val box = boundsOf(points)
        w.patchI64(BASE_TIME_OFFSET, modifiedTimeUs)
        w.patchF64(BASE_RECT_OFFSET, box.left)
        w.patchF64(BASE_RECT_OFFSET + 8, box.top)
        w.patchF64(BASE_RECT_OFFSET + 16, box.right)
        w.patchF64(BASE_RECT_OFFSET + 24, box.bottom)
        w.bytes(payload)
        return w.toByteArray()
    }

    /**
     * Builds a new type-1 object record shaped like the ones the reference app writes: a 121-byte
     * base block with attribute flags `0x01BE` and the optional append time and owner page size
     * (mask `0x6000`), then [payload]. The caller appends the 32-byte object hash.
     */
    fun newRecord(
        points: List<SdocxStrokePoint>,
        payload: ByteArray,
        uuid: String,
        modifiedTimeUs: Long,
        ownerPageWidth: Int,
        ownerPageHeight: Int,
        objFormatVersion: Int = SdocxFormat.WRITE_FORMAT_VERSION,
        attrFlags: Int = 0x01BE,
    ): ByteArray {
        val box = boundsOf(points)
        val w = LeWriter(160 + payload.size)
        w.i32(121) // blockSize
        w.i16(0)
        w.i32(105) // optOffset
        w.sizedInt(2, attrFlags.toLong())
        w.sizedInt(4, 0x6000)
        w.i32(objFormatVersion)
        w.uuid36(uuid)
        w.i64(modifiedTimeUs)
        w.f64(box.left); w.f64(box.top); w.f64(box.right); w.f64(box.bottom)
        w.i32(0) // timeStamp
        w.u8(0) // resizable
        w.i64(modifiedTimeUs) // optional bit 13: append time
        w.i32(ownerPageWidth) // optional bit 14
        w.i32(ownerPageHeight)
        w.bytes(payload)
        return w.toByteArray()
    }

    // ---- payload ---------------------------------------------------------------------------

    /** Decodes a stroke data block. Null when it is not the layout described on this object. */
    fun decodePayload(payload: ByteArray): SdocxStroke? {
        if (payload.size < FIXED_SIZE + STYLE_HEAD) return null
        return try {
            val r = LeReader(payload)
            val dataLen = r.i32()
            if (dataLen != payload.size) return null
            val version = r.u16()
            val styleOffset = r.i32()
            val toolType = r.u8()
            val header = r.bytes(7)
            val n = r.u16()
            if (n <= 0) return null
            if (styleOffset != FIXED_SIZE + 12 * n) return null
            if (dataLen - styleOffset < STYLE_HEAD) return null

            val x0 = r.f64()
            val y0 = r.f64()
            if (!x0.isFinite() || !y0.isFinite()) return null
            val deltaWords = IntArray(2 * (n - 1)) { r.u16() }
            val scaleBits = r.i32()
            val auxArea = r.bytes(8 * n + 6)
            if (r.pos != styleOffset) return null
            val style = SdocxStrokeStyle(
                advancedId = r.i32(),
                colour = r.i32().toLong() and 0xFFFFFFFFL,
                penSize = r.f32(),
                penNameId = r.i32(),
                rest = r.bytes(r.remaining),
            )
            SdocxStroke(
                points = pointsOf(x0, y0, deltaWords),
                colour = style.colour,
                penSize = style.penSize,
                toolType = toolType,
                raw = SdocxStrokeRaw(version, header, scaleBits, auxArea, style, deltaWords),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encodes a stroke that came from a file, preserving every field of its [SdocxStroke.raw]. The
     * stored delta words are reused while they still describe [SdocxStroke.points] (so a stroke
     * that was only moved keeps them); the colour and size come from the stroke.
     */
    fun encodePayload(stroke: SdocxStroke): ByteArray {
        val raw = requireNotNull(stroke.raw) { "a new stroke is encoded with a pen profile" }
        val n = stroke.points.size
        require(n in 1..MAX_POINTS) { "a stroke record holds 1..$MAX_POINTS points" }
        val deltaWords = raw.deltaWords.takeIf { it.size == 2 * (n - 1) && describes(it, stroke.points) }
            ?: deltaWordsOf(stroke.points)
        val auxArea = raw.auxArea.takeIf { it.size == 8 * n + 6 } ?: defaultAuxArea(n)
        val style = raw.style
        return payload(
            version = raw.version,
            toolType = stroke.toolType,
            header = raw.header,
            points = stroke.points,
            deltaWords = deltaWords,
            scaleBits = raw.scaleBits,
            auxArea = auxArea,
            advancedId = style.advancedId,
            colour = stroke.colour,
            penSize = stroke.penSize,
            penNameId = style.penNameId,
            rest = restFor(raw, stroke.penSize),
        )
    }

    /**
     * Encodes a new stroke with [profile]'s header and style constants. [advancedId] and [penNameId]
     * are the string-table ids of the profile's advanced-setting string and pen class name.
     * [stroke]'s points must already be [encodable].
     */
    fun encodeNew(stroke: SdocxStroke, profile: SdocxPenProfile, advancedId: Int, penNameId: Int): ByteArray {
        val n = stroke.points.size
        require(n in 1..MAX_POINTS) { "a stroke record holds 1..$MAX_POINTS points" }
        return payload(
            version = 1,
            toolType = stroke.toolType,
            header = profile.header,
            points = stroke.points,
            deltaWords = deltaWordsOf(stroke.points),
            scaleBits = 1f.toRawBits(),
            auxArea = defaultAuxArea(n),
            advancedId = advancedId,
            colour = stroke.colour,
            penSize = stroke.penSize,
            penNameId = penNameId,
            rest = profile.styleRest(stroke.penSize),
        )
    }

    private fun payload(
        version: Int,
        toolType: Int,
        header: ByteArray,
        points: List<SdocxStrokePoint>,
        deltaWords: IntArray,
        scaleBits: Int,
        auxArea: ByteArray,
        advancedId: Int,
        colour: Long,
        penSize: Float,
        penNameId: Int,
        rest: ByteArray,
    ): ByteArray {
        val n = points.size
        val styleOffset = FIXED_SIZE + 12 * n
        val dataLen = styleOffset + STYLE_HEAD + rest.size
        val w = LeWriter(dataLen)
        w.i32(dataLen)
        w.u16(version)
        w.i32(styleOffset)
        w.u8(toolType)
        w.bytes(header)
        w.u16(n)
        w.f64(points[0].x)
        w.f64(points[0].y)
        deltaWords.forEach { w.u16(it) }
        w.i32(scaleBits)
        w.bytes(auxArea)
        w.i32(advancedId)
        w.i32(colour.toInt())
        w.f32(penSize)
        w.i32(penNameId)
        w.bytes(rest)
        return w.toByteArray()
    }

    /**
     * The style tail for a (possibly resized) stroke from a file. When the style flags say the block
     * repeats the pen size and the source really had the same value twice, the copy follows the new
     * size; every other byte is the source's.
     */
    private fun restFor(raw: SdocxStrokeRaw, penSize: Float): ByteArray {
        val rest = raw.style.rest
        val hasSecondSize = raw.header.size == 7 && (raw.header[4].toInt() and STYLE_FLAG_SECOND_SIZE) != 0
        if (!hasSecondSize || rest.size < 4) return rest
        val second = LeReader(rest).i32()
        if (second != raw.style.penSize.toRawBits() || second == penSize.toRawBits()) return rest
        val w = LeWriter(rest.size)
        w.bytes(rest)
        w.patchI32(0, penSize.toRawBits())
        return w.toByteArray()
    }

    /** The per-point area of a synthetic stroke: zeros, `0x9000` in the last aux word, trailer `0, 0, 1`. */
    private fun defaultAuxArea(n: Int): ByteArray {
        val area = ByteArray(8 * n + 6)
        if (n >= 2) area[2 * (n - 2) + 1] = 0x90.toByte()
        area[area.size - 2] = 1
        return area
    }

    // ---- edit helpers ----------------------------------------------------------------------

    /** A copy moved by ([dx], [dy]); the source's delta words stay valid and are reused on encode. */
    fun translated(stroke: SdocxStroke, dx: Double, dy: Double): SdocxStroke = SdocxStroke(
        points = stroke.points.map { SdocxStrokePoint(it.x + dx, it.y + dy) },
        colour = stroke.colour,
        penSize = stroke.penSize,
        toolType = stroke.toolType,
        raw = stroke.raw,
    )

    /** A copy with a new point list; the source's other fields are preserved. */
    fun withPoints(stroke: SdocxStroke, points: List<SdocxStrokePoint>): SdocxStroke = SdocxStroke(
        points = points,
        colour = stroke.colour,
        penSize = stroke.penSize,
        toolType = stroke.toolType,
        raw = stroke.raw,
    )

    /** A copy with a new colour/size; everything else is preserved. */
    fun withStyle(
        stroke: SdocxStroke,
        colour: Long = stroke.colour,
        penSize: Float = stroke.penSize,
    ): SdocxStroke = SdocxStroke(
        points = stroke.points,
        colour = colour,
        penSize = penSize,
        toolType = stroke.toolType,
        raw = stroke.raw,
    )

    // ---- point encoding --------------------------------------------------------------------

    /** Sign-magnitude 1/32 page unit word; zero is stored as `0x8000` (objects spec §4.3.1). */
    fun deltaValue(word: Int): Double = when {
        word == ZERO_DELTA -> 0.0
        word and 0x8000 != 0 -> -(word and 0x7FFF) / DELTA_SCALE
        else -> word / DELTA_SCALE
    }

    private fun deltaWord(units: Int): Int {
        require(abs(units) <= 0x7FFF) { "stroke point delta does not fit in one 1/32 page-unit word" }
        return if (units > 0) units else ZERO_DELTA or -units
    }

    /**
     * The raw `(dx, dy)` words for [points] after the first. Each word is measured from the position
     * the previous words decode to, so rounding to 1/32 never accumulates along a long stroke.
     */
    fun deltaWordsOf(points: List<SdocxStrokePoint>): IntArray {
        val out = IntArray(2 * (points.size - 1))
        var x = points[0].x
        var y = points[0].y
        for (i in 1 until points.size) {
            val dx = ((points[i].x - x) * DELTA_SCALE).roundToInt()
            val dy = ((points[i].y - y) * DELTA_SCALE).roundToInt()
            out[(i - 1) * 2] = deltaWord(dx)
            out[(i - 1) * 2 + 1] = deltaWord(dy)
            x += dx / DELTA_SCALE
            y += dy / DELTA_SCALE
        }
        return out
    }

    /** True when [words] decode, from the first of [points], to [points] within half a delta unit. */
    private fun describes(words: IntArray, points: List<SdocxStrokePoint>): Boolean {
        var x = points[0].x
        var y = points[0].y
        for (i in 1 until points.size) {
            x += deltaValue(words[(i - 1) * 2])
            y += deltaValue(words[(i - 1) * 2 + 1])
            if (abs(points[i].x - x) > 0.5 / DELTA_SCALE || abs(points[i].y - y) > 0.5 / DELTA_SCALE) return false
        }
        return true
    }

    /**
     * Makes a point list for a new record: a step longer than one delta word can hold gets evenly
     * spaced points in between, the list ends with a repeated last point (every stroke the reference
     * app writes ends with the pen-up event at the last position, which is where the `0x9000` aux
     * word sits), and a list longer than one record can hold is cut into consecutive pieces that
     * share their boundary point.
     */
    fun encodable(points: List<SdocxStrokePoint>): List<List<SdocxStrokePoint>> {
        require(points.isNotEmpty()) { "a stroke needs at least one point" }
        val dense = ArrayList<SdocxStrokePoint>(points.size + 1)
        dense += points[0]
        for (i in 1 until points.size) {
            val a = dense.last()
            val b = points[i]
            val steps = ceil(maxOf(abs(b.x - a.x), abs(b.y - a.y)) / (MAX_DELTA - 1.0)).toInt()
            for (k in 1 until steps) {
                dense += SdocxStrokePoint(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps)
            }
            dense += b
        }
        val pieces = ArrayList<List<SdocxStrokePoint>>()
        var start = 0
        while (true) {
            val end = minOf(dense.size, start + MAX_POINTS - 1)
            val piece = ArrayList(dense.subList(start, end))
            val last = piece.last()
            val words = if (piece.size >= 2) deltaWordsOf(piece.subList(piece.size - 2, piece.size)) else null
            val endsWithRepeat = words != null && words[0] == ZERO_DELTA && words[1] == ZERO_DELTA
            if (!endsWithRepeat) piece += last
            pieces += piece
            if (end >= dense.size) break
            start = end - 1
        }
        return pieces
    }

    private fun pointsOf(x0: Double, y0: Double, deltaWords: IntArray): List<SdocxStrokePoint> {
        val points = ArrayList<SdocxStrokePoint>(deltaWords.size / 2 + 1)
        points += SdocxStrokePoint(x0, y0)
        for (i in deltaWords.indices step 2) {
            val previous = points.last()
            points += SdocxStrokePoint(
                previous.x + deltaValue(deltaWords[i]),
                previous.y + deltaValue(deltaWords[i + 1]),
            )
        }
        return points
    }

    /** Exact bounding box of the points (objects spec §4.3.1). */
    fun boundsOf(points: List<SdocxStrokePoint>): RectF64 {
        require(points.isNotEmpty()) { "a stroke needs at least one point" }
        var left = points[0].x
        var top = points[0].y
        var right = left
        var bottom = top
        for (point in points) {
            if (point.x < left) left = point.x
            if (point.x > right) right = point.x
            if (point.y < top) top = point.y
            if (point.y > bottom) bottom = point.y
        }
        return RectF64(left, top, right, bottom)
    }
}
