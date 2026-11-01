package org.samesame.notes.core.model.io

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitedInputStreamTest {
    @Test fun `input at the limit passes`() {
        val bytes = ByteArray(1000) { it.toByte() }
        assertEquals(bytes.toList(), ByteArrayInputStream(bytes).readBytesLimited(1000).toList())
    }

    @Test fun `input over the limit fails`() {
        assertThrows(InputTooLargeException::class.java) { ByteArrayInputStream(ByteArray(1001)).readBytesLimited(1000) }
    }

    @Test fun `endless stream fails without reading it all`() {
        val endless = object : InputStream() {
            var reads = 0L
            override fun read(): Int { reads++; return 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { reads += len; return len }
        }
        assertThrows(InputTooLargeException::class.java) { endless.readBytesLimited(10_000) }
        assertTrue("read ${endless.reads} bytes", endless.reads < 1_000_000)
    }

    @Test fun `single byte reads are counted`() {
        val s = LimitedInputStream(ByteArrayInputStream(ByteArray(5)), 3)
        repeat(3) { s.read() }
        assertThrows(InputTooLargeException::class.java) { s.read() }
    }

    @Test fun `close does not close the source when asked not to`() {
        var closed = false
        val src = object : ByteArrayInputStream(ByteArray(1)) { override fun close() { closed = true } }
        LimitedInputStream(src, 10, closeInput = false).close()
        assertEquals(false, closed)
        LimitedInputStream(src, 10).close()
        assertEquals(true, closed)
    }
}
