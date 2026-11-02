package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode

/** A locked note shows its real title when it has one; only untitled locked notes read "Locked note". */
class NoteTitleTextTest {
    private fun note(title: String, locked: Boolean) = Note(
        id = NoteId("n"), title = title, previewText = "", folderId = null, isFavorite = false,
        isLocked = locked, pageMode = PageMode.Pages, backgroundColor = 0, createdAt = 0, modifiedAt = 0,
        lastOpenedAt = 0, trashedAt = null,
    )

    @Test fun `an unlocked titled note shows its title`() {
        assertThat(noteTitleText(note("Ideas", locked = false), untitled = "1/1", locked = "Locked note"))
            .isEqualTo("Ideas")
    }

    @Test fun `a locked titled note shows its title, not the lock placeholder`() {
        assertThat(noteTitleText(note("Ideas", locked = true), untitled = "1/1", locked = "Locked note"))
            .isEqualTo("Ideas")
    }

    @Test fun `an untitled locked note shows the lock placeholder`() {
        assertThat(noteTitleText(note("", locked = true), untitled = "1/1", locked = "Locked note"))
            .isEqualTo("Locked note")
        assertThat(noteTitleText(note("   ", locked = true), untitled = "1/1", locked = "Locked note"))
            .isEqualTo("Locked note")
    }

    @Test fun `an untitled unlocked note gets the date default, not the lock placeholder`() {
        assertThat(noteTitleText(note("", locked = false), untitled = "1/1", locked = "Locked note"))
            .isEqualTo("1/1")
    }
}
