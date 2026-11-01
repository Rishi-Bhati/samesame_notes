package org.samesame.notes.core.sdocx

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SdocxSideChannelTest {

    @Test
    fun `encode writes the origin index and the complete raw archive`() {
        val entries = SdocxSideChannel.encode(SdocxFixtures.archive())

        assertThat(entries.keys).containsExactly(
            SdocxSideChannel.ORIGIN_PATH,
            SdocxSideChannel.RAW_ARCHIVE_PATH,
        )
        assertThat(SdocxSideChannel.isSideChannel(SdocxSideChannel.ORIGIN_PATH)).isTrue()
        val origin = entries.getValue(SdocxSideChannel.ORIGIN_PATH).decodeToString()
        assertThat(origin).contains("\"sideChannelVersion\":1")
        assertThat(origin).contains("\"rawArchive\":\"${SdocxSideChannel.RAW_ARCHIVE_PATH}\"")
    }

    @Test
    fun `decode round-trips the archive byte for byte`() {
        val first = SdocxWriter().writeToBytes(SdocxFixtures.archive())
        val entries = SdocxSideChannel.encode(SdocxReader().read(first))

        val restored = SdocxSideChannel.decode(entries)

        assertThat(restored).isNotNull()
        assertThat(SdocxWriter().writeToBytes(restored!!)).isEqualTo(first)
    }

    @Test
    fun `import keeps original ZIP metadata rather than replacing it with writer output`() {
        val canonical = SdocxWriter().writeToBytes(SdocxFixtures.archive())
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setComment("Synthetic external producer")
            ZipInputStream(canonical.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = 1_700_000_000_000L })
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
        val original = output.toByteArray()
        assertThat(original).isNotEqualTo(canonical)
        val entries = SdocxSideChannel.encode(SdocxReader().read(original), original)
        assertThat(entries.getValue(SdocxSideChannel.RAW_ARCHIVE_PATH)).isEqualTo(original)
        assertThat(SdocxSideChannel.decode(entries)).isNotNull()
    }

    @Test
    fun `decode ignores notes without a side channel`() {
        assertThat(SdocxSideChannel.decode(mapOf("document.json" to byteArrayOf(1, 2, 3)))).isNull()
        assertThat(SdocxSideChannel.decode(emptyMap())).isNull()
    }

    @Test
    fun `entriesOf keeps only the sdocx directory`() {
        val all = mapOf(
            "document.json" to byteArrayOf(1),
            "media/a.png" to byteArrayOf(2),
            SdocxSideChannel.ORIGIN_PATH to byteArrayOf(3),
            SdocxSideChannel.RAW_ARCHIVE_PATH to byteArrayOf(4),
        )
        assertThat(SdocxSideChannel.entriesOf(all).keys).containsExactly(
            SdocxSideChannel.ORIGIN_PATH,
            SdocxSideChannel.RAW_ARCHIVE_PATH,
        )
    }
}
