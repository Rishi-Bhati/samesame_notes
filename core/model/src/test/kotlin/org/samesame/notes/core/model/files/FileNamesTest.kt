package org.samesame.notes.core.model.files

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.samesame.notes.core.model.Limits

class FileNamesTest {
    @Test fun `separators become underscores, controls and bidi overrides are dropped`() {
        assertThat(FileNames.sanitize("../etc/passwd")).isEqualTo(".._etc_passwd")
        assertThat(FileNames.sanitize("a\\b")).isEqualTo("a_b")
        assertThat(FileNames.sanitize("na\u0000me\u0007\t.txt")).isEqualTo("name.txt")
        assertThat(FileNames.sanitize("line\nbreak .md")).isEqualTo("linebreak.md")
        // "photo<RLO>gpj.exe" would display as "photoexe.jpg"
        assertThat(FileNames.sanitize("photo‮gpj.exe")).isEqualTo("photogpj.exe")
        assertThat(FileNames.sanitize("⁦x⁩‏.png")).isEqualTo("x.png")
    }

    @Test fun `names are NFC and trimmed`() {
        val decomposed = "Café.txt"
        assertThat(FileNames.sanitize("  $decomposed  ")).isEqualTo("Café.txt")
    }

    @Test fun `empty and dot-only names fall back`() {
        assertThat(FileNames.sanitize(null)).isEqualTo("file")
        assertThat(FileNames.sanitize("   ")).isEqualTo("file")
        assertThat(FileNames.sanitize(".")).isEqualTo("file")
        assertThat(FileNames.sanitize("..")).isEqualTo("file")
        assertThat(FileNames.sanitize("\u0000\u0001")).isEqualTo("file")
        assertThat(FileNames.sanitize("", fallback = "image")).isEqualTo("image")
    }

    @Test fun `long names are cut to 255 UTF-8 bytes on a code point boundary keeping the extension`() {
        val ascii = FileNames.sanitize("a".repeat(400) + ".pdf")
        assertThat(FileNames.utf8Length(ascii)).isEqualTo(Limits.MAX_FILE_NAME_BYTES)
        assertThat(ascii).endsWith(".pdf")

        // 3-byte and 4-byte characters: never split, never over the limit
        val cjk = FileNames.sanitize("漢".repeat(200) + ".txt")
        assertThat(FileNames.utf8Length(cjk)).isAtMost(Limits.MAX_FILE_NAME_BYTES)
        assertThat(cjk).endsWith(".txt")
        val emoji = FileNames.sanitize("😀".repeat(100))
        assertThat(FileNames.utf8Length(emoji)).isAtMost(Limits.MAX_FILE_NAME_BYTES)
        assertThat(emoji.codePoints().allMatch { it == 0x1F600 }).isTrue()
    }

    @Test fun `extensions`() {
        assertThat(FileNames.extensionOf("Report.PDF")).isEqualTo("pdf")
        assertThat(FileNames.extensionOf(".bashrc")).isEmpty()
        assertThat(FileNames.extensionOf("noext")).isEmpty()
        assertThat(FileNames.extensionOf("trailing.")).isEmpty()
        assertThat(FileNames.extensionOf("v1.2 final")).isEmpty()
        assertThat(FileNames.baseName("archive.tar.gz")).isEqualTo("archive.tar")
        assertThat(FileNames.withExtension("image", "png")).isEqualTo("image.png")
        assertThat(FileNames.withExtension("image.jpg", "png")).isEqualTo("image.jpg")
        assertThat(FileNames.withExtension("image", "")).isEqualTo("image")
    }

    @Test fun `unique adds a counter before the extension, ignoring case`() {
        assertThat(FileNames.unique("a.png", listOf("b.png"))).isEqualTo("a.png")
        assertThat(FileNames.unique("a.png", listOf("A.PNG"))).isEqualTo("a (2).png")
        assertThat(FileNames.unique("a.png", listOf("a.png", "a (2).png"))).isEqualTo("a (3).png")
        assertThat(FileNames.unique("notes", listOf("notes"))).isEqualTo("notes (2)")
    }

    @Test fun `folder names`() {
        assertThat(FileNames.sanitizeFolderName("  Work/2026 ")).isEqualTo("Work_2026")
        assertThat(FileNames.sanitizeFolderName("x".repeat(80)).length).isEqualTo(Limits.MAX_FILE_FOLDER_NAME_LENGTH)
        assertThrows(IllegalArgumentException::class.java) { FileNames.sanitizeFolderName(" \u0000 ") }
    }
}
