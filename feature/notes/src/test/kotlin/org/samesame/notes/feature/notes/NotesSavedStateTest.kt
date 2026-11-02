package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteFilter

class NotesSavedStateTest {

    @Test
    fun everyFilterRoundTripsThroughItsSavedString() {
        val filters = listOf(
            NoteFilter.All,
            NoteFilter.Uncategorized,
            NoteFilter.Favorites,
            NoteFilter.Locked,
            NoteFilter.Trash,
            NoteFilter.InFolder(FolderId("2f4f-9a")),
            NoteFilter.InFolder(FolderId("2f4f-9a"), includeSubfolders = false),
            NoteFilter.WithTag("work"),
        )
        for (filter in filters) {
            assertThat(decodeFilter(encodeFilter(filter))).isEqualTo(filter)
        }
    }

    @Test
    fun missingOrUnknownValuesFallBackToAllNotes() {
        assertThat(decodeFilter(null)).isEqualTo(NoteFilter.All)
        assertThat(decodeFilter("nonsense")).isEqualTo(NoteFilter.All)
        assertThat(decodeFilter("folder:")).isEqualTo(NoteFilter.All)
    }
}
