package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteFilter

class DrawerEntriesTest {

    @Test
    fun bothHiddenWhenZero() {
        assertThat(drawerEntries(favoritesCount = 0, lockedCount = 0))
            .containsExactly(DrawerEntry.All, DrawerEntry.Trash, DrawerEntry.Folders)
    }

    @Test
    fun favoritesShownOnlyWhenPositive() {
        assertThat(drawerEntries(favoritesCount = 2, lockedCount = 0))
            .containsExactly(DrawerEntry.All, DrawerEntry.Favorites, DrawerEntry.Trash, DrawerEntry.Folders)
    }

    @Test
    fun lockedShownOnlyWhenPositive() {
        assertThat(drawerEntries(favoritesCount = 0, lockedCount = 1))
            .containsExactly(DrawerEntry.All, DrawerEntry.Locked, DrawerEntry.Trash, DrawerEntry.Folders)
    }

    @Test
    fun bothSitRightAfterAll() {
        assertThat(drawerEntries(favoritesCount = 2, lockedCount = 3))
            .containsExactly(
                DrawerEntry.All, DrawerEntry.Favorites, DrawerEntry.Locked, DrawerEntry.Trash, DrawerEntry.Folders,
            ).inOrder()
    }

    @Test
    fun negativeCountsStayHidden() {
        assertThat(drawerEntries(favoritesCount = -1, lockedCount = -1))
            .containsExactly(DrawerEntry.All, DrawerEntry.Trash, DrawerEntry.Folders)
    }

    @Test
    fun headerTitlesCoverFavoritesAndLocked() {
        assertThat(headerTitleRes(NoteFilter.All)).isEqualTo(R.string.feature_notes_all)
        assertThat(headerTitleRes(NoteFilter.Uncategorized)).isEqualTo(R.string.feature_notes_folders)
        assertThat(headerTitleRes(NoteFilter.Trash)).isEqualTo(R.string.feature_notes_trash)
        assertThat(headerTitleRes(NoteFilter.Favorites)).isEqualTo(R.string.feature_notes_favorites)
        assertThat(headerTitleRes(NoteFilter.Locked)).isEqualTo(R.string.feature_notes_locked)
    }

    @Test
    fun folderTitleIsDynamicAndTagFallsBackToAllNotes() {
        assertThat(headerTitleRes(NoteFilter.InFolder(FolderId("f")))).isNull()
        assertThat(headerTitleRes(NoteFilter.WithTag("work"))).isEqualTo(R.string.feature_notes_all)
    }

    @Test
    fun emptyTextsArePerFilter() {
        assertThat(emptyTitleRes(NoteFilter.All)).isEqualTo(R.string.feature_notes_empty_title)
        assertThat(emptyTitleRes(NoteFilter.Favorites)).isEqualTo(R.string.feature_notes_empty_favorites_title)
        assertThat(emptyTitleRes(NoteFilter.Locked)).isEqualTo(R.string.feature_notes_empty_locked_title)
        assertThat(emptyHintRes(NoteFilter.All)).isEqualTo(R.string.feature_notes_empty_hint)
        assertThat(emptyHintRes(NoteFilter.Favorites)).isEqualTo(R.string.feature_notes_empty_favorites_hint)
        assertThat(emptyHintRes(NoteFilter.Locked)).isEqualTo(R.string.feature_notes_empty_locked_hint)
        assertThat(emptyHintRes(NoteFilter.Trash)).isNull()
    }
}
