package org.samesame.notes.core.sdocx

import org.samesame.notes.core.sdocx.io.LeReader
import org.samesame.notes.core.sdocx.io.LeWriter
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.RectF64
import kotlin.math.abs
import kotlin.math.roundToInt

/** One point of a stroke payload, in `.sdocx` page units (px at the capture zoom; spec objects §4.3.1). */
data class SdocxStrokePoint(val x: Double, val y: Double)

/**
 * A decoded (or newly built) type-1 stroke. [raw] is present for a stroke loaded from a file and holds
 * every field the spec does not fully describe; a new stroke has none and is written from the
 * default-pen writer checklist (objects spec §4.3.5).
 */
class SdocxStroke(
    val points: List<SdocxStrokePoint>,
    val colour: Long,
    val penSize: Float,
    val toolType: Int = SdocxStrokeCodec.TOOL_SPEN,
    val raw: SdocxStrokeRaw? = null,
) {
    val isFromFile: Boolean get() = raw != null
}

/**
 * The 28-byte stroke style block (objects spec §4.3.3). [colour] and [penSize] are verified; the other
 * fields are `[C]`/`[?]` and are only ever copied from the source bytes.
 */
data class SdocxStrokeStyle(
    val version: Int,
    val colour: Long,
    val penSize: Float,
    val zero: Float,
    val penSize2: Float,
    val flags: Int,
    val tension: Float,
)

/**
 * Everything in a stroke payload the spec marks `[C]` or `[?]`, kept exactly as read. A rewrite of an
 * imported stroke copies these; it never invents a value for them.
 *
 * [deltaWords] holds the raw `(dx, dy)` words for the points after the first, so an unchanged stroke
 * re-encodes to identical bytes even where the page coordinates are not exactly representable in 1/32.
 */
class SdocxStrokeRaw(
    val version: Int,
    val penHeader: ByteArray,
    val pressureScale: Float,
    /** `(n-1)` raw u16 values (objects spec §4.3.2). */
    val aux: IntArray,
    /** `3n` raw u16 values (objects spec §4.3.2). */
    val mid: IntArray,
    val trailerFirst: Int,
    val trailerSecond: Int,
    val trailerThird: Int,
    val style: SdocxStrokeStyle,
    val deltaWords: IntArray,
) {
    fun withPoints(points: List<SdocxStrokePoint>): SdocxStrokeRaw = SdocxStrokeRaw(
        version = version,
        penHeader = penHeader.copyOf(),
        pressureScale = pressureScale,
        aux = if (points.size - 1 == aux.size) aux.copyOf() else defaultAuxWords(points.size),
        mid = if (points.size * 3 == mid.size) mid.copyOf() else IntArray(points.size * 3),
        trailerFirst = trailerFirst,
        trailerSecond = trailerSecond,
        trailerThird = trailerThird,
        style = style,
        deltaWords = SdocxStrokeCodec.deltaWordsOf(points),
    )
}

/** `(n-1)` auxiliary words: zero everywhere, `0x9000` last (objects spec §4.3.2/§4.3.5). */
private fun defaultAuxWords(n: Int): IntArray {
    val aux = IntArray(n - 1)
    if (aux.isNotEmpty()) aux[aux.size - 1] = 0x9000
    return aux
}

/**
 * Decodes and encodes the stroke data block of a type-1 object (objects spec §4.3).
 *
 * Only the colour and pen size of the style block are understood; the auxiliary area and the other
 * style fields are carried verbatim. Encoding a stroke decoded from a file therefore reproduces the
 * source bytes exactly, and editing the points or the style rewrites only the parts the spec marks
 * `[V]` while preserving everything else.
 *
 * A stroke built without [SdocxStrokeRaw] (a new stroke) is written using the writer checklist of
 * objects spec §4.3.5: default S Pen tool type, the fixture pen header, zero auxiliary data except
 * the last `0x9000` word, and the fixture style constants. That checklist is only verified for
 * default-pen strokes; other pens have to wait for fixtures f05-f08 (objects spec §4.3.4).
 */
object SdocxStrokeCodec {

    /** Tool type of an S Pen stroke (objects spec §4.3, enum 0 unknown, 1 finger, 2 S Pen, 3 mouse, 4 eraser). */
    const val TOOL_UNKNOWN = 0
    const val TOOL_FINGER = 1
    const val TOOL_SPEN = 2
    const val TOOL_MOUSE = 3
    const val TOOL_ERASER = 4

