package org.samesame.notes.core.model.io

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/** Thrown when untrusted input is larger than the limit it is read under. */
class InputTooLargeException(message: String) : IOException(message)

/**
 * Reads at most [limit] bytes from [input] and fails with [InputTooLargeException] past that, instead of
 * trusting a size declared by the input (a ZIP header, a length prefix) or reading an endless stream.
 * [close] closes [input] only when [closeInput] is true (zip entries must not close their archive).
 */
class LimitedInputStream(
    input: InputStream,
    private val limit: Long,
    private val what: String = "input",
    private val closeInput: Boolean = true,
) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) advance(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        // Read one byte past the limit at most, so an input of exactly [limit] bytes still passes.
        val allowed = (limit - count + 1).coerceAtLeast(1).coerceAtMost(len.toLong()).toInt()
        val n = super.read(b, off, allowed)
        if (n > 0) advance(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n.coerceAtMost(limit - count + 1).coerceAtLeast(0))
        if (skipped > 0) advance(skipped)
        return skipped
    }

    override fun markSupported(): Boolean = false

    override fun close() {
        if (closeInput) super.close()
    }

    private fun advance(n: Long) {
        count += n
        if (count > limit) throw InputTooLargeException("$what is larger than $limit bytes")
    }
}

/** Reads the whole stream, failing once it exceeds [limit] bytes (the stream is not closed). */
fun InputStream.readBytesLimited(limit: Long, what: String = "input"): ByteArray =
    LimitedInputStream(this, limit, what, closeInput = false).readBytes()
