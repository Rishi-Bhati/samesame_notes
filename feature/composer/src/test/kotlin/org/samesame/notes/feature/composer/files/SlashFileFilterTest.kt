package org.samesame.notes.feature.composer.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile

class SlashFileFilterTest {
    private fun file(name: String, tags: List<String> = emptyList(), description: String = "", created: Long = 0) = LibraryFile(
        id = FileId(name), folderId = null, displayName = name, ext = name.substringAfterLast('.', ""),
        mimeType = "application/pdf", category = FileCategory.Pdf, sizeBytes = 1, sha256 = "0",
        createdAt = created, modifiedAt = created, description = description, tags = tags,
    )

    // Newest first, as observeAllFiles(DateAdded, descending) returns them.
    private val newest = file("holiday-photo.png", tags = listOf("trip"), description = "beach", created = 3)
    private val middle = file("electricity-bill.pdf", tags = listOf("home"), description = "March 2026", created = 2)
    private val oldest = file("workout-mix.mp3", tags = listOf("gym"), description = "morning", created = 1)
    private val files = listOf(newest, middle, oldest)

    @Test fun `blank query keeps newest first`() {
        assertThat(SlashFileFilter.filter(files, "")).containsExactly(newest, middle, oldest).inOrder()
        assertThat(SlashFileFilter.filter(files, "   ")).containsExactly(newest, middle, oldest).inOrder()
    }

    @Test fun `name matches case-insensitively anywhere`() {
        assertThat(SlashFileFilter.filter(files, "BILL")).containsExactly(middle)
        assertThat(SlashFileFilter.filter(files, "o")).containsExactly(newest, middle, oldest).inOrder()
    }

    @Test fun `tags match`() {
        assertThat(SlashFileFilter.filter(files, "gym")).containsExactly(oldest)
    }

    @Test fun `description matches`() {
        assertThat(SlashFileFilter.filter(files, "beach")).containsExactly(newest)
        assertThat(SlashFileFilter.filter(files, "2026")).containsExactly(middle)
    }

    @Test fun `query with spaces is trimmed`() {
        assertThat(SlashFileFilter.filter(files, " mix ")).containsExactly(oldest)
    }

    @Test fun `no match is empty`() {
        assertThat(SlashFileFilter.filter(files, "zzz")).isEmpty()
    }
}
