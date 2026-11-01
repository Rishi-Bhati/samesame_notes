package org.samesame.notes.core.sdocx.io

/**
 * Little-endian reader over a byte array, per `sdocx-format-spec.md` §0.1. All multi-byte numbers
 * are little-endian. Strings come in three encodings (`str16`, `str8`, `uuid36`).
 */
internal class LeReader(private val data: ByteArray, var pos: Int = 0) {

    val remaining: Int get() = data.size - pos
    val size: Int get() = data.size

    fun seek(offset: Int) {
        require(offset in 0..data.size) { "seek out of range: $offset (size ${data.size})" }
        pos = offset
    }

    private fun need(n: Int) {
        // `n <= size - pos` rather than `pos + n <= size`: the sum can overflow for hostile lengths.
        require(n >= 0 && n <= data.size - pos) { "truncated: need $n at $pos of ${data.size}" }
    }

    /**
     * A record count read from the file, checked before anything is allocated for it: negative counts read as 0
     * (as before), and a count whose records could not fit in the bytes left ([minBytesEach] each) is rejected.
     */
    fun count(n: Int, minBytesEach: Int): Int {
        if (n <= 0) return 0
        require(n <= remaining / minBytesEach) { "count $n cannot fit in $remaining bytes" }
        return n
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun i8(): Int {
        need(1)
        return data[pos++].toInt()
    }

    fun u16(): Int {
        need(2)
        val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun i16(): Int {
        val v = u16()
        return if (v >= 0x8000) v - 0x10000 else v
    }

    fun i32(): Int {
        need(4)
        val v = (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        pos += 8
        return v
    }

    fun f32(): Float = Float.fromBits(i32())

    fun f64(): Double = Double.fromBits(i64())

    fun bytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** `str16`: `i16` code-unit count. `-1` = null (absent). `0` = empty. */
    fun str16(): String? {
        val n = i16()
        if (n == -1) return null
        require(n >= 0) { "invalid str16 length $n" }
        return String(bytes(n * 2), Charsets.UTF_16LE)
    }

    /** `str8`: `u16` byte count + UTF-8 bytes. */
    fun str8(): String {
        val n = u16()
        return String(bytes(n), Charsets.UTF_8)
    }

    /** `uuid36`: `u16` declared length + ASCII bytes; stops at the first NUL, truncates to 36. */
    fun uuid36(): String {
        val n = u16()
        val raw = bytes(n.coerceAtLeast(0))
        val nul = raw.indexOf(0)
        val end = if (nul >= 0) nul else raw.size
        return String(raw, 0, end, Charsets.US_ASCII).take(36)
    }

    /**
     * "Sized integer" (§0.2): `u8 width` then `width` little-endian bytes, read unsigned.
     * Widths > 8 are not expected; the extra bytes are read but only the low 8 contribute.
     */
    fun sizedInt(): SizedInt {
        val width = u8()
        var value = 0L
        val rawArray = ByteArray(width)
        for (i in 0 until width) {
            val b = u8()
            rawArray[i] = b.toByte()
            if (i < 8) value = value or (b.toLong() shl (8 * i))
        }
        return SizedInt(width, value, rawArray)
    }
}

/** A "sized integer" field: the on-disk width byte plus the value and its raw bytes. */
data class SizedInt(val width: Int, val value: Long, val raw: ByteArray) {
    fun hasBit(bit: Int): Boolean = (value and (1L shl bit)) != 0L
    fun bit(bit: Int): Boolean = hasBit(bit)
}

/** Little-endian writer over a growable byte array, with random-access patching of `i32`/`u16`. */
internal class LeWriter(initialCapacity: Int = 64) {
    private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
    var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    private fun put(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun setByte(offset: Int, v: Int) { buf[offset] = v.toByte() }

    fun patchI32(offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    fun patchU16(offset: Int, v: Int) {
        buf[offset] = (v and 0xFF).toByte()
        buf[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    fun u8(v: Int) = put(v)
    fun i8(v: Int) = put(v)

    fun u16(v: Int) {
        put(v and 0xFF)
        put((v ushr 8) and 0xFF)
    }

    fun i16(v: Int) = u16(v)

    fun i32(v: Int) {
        put(v and 0xFF)
        put((v ushr 8) and 0xFF)
        put((v ushr 16) and 0xFF)
        put((v ushr 24) and 0xFF)
    }

    fun i64(v: Long) {
        for (i in 0 until 8) put(((v ushr (8 * i)) and 0xFF).toInt())
    }

    fun f32(v: Float) = i32(v.toRawBits())
    fun f64(v: Double) = i64(v.toRawBits())

    fun bytes(b: ByteArray) {
        ensure(b.size)
        System.arraycopy(b, 0, buf, size, b.size)
        size += b.size
    }

    fun str16(v: String?) {
        if (v == null) {
            i16(-1)
            return
        }
        val units = v.toCharArray()
        i16(units.size)
        for (c in units) {
            put(c.code and 0xFF)
            put((c.code ushr 8) and 0xFF)
        }
    }

    fun str8(v: String) {
        val b = v.toByteArray(Charsets.UTF_8)
        u16(b.size)
        bytes(b)
    }

    /** Writes `uuid36`: declared length 36, ASCII bytes, zero-padded to 36 (38 bytes total). */
    fun uuid36(v: String) {
        val b = v.toByteArray(Charsets.US_ASCII)
        u16(36)
        val pad = ByteArray(36)
        System.arraycopy(b, 0, pad, 0, minOf(36, b.size))
        bytes(pad)
    }

    fun sizedInt(width: Int, value: Long) {
        u8(width)
        for (i in 0 until width) put(((value ushr (8 * i)) and 0xFF).toInt())
    }

    /** Re-emits a captured [SizedInt] byte-for-byte (preserves unusual widths). */
    fun sizedIntRaw(v: SizedInt) {
        u8(v.width)
        bytes(v.raw)
    }
}
