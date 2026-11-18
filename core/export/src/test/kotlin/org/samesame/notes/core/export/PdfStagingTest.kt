package org.samesame.notes.core.export

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.samesame.notes.core.export.render.PdfExport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class PdfStagingTest {
    @Test fun `failed vector is replaced before copying`() {
        val out = ByteArrayOutputStream()
        PdfExport.writeStaged(out, { it.writeText("partial"); throw IOException("save") }, { it.writeText("complete") })
        assertThat(out.toString()).isEqualTo("complete")
    }

    @Test fun `copy failure never starts fallback`() {
        var fallbacks = 0
        var writes = 0
        val out = object : OutputStream() {
            override fun write(b: Int) { writes++; throw IOException("copy") }
        }
        assertThrows(IOException::class.java) {
            PdfExport.writeStaged(out, { it.writeText("vector") }, { fallbacks++ })
        }
        assertThat(fallbacks).isEqualTo(0)
        assertThat(writes).isEqualTo(1)
    }

    @Test fun `flush failure never starts fallback`() {
        var fallbacks = 0
        val out = object : ByteArrayOutputStream() {
            override fun flush() { throw IOException("flush") }
        }
        assertThrows(IOException::class.java) {
            PdfExport.writeStaged(out, { it.writeText("vector") }, { fallbacks++ })
        }
        assertThat(fallbacks).isEqualTo(0)
        assertThat(out.toString()).isEqualTo("vector")
    }

    @Test fun `failed fallback leaves output untouched`() {
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            PdfExport.writeStaged(out, { throw IOException("vector") }, { it.writeText("partial"); throw IOException("raster") })
        }
        assertThat(out.size()).isEqualTo(0)
    }
}
