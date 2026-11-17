package org.samesame.notes.core.model.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZipEntryNamesTest {
    @Test fun `safe strips separators and parent segments`() {
        val name = ZipEntryNames.safe("../../etc/passwd")
        assertThat(name).doesNotContain("/")
        assertThat(name).doesNotContain("\\")
        assertThat(name).isNotEqualTo(".")
        assertThat(name).isNotEqualTo("..")
    }

    @Test fun `safe replaces windows separators, control and bidi characters`() {
        assertThat(ZipEntryNames.safe("a\\b\u0000c\u202Ed")).isEqualTo("a_bcd")
        assertThat(ZipEntryNames.safe("..")).isEqualTo("file")
        assertThat(ZipEntryNames.safe("   ")).isEqualTo("file")
    }

    @Test fun `long names stay within the byte limit and keep the extension`() {
        val name = ZipEntryNames.safe("x".repeat(400) + ".pdf")
        assertThat(FileNames.utf8Length(name)).isAtMost(org.samesame.notes.core.model.Limits.MAX_FILE_NAME_BYTES)
        assertThat(name).endsWith(".pdf")
    }

    @Test fun `unique adds a suffix on clashes before the extension`() {
        val taken = mutableSetOf("photo.jpg")
        assertThat(ZipEntryNames.unique("photo.jpg", taken)).isEqualTo("photo (2).jpg")
        taken += "photo (2).jpg"
        assertThat(ZipEntryNames.unique("photo.jpg", taken)).isEqualTo("photo (3).jpg")
        assertThat(ZipEntryNames.unique("other.jpg", taken)).isEqualTo("other.jpg")
    }

    @Test fun `path sanitises every folder segment`() {
        assertThat(ZipEntryNames.path(listOf("Invoices/2024", ".."), "bill.pdf")).isEqualTo("Invoices_2024/folder/bill.pdf")
        assertThat(ZipEntryNames.path(emptyList(), "note.txt")).isEqualTo("note.txt")
    }

    @Test fun `allocate gives distinct flat paths and keeps repeated ids stable`() {
        val paths = ZipEntryNames.allocate(listOf("a" to "pic.png", "b" to "pic.png", "a" to "other.png", "c" to "../pic.png"))
        assertThat(paths["a"]).isEqualTo("files/pic.png")
        assertThat(paths["b"]).isEqualTo("files/pic (2).png")
        assertThat(paths["c"]).isEqualTo("files/.._pic.png")
        assertThat(paths.values.toSet()).hasSize(3)
        assertThat(paths.values.all { it.startsWith("files/") && "/" !in it.removePrefix("files/") }).isTrue()
    }
}
