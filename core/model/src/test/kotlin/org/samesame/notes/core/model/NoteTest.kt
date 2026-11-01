package org.samesame.notes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NoteTest {
    private val day = 24L * 60 * 60 * 1000
    private val note = Note(
        id = NoteId("n1"),
        title = "",
        previewText = "",
        folderId = null,
        isFavorite = false,
        isLocked = false,
        pageMode = PageMode.Infinite,
        backgroundColor = 0,
        createdAt = 0,
        modifiedAt = 0,
        lastOpenedAt = 0,
        trashedAt = null,
    )

    @Test
    fun `note outside trash has no countdown`() {
        assertThat(note.daysLeftInTrash(now = 10 * day)).isNull()
    }

    @Test
    fun `trash countdown starts at retention period and floors at zero`() {
        val trashed = note.copy(trashedAt = 100 * day)
        assertThat(trashed.daysLeftInTrash(now = 100 * day)).isEqualTo(Limits.TRASH_RETENTION_DAYS)
        assertThat(trashed.daysLeftInTrash(now = 110 * day)).isEqualTo(20)
        assertThat(trashed.daysLeftInTrash(now = 200 * day)).isEqualTo(0)
    }
}
