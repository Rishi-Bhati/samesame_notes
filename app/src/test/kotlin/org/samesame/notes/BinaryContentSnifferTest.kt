package org.samesame.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BinaryContentSnifferTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun padded(head: ByteArray, size: Int = 200) = head + ByteArray(size) { ('a'.code + it % 26).toByte() }

    @Test
    fun knownMagicNumbersAreBinary() {
        assertThat(BinaryContentSniffer.sniff("%PDF-1.7\n%text-looking".toByteArray())).isEqualTo("application/pdf")
        assertThat(BinaryContentSniffer.sniff(padded(bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A)))).isEqualTo("image/png")
        assertThat(BinaryContentSniffer.sniff(padded(bytes(0xFF, 0xD8, 0xFF, 0xE0)))).isEqualTo("image/jpeg")
        assertThat(BinaryContentSniffer.sniff(padded(bytes('P'.code, 'K'.code, 3, 4)))).isEqualTo("application/zip")
        assertThat(BinaryContentSniffer.sniff("GIF89a......".toByteArray())).isEqualTo("image/gif")
    }

    @Test
    fun nulBytesAreBinary() {
        assertThat(BinaryContentSniffer.isBinary("abc\u0000def".toByteArray())).isTrue()
    }

    @Test
    fun randomLookingBytesAreBinary() {
        val data = ByteArray(4000) { ((it * 131 + 17) % 251 + 1).toByte() } // no NULs, mostly high bytes
        assertThat(BinaryContentSniffer.isBinary(data)).isTrue()
        val controls = ByteArray(1000) { (1 + it % 7).toByte() }
        assertThat(BinaryContentSniffer.isBinary(controls)).isTrue()
    }

    @Test
    fun plainAndMarkdownTextIsText() {
        assertThat(BinaryContentSniffer.sniff("# Title\n\n- one\n- two\r\n\ttabbed\n".toByteArray())).isNull()
        assertThat(BinaryContentSniffer.sniff(ByteArray(0))).isNull()
        // starts with "PK" or "%PDF" without the full signature
        assertThat(BinaryContentSniffer.sniff("PK is a name\nand %PDF is not first".toByteArray())).isNull()
    }

    @Test
    fun utf8TextIsText() {
        val text = "Zażółć gęślą jaźń — 日本語のテキスト — emoji 😀\n".repeat(30)
        assertThat(BinaryContentSniffer.sniff(text.toByteArray(Charsets.UTF_8))).isNull()
        assertThat(BinaryContentSniffer.sniff(bytes(0xEF, 0xBB, 0xBF) + "héllo".toByteArray())).isNull()
    }

    @Test
    fun aMultiByteCharacterCutAtTheSampleEndIsNotInvalid() {
        val text = ("é".repeat(400)).toByteArray(Charsets.UTF_8)
        val cut = text.copyOf(text.size - 1) // splits the last two-byte character
        assertThat(BinaryContentSniffer.sniff(cut)).isNull()
        val emoji = ("x".repeat(20) + "😀").toByteArray(Charsets.UTF_8)
        assertThat(BinaryContentSniffer.sniff(emoji, emoji.size - 2)).isNull()
    }

    @Test
    fun utf16WithBomIsText() {
        val le = bytes(0xFF, 0xFE) + "Hello, world\n".toByteArray(Charsets.UTF_16LE)
        val be = bytes(0xFE, 0xFF) + "Hello, world\n".toByteArray(Charsets.UTF_16BE)
        assertThat(BinaryContentSniffer.sniff(le)).isNull()
        assertThat(BinaryContentSniffer.sniff(be)).isNull()
    }

    @Test
    fun latin1TextIsText() {
        val latin1 = "Café crème brûlée, naïve façade, déjà vu. Müller & Søren.\n".repeat(20).toByteArray(Charsets.ISO_8859_1)
        assertThat(BinaryContentSniffer.sniff(latin1)).isNull()
        assertThat(BinaryContentSniffer.sniff("café".toByteArray(Charsets.ISO_8859_1))).isNull()
    }

    @Test
    fun onlyTheGivenLengthIsLooked_at() {
        val data = "plain text".toByteArray() + ByteArray(50) // trailing NULs beyond the sample
        assertThat(BinaryContentSniffer.sniff(data, 10)).isNull()
        assertThat(BinaryContentSniffer.sniff(data)).isNotNull()
    }
}
