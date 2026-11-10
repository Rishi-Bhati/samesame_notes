package org.samesame.notes.feature.files.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextContentTest {
    @Test fun decodesUtf8WithAndWithoutBom() {
        assertThat(TextContent.decode("héllo ✓".toByteArray())).isEqualTo("héllo ✓")
        assertThat(TextContent.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray())).isEqualTo("hi")
    }

    @Test fun decodesUtf16ByItsBom() {
        assertThat(TextContent.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hé".toByteArray(Charsets.UTF_16LE))).isEqualTo("hé")
        assertThat(TextContent.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hé".toByteArray(Charsets.UTF_16BE))).isEqualTo("hé")
    }

    @Test fun fallsBackToASingleByteCharset() {
        // 0xE9 alone is not UTF-8; in Windows-1252 it is "é".
        assertThat(TextContent.decode(byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte()))).isEqualTo("café")
    }

    @Test fun aCutOffFileStaysUtf8() {
        val bytes = "ab✓".toByteArray() // the tick is three bytes
        val load = TextContent.read(bytes.inputStream(), maxBytes = bytes.size - 1)
        assertThat(load.truncated).isTrue()
        assertThat(load.lines).containsExactly("ab")
    }

    @Test fun readReportsWhetherTheFileWasLonger() {
        assertThat(TextContent.read("abc".byteInputStream(), maxBytes = 3).truncated).isFalse()
        assertThat(TextContent.read("abcd".byteInputStream(), maxBytes = 3)).isEqualTo(TextLoad(listOf("abc"), true))
        assertThat(TextContent.read("".byteInputStream()).lines).containsExactly("")
    }

    @Test fun splitsEveryNewlineStyle() {
        assertThat(TextContent.lines("a\nb\r\nc\rd\n")).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(TextContent.lines("a\n\nb")).containsExactly("a", "", "b").inOrder()
        assertThat(TextContent.lines("a\u0000b")).containsExactly("ab")
    }

    @Test fun longLinesAreCutWithoutSplittingSurrogates() {
        assertThat(TextContent.lines("abcdefg", maxLine = 3)).containsExactly("abc", "def", "g").inOrder()
        val rows = TextContent.lines("ab😀cd", maxLine = 3)
        assertThat(rows.joinToString("")).isEqualTo("ab😀cd")
        assertThat(rows.none { Character.isHighSurrogate(it.last()) }).isTrue()
    }

    @Test fun findIsCaseInsensitiveAndOrdered() {
        val lines = listOf("Foo foo", "bar", "xfOOx")
        assertThat(TextContent.find(lines, "foo")).containsExactly(TextMatch(0, 0, 3), TextMatch(0, 4, 3), TextMatch(2, 1, 3)).inOrder()
        assertThat(TextContent.find(lines, "")).isEmpty()
        assertThat(TextContent.find(lines, "foo", limit = 2)).hasSize(2)
        // Hits don't overlap.
        assertThat(TextContent.find(listOf("aaaa"), "aa")).hasSize(2)
    }

    @Test fun steppingWrapsAround() {
        assertThat(TextContent.step(0, 3, forward = true)).isEqualTo(1)
        assertThat(TextContent.step(2, 3, forward = true)).isEqualTo(0)
        assertThat(TextContent.step(0, 3, forward = false)).isEqualTo(2)
        assertThat(TextContent.step(-1, 3, forward = false)).isEqualTo(2)
        assertThat(TextContent.step(0, 0, forward = true)).isEqualTo(-1)
    }
}
