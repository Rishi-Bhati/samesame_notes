package org.samesame.notes.core.model.textimport

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextImportTest {
    @Test fun detectsByExtensionThenMime() {
        assertThat(TextImport.isTextFile("a.TXT", null)).isTrue()
        assertThat(TextImport.isTextFile("a.md", "application/octet-stream")).isTrue()
        assertThat(TextImport.isTextFile("a.csv", null)).isTrue()
        assertThat(TextImport.isTextFile("a.jpg", "text/plain")).isFalse()
        assertThat(TextImport.isTextFile("readme", "text/markdown; charset=utf-8")).isTrue()
        assertThat(TextImport.isTextFile("readme", "image/png")).isFalse()
        assertThat(TextImport.isTextFile(null, null)).isFalse()
    }

    @Test fun titleDropsTextExtensionOnly() {
        assertThat(TextImport.titleOf("Shopping list.txt")).isEqualTo("Shopping list")
        assertThat(TextImport.titleOf("a.b.MD")).isEqualTo("a.b")
        assertThat(TextImport.titleOf("noext")).isEqualTo("noext")
        assertThat(TextImport.titleOf(null)).isEmpty()
    }

    @Test fun decodesBoms() {
        assertThat(TextImport.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "héllo".toByteArray())).isEqualTo("héllo")
        assertThat(TextImport.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi é".toByteArray(Charsets.UTF_16LE))).isEqualTo("hi é")
        assertThat(TextImport.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi é".toByteArray(Charsets.UTF_16BE))).isEqualTo("hi é")
    }

    @Test fun invalidBytesBecomeReplacementCharacters() {
        val s = TextImport.decode(byteArrayOf('a'.code.toByte(), 0xC3.toByte(), 'b'.code.toByte()))
        assertThat(s).isEqualTo("a�b")
    }

    @Test fun normalisesLineBreaks() {
        assertThat(TextImport.decode("a\r\nb\rc\nd".toByteArray())).isEqualTo("a\nb\nc\nd")
        assertThat(TextImport.decode(ByteArray(0))).isEmpty()
    }

    @Test fun sizeCap() {
        assertThat(TextImport.isTooLarge(2L * 1024 * 1024)).isFalse()
        assertThat(TextImport.isTooLarge(2L * 1024 * 1024 + 1)).isTrue()
    }
}
