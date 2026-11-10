package org.samesame.notes.core.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.files.FileCategory

class MimeSnifferTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() } + ByteArray(32)
    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val zip = bytes(0x50, 0x4B, 0x03, 0x04)

    @Test fun `content wins over a wrong extension`() {
        val r = MimeSniffer.sniff(png, "jpg")
        assertThat(r.mimeType).isEqualTo("image/png")
        assertThat(r.category).isEqualTo(FileCategory.Image)
    }

    @Test fun `magic bytes without an extension suggest one`() {
        assertThat(MimeSniffer.sniff(png, "").suggestedExt).isEqualTo("png")
        assertThat(MimeSniffer.sniff("%PDF-1.7\n".toByteArray(), "").let { it.mimeType to it.category })
            .isEqualTo("application/pdf" to FileCategory.Pdf)
        assertThat(MimeSniffer.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0), "").suggestedExt).isEqualTo("jpg")
    }

    @Test fun `zip containers are refined by the extension`() {
        assertThat(MimeSniffer.sniff(zip, "docx").category).isEqualTo(FileCategory.Document)
        assertThat(MimeSniffer.sniff(zip, "apk").mimeType).isEqualTo("application/vnd.android.package-archive")
        assertThat(MimeSniffer.sniff(zip, "apk").category).isEqualTo(FileCategory.Other)
        assertThat(MimeSniffer.sniff(zip, "ssnote").category).isEqualTo(FileCategory.Document)
        assertThat(MimeSniffer.sniff(zip, "").category).isEqualTo(FileCategory.Archive)
        // a .png that is a zip is a zip
        assertThat(MimeSniffer.sniff(zip, "png").mimeType).isEqualTo("application/zip")
    }

    @Test fun `iso media brands`() {
        fun ftyp(brand: String) = byteArrayOf(0, 0, 0, 0x18) + "ftyp$brand".toByteArray() + ByteArray(16)
        assertThat(MimeSniffer.sniff(ftyp("heic"), "").mimeType).isEqualTo("image/heic")
        assertThat(MimeSniffer.sniff(ftyp("isom"), "").category).isEqualTo(FileCategory.Video)
        assertThat(MimeSniffer.sniff(ftyp("M4A "), "m4a").category).isEqualTo(FileCategory.Audio)
    }

    @Test fun `text has no magic and falls back on extension, declared type, then content`() {
        val text = "# Title\nhello".toByteArray()
        assertThat(MimeSniffer.sniff(text, "md").category).isEqualTo(FileCategory.Markdown)
        assertThat(MimeSniffer.sniff(text, "json").category).isEqualTo(FileCategory.Text)
        assertThat(MimeSniffer.sniff(text, "", declaredMime = "text/csv; charset=utf-8").mimeType).isEqualTo("text/csv")
        assertThat(MimeSniffer.sniff(text, "").mimeType).isEqualTo("text/plain")
        assertThat(MimeSniffer.sniff(byteArrayOf(1, 0, 2, 0), "").mimeType).isEqualTo(MimeSniffer.OCTET_STREAM)
        assertThat(MimeSniffer.sniff(byteArrayOf(1, 0, 2, 0), "", "application/octet-stream").category).isEqualTo(FileCategory.Other)
    }

    @Test fun `utf-8 cut mid-character still counts as text`() {
        val s = "café".toByteArray()
        assertThat(MimeSniffer.sniff(s.copyOf(s.size - 1), "").mimeType).isEqualTo("text/plain")
    }
}
