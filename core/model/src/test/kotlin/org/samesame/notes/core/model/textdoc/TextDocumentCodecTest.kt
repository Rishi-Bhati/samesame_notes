package org.samesame.notes.core.model.textdoc

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Limits
import java.nio.charset.Charset

class TextDocumentCodecTest {

    @Test
    fun utf8RoundTripsWithoutABom() {
        val bytes = "héllo ✓\nsecond line\n".toByteArray(Charsets.UTF_8)
        val doc = TextDocumentCodec.read(bytes.inputStream())

        assertThat(doc.text).isEqualTo("héllo ✓\nsecond line\n")
        assertThat(doc.encoding).isEqualTo(TextEncoding.Utf8)
        assertThat(doc.lineEnding).isEqualTo(LineEnding.Lf)
        assertThat(doc.hasBom).isFalse()
        assertThat(TextDocumentCodec.roundTripBytes(doc.text, doc.encoding, doc.lineEnding, doc.hasBom)).isEqualTo(bytes)
    }

    @Test
    fun utf8BomIsDetectedAndWrittenBack() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray(Charsets.UTF_8)
        val doc = TextDocumentCodec.read(bytes.inputStream())

        assertThat(doc.text).isEqualTo("hi")
        assertThat(doc.encoding).isEqualTo(TextEncoding.Utf8)
        assertThat(doc.hasBom).isTrue()
        assertThat(TextDocumentCodec.roundTripBytes(doc.text, doc.encoding, doc.lineEnding, doc.hasBom)).isEqualTo(bytes)
    }

    @Test
    fun utf16RoundTripsInBothByteOrders() {
        for (encoding in listOf(TextEncoding.Utf16Le, TextEncoding.Utf16Be)) {
            val bom = if (encoding == TextEncoding.Utf16Le) {
                byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            } else {
                byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            }
            val bytes = bom + "a\r\nb ✓".toByteArray(encoding.charset)
            val doc = TextDocumentCodec.read(bytes.inputStream())

            assertThat(doc.encoding).isEqualTo(encoding)
            assertThat(doc.text).isEqualTo("a\nb ✓")
            assertThat(doc.lineEnding).isEqualTo(LineEnding.Crlf)
            assertThat(TextDocumentCodec.roundTripBytes(doc.text, doc.encoding, doc.lineEnding, doc.hasBom)).isEqualTo(bytes)
        }
    }

    @Test
    fun invalidUtf8FallsBackToWindows1252ForC1Bytes() {
        // “quoted” in windows-1252 uses 0x93/0x94, which are control characters in ISO-8859-1.
        val bytes = byteArrayOf('a'.code.toByte(), 0x93.toByte(), 'q'.code.toByte(), 0x94.toByte(), 0xE9.toByte())
        val doc = TextDocumentCodec.read(bytes.inputStream())

        assertThat(doc.encoding).isEqualTo(TextEncoding.Windows1252)
        assertThat(doc.text).isEqualTo("a“q”é")
        assertThat(TextDocumentCodec.roundTripBytes(doc.text, doc.encoding, doc.lineEnding, doc.hasBom)).isEqualTo(bytes)
    }

    @Test
    fun invalidUtf8WithoutC1BytesIsIso88591() {
        val bytes = "café".toByteArray(Charsets.ISO_8859_1)
        val doc = TextDocumentCodec.read(bytes.inputStream())

        assertThat(doc.encoding).isEqualTo(TextEncoding.Iso88591)
        assertThat(doc.text).isEqualTo("café")
        assertThat(TextDocumentCodec.roundTripBytes(doc.text, doc.encoding, doc.lineEnding, doc.hasBom)).isEqualTo(bytes)
    }

    @Test
    fun dominantLineEndingIsRemembered() {
        val crlf = TextDocumentCodec.read("a\r\nb\nc\r\n".toByteArray().inputStream())
        assertThat(crlf.lineEnding).isEqualTo(LineEnding.Crlf)
        assertThat(crlf.text).isEqualTo("a\nb\nc\n")

        val cr = TextDocumentCodec.read("a\rb\rc\n".toByteArray().inputStream())
        assertThat(cr.lineEnding).isEqualTo(LineEnding.Cr)
        assertThat(cr.text).isEqualTo("a\nb\nc\n")

        val lf = TextDocumentCodec.read("a\nb\n".toByteArray().inputStream())
        assertThat(lf.lineEnding).isEqualTo(LineEnding.Lf)
    }

    @Test
    fun writeUsesTheDetectedLineEnding() {
        val bytes = TextDocumentCodec.roundTripBytes("one\ntwo", TextEncoding.Utf8, LineEnding.Crlf, hasBom = false)
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("one\r\ntwo")
    }

    @Test
    fun truncationCapsTheTextAndMarksTheDocumentReadOnly() {
        val bytes = ByteArray((Limits.MAX_TEXTDOC_EDIT_BYTES + 10).toInt()) { 'a'.code.toByte() }
        val doc = TextDocumentCodec.read(bytes.inputStream())

        assertThat(doc.isTruncated).isTrue()
        assertThat(doc.isReadOnly).isTrue()
        assertThat(doc.text.length.toLong()).isEqualTo(Limits.MAX_TEXTDOC_EDIT_BYTES)
    }

    @Test
    fun truncatedUtf8DropsAPartialTrailingCharacter() {
        val full = "abc✓".toByteArray(Charsets.UTF_8) + 'd'.code.toByte()
        val cap = full.size - 2 // ends inside the three-byte '✓' and leaves one byte over the cap
        val doc = TextDocumentCodec.read(full.inputStream(), maxBytes = cap.toLong())

        assertThat(doc.text).isEqualTo("abc")
        assertThat(doc.encoding).isEqualTo(TextEncoding.Utf8)
    }

    @Test
    fun aFallbackCharsetIsAvailableEverywhere() {
        // Guards the charset lookups: Android ships windows-1252, but a missing one must not crash the editor.
        assertThat(Charset.isSupported(TextEncoding.Windows1252.charsetName) || TextEncoding.Windows1252.charset == Charsets.UTF_8).isTrue()
    }
}