    /** Fixed overhead of a payload: everything before the points, plus the 28-byte style block. */
    const val PAYLOAD_OVERHEAD = 70

    /** Size of the trailing style block. */
    const val STYLE_SIZE = 28

    private const val DELTA_SCALE = 32.0
    private const val ZERO_DELTA = 0x8000

    /** The seven bytes after `toolType`; constant in every fixture, meaning unknown (objects spec §4.3). */
    private val PEN_HEADER = byteArrayOf(0x25, 0x00, 0x04, 0x8E.toByte(), 0x25, 0x00, 0x00)

    // ---- object record helpers -------------------------------------------------------------

    /** Object type code of a current stroke (core spec §7). Legacy strokes are type 15 and stay opaque. */
    const val OBJECT_TYPE_STROKE = 1

    /** Size of the object-base block at the start of a record (0 when it is not a base block). */
    private fun baseSize(record: ByteArray): Int {
        if (record.size < 108) return 0
        val r = LeReader(record)
        val size = r.i32()
        if (size !in 105..record.size) return 0
        if (r.i16() != 0) return 0
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
            val r = LeReader(record, 68)
            RectF64(r.f64(), r.f64(), r.f64(), r.f64())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Rewrites the stroke payload of [original] from [stroke], patching the base rect to the point
     * bounding box. Everything outside the patched rect and the payload is copied unchanged, so the
     * object uuid/time (and therefore its hash) stay valid.
     */
    fun rewrite(original: ByteArray, stroke: SdocxStroke): ByteArray {
        val base = baseSize(original)
        require(base > 0) { "not a stroke object record" }
        val out = original.copyOfRange(0, base)
        val w = LeWriter(out.size)
        w.bytes(out)
        val box = boundsOf(stroke.points)
        w.patchF64(68, box.left)
        w.patchF64(76, box.top)
        w.patchF64(84, box.right)
        w.patchF64(92, box.bottom)
        w.bytes(encodePayload(stroke))
        return w.toByteArray()
    }

    /**
     * Builds a new type-1 object record per objects spec §4.3.5 step 7: base size 121 with optional
     * mask `0x6000` (append time + owner page size). The caller appends the 32-byte object hash.
     */
    fun newRecord(
        stroke: SdocxStroke,
        uuid: String,
        modifiedTimeUs: Long,
        ownerPageWidth: Int,
        ownerPageHeight: Int,
        appendTimeUs: Long = modifiedTimeUs,
        objFormatVersion: Int = 3001,
        attrFlags: Int = 0x01BF,
    ): ByteArray {
        val box = boundsOf(stroke.points)
        val w = LeWriter(256)
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
        w.i64(appendTimeUs) // optional bit 13
        w.i32(ownerPageWidth) // optional bit 14
        w.i32(ownerPageHeight)
        w.bytes(encodePayload(stroke))
        return w.toByteArray()
    }

    // ---- payload ---------------------------------------------------------------------------

    /** Decodes a stroke data block. Null when it is not the §4.3 layout. */
    fun decodePayload(payload: ByteArray): SdocxStroke? {
        if (payload.size < PAYLOAD_OVERHEAD) return null
        return try {
            val r = LeReader(payload)
            val dataLen = r.i32()
            if (dataLen != payload.size) return null
            val version = r.u16()
            val styleOffset = r.i32()
            val toolType = r.u8()
            val penHeader = r.bytes(7)
            val n = r.u16()
            if (n <= 0) return null
            if (dataLen != PAYLOAD_OVERHEAD + 12 * n) return null
            if (styleOffset != dataLen - STYLE_SIZE) return null

            val x0 = r.f64()
            val y0 = r.f64()
            val deltaWords = IntArray(2 * (n - 1))
            repeat(n - 1) {
                deltaWords[it * 2] = r.u16()
                deltaWords[it * 2 + 1] = r.u16()
            }
            val pressureScale = r.f32()
            val aux = IntArray(n - 1) { r.u16() }
            val mid = IntArray(3 * n) { r.u16() }
            val trailerFirst = r.i32()
            val trailerSecond = r.u16()
            val trailerThird = r.u16()
            if (r.pos != styleOffset) return null
            val style = SdocxStrokeStyle(
                version = r.i32(),
                colour = r.i32().toLong() and 0xFFFFFFFFL,
                penSize = r.f32(),
                zero = r.f32(),
                penSize2 = r.f32(),
                flags = r.i32(),
                tension = r.f32(),
            )
            if (r.remaining != 0) return null

            val points = pointsOf(x0, y0, deltaWords)
            SdocxStroke(
                points = points,
                colour = style.colour,
                penSize = style.penSize,
                toolType = toolType,
                raw = SdocxStrokeRaw(
                    version = version,
                    penHeader = penHeader,
                    pressureScale = pressureScale,
                    aux = aux,
                    mid = mid,
                    trailerFirst = trailerFirst,
                    trailerSecond = trailerSecond,
                    trailerThird = trailerThird,
                    style = style,
                    deltaWords = deltaWords,
                ),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Encodes a stroke data block per objects spec §4.3, preserving every `[C]`/`[?]` field of [stroke]. */
    fun encodePayload(stroke: SdocxStroke): ByteArray {
        val points = stroke.points
        require(points.isNotEmpty()) { "a stroke needs at least one point" }
        val n = points.size
        val raw = stroke.raw
        val deltaWords = raw?.deltaWords?.takeIf { it.size == 2 * (n - 1) } ?: deltaWordsOf(points)
        val aux = raw?.aux?.takeIf { it.size == n - 1 } ?: defaultAuxWords(n)
        val mid = raw?.mid?.takeIf { it.size == 3 * n } ?: IntArray(3 * n)
        val style = raw?.style?.let {
            it.copy(colour = stroke.colour, penSize = stroke.penSize)
        } ?: SdocxStrokeStyle(
            version = 1,
            colour = stroke.colour,
            penSize = stroke.penSize,
            zero = 0f,
            penSize2 = stroke.penSize,
            flags = 1,
            tension = 1.5f,
        )
        val dataLen = PAYLOAD_OVERHEAD + 12 * n
        val w = LeWriter(dataLen)
        w.i32(dataLen)
        w.u16(raw?.version ?: 1)
        w.i32(dataLen - STYLE_SIZE)
        w.u8(stroke.toolType)
        w.bytes(raw?.penHeader ?: PEN_HEADER)
        w.u16(n)
        w.f64(points[0].x)
        w.f64(points[0].y)
        for (i in 0 until n - 1) {
            w.u16(deltaWords[i * 2])
            w.u16(deltaWords[i * 2 + 1])
        }
        w.f32(raw?.pressureScale ?: 1f)
        aux.forEach { w.u16(it) }
        mid.forEach { w.u16(it) }
        w.i32(raw?.trailerFirst ?: 0)
        w.u16(raw?.trailerSecond ?: 0)
        w.u16(raw?.trailerThird ?: 1)
        w.i32(style.version)
        w.i32(style.colour.toInt())
        w.f32(style.penSize)
        w.f32(style.zero)
        w.f32(style.penSize2)
        w.i32(style.flags)
        w.f32(style.tension)
        return w.toByteArray()
    }

    // ---- edit helpers ----------------------------------------------------------------------

    /** A copy with a new point list; the source's unknown fields are preserved (aux area resized safely). */
    fun withPoints(stroke: SdocxStroke, points: List<SdocxStrokePoint>): SdocxStroke = SdocxStroke(
        points = points,
        colour = stroke.colour,
        penSize = stroke.penSize,
        toolType = stroke.toolType,
        raw = stroke.raw?.withPoints(points),
    )

    /**
     * A copy with a new colour/size. `[V]` style fields are replaced; `[C]`/`[?]` style fields
     * (including the second size word) are preserved.
     */
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

    /** Inverse of [deltaValue]. Throws when a delta cannot be represented in one word. */
    fun deltaWord(value: Double): Int {
        val magnitude = (abs(value) * DELTA_SCALE).roundToInt()
        require(magnitude <= 0x7FFF) { "stroke point delta $value does not fit in one 1/32 page-unit word" }
        return if (value > 0.0) magnitude else ZERO_DELTA or magnitude
    }

    /** The raw `(dx, dy)` words for [points] after the first. */
    fun deltaWordsOf(points: List<SdocxStrokePoint>): IntArray {
        val out = IntArray(2 * (points.size - 1))
        for (i in 1 until points.size) {
            out[(i - 1) * 2] = deltaWord(points[i].x - points[i - 1].x)
            out[(i - 1) * 2 + 1] = deltaWord(points[i].y - points[i - 1].y)
        }
        return out
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
