package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.UserPreferences

class NotesUiStateTest {
    private fun folder(id: String, parentId: String? = null, trashedAt: Long? = null, noteCount: Int = 0) = Folder(
        id = FolderId(id),
        parentId = parentId?.let(::FolderId),
        name = id,
        color = null,
        sortOrder = 0,
        depth = if (parentId == null) 0 else 1,
        noteCount = noteCount,
        trashedAt = trashedAt,
    )

    private fun note(id: String) = Note(
        id = NoteId(id), title = id, previewText = "", folderId = null, isFavorite = false, isLocked = false,
        pageMode = PageMode.Infinite, backgroundColor = 0, createdAt = 0, modifiedAt = 0, lastOpenedAt = 0, trashedAt = null,
    )

    private val folders = listOf(
        folder("top-a"),
        folder("top-b"),
        folder("child-a", parentId = "top-a"),
        folder("child-b", parentId = "top-a"),
        folder("grandchild", parentId = "child-a"),
    )

    /** Folder cards are shown only when [showFolderCards] is on; the default is off. */
    private fun state(
        filter: NoteFilter,
        showFolderCards: Boolean = true,
        notes: List<Note> = emptyList(),
    ) = NotesUiState(
        filter = filter, folders = folders, notes = notes,
        preferences = UserPreferences(showFolderCards = showFolderCards),
    )

    @Test
    fun folderCardsAreHiddenByDefault() {
        assertThat(UserPreferences().showFolderCards).isFalse()
        assertThat(NotesUiState(filter = NoteFilter.All, folders = folders).visibleFolders).isEmpty()
        assertThat(NotesUiState(filter = NoteFilter.InFolder(FolderId("top-a")), folders = folders).visibleFolders).isEmpty()
    }

    @Test
    fun allNotesShowsTopLevelFoldersOnlyWhenShown() {
        val state = state(NoteFilter.All)

        assertThat(state.visibleFolders.map { it.id }).containsExactly(FolderId("top-a"), FolderId("top-b")).inOrder()
    }

    @Test
    fun foldersRootShowsTheSameTopLevelFoldersWhetherOrNotCardsAreShown() {
        for (shown in listOf(true, false)) {
            val state = state(NoteFilter.Uncategorized, showFolderCards = shown)

            assertThat(state.visibleFolders.map { it.id }).containsExactly(FolderId("top-a"), FolderId("top-b")).inOrder()
        }
    }

    @Test
    fun trashShowsOnlyTopmostTrashedFoldersAsCards() {
        val trashed = listOf(
            folder("gone", trashedAt = 1),
            folder("gone-child", parentId = "gone", trashedAt = 1),
        )
        val state = NotesUiState(filter = NoteFilter.Trash, trashedFolders = trashed)

        assertThat(state.visibleFolders).isEmpty()
        assertThat(state.trashFolders.map { it.id }).containsExactly(FolderId("gone"))
    }

    @Test
    fun trashSubtitleCountsTrashedFolderCardsAndNotes() {
        val trashed = listOf(folder("gone", trashedAt = 1))
        val state = NotesUiState(
            filter = NoteFilter.Trash, trashedFolders = trashed, notes = listOf(note("a"), note("b")),
        )

        assertThat(listSubtitle(state)).isEqualTo(ListSubtitle.FoldersAndNotes(folders = 1, notes = 2))
    }

    @Test
    fun selectionCountsFoldersAndNotes() {
        val state = NotesUiState(
            filter = NoteFilter.All, folders = folders, notes = listOf(note("a")),
            preferences = UserPreferences(showFolderCards = true),
            selecting = true, selection = setOf(NoteId("a")), folderSelection = setOf(FolderId("top-a")),
        )

        assertThat(state.selectedCount).isEqualTo(2)
        assertThat(state.allSelected).isFalse()
    }

    @Test
    fun otherFiltersShowNoFolderCards() {
        for (filter in listOf(NoteFilter.Favorites, NoteFilter.Locked, NoteFilter.WithTag("tag"))) {
            assertThat(state(filter).visibleFolders).isEmpty()
        }
    }

    @Test
    fun inFolderShowsDirectChildrenOnlyWhenShown() {
        val state = state(NoteFilter.InFolder(FolderId("top-a")))

        assertThat(state.visibleFolders.map { it.id })
            .containsExactly(FolderId("child-a"), FolderId("child-b")).inOrder()
    }

    @Test
    fun subtitleCountsOnlyTheNotesWhenFolderCardsAreHidden() {
        val notes = listOf(note("a"), note("b"), note("c"))
        val hidden = state(NoteFilter.All, showFolderCards = false, notes = notes)

        assertThat(listSubtitle(hidden)).isEqualTo(ListSubtitle.Notes(3))
    }

    @Test
    fun subtitleCountsFoldersAndNotesWhenFolderCardsAreShown() {
        val notes = listOf(note("a"), note("b"), note("c"))
        val shown = state(NoteFilter.All, showFolderCards = true, notes = notes)

        assertThat(listSubtitle(shown)).isEqualTo(ListSubtitle.FoldersAndNotes(folders = 2, notes = 3))
    }

    @Test
    fun subtitleInFolderCountsOnlyDirectChildFolders() {
        val inFolder = state(NoteFilter.InFolder(FolderId("top-a")), notes = listOf(note("a")))

        assertThat(listSubtitle(inFolder)).isEqualTo(ListSubtitle.FoldersAndNotes(folders = 2, notes = 1))
    }

    @Test
    fun subtitleIsEmptyWhenNothingIsShown() {
        assertThat(listSubtitle(state(NoteFilter.All, showFolderCards = false))).isEqualTo(ListSubtitle.None)
        assertThat(listSubtitle(state(NoteFilter.Trash))).isEqualTo(ListSubtitle.None)
    }
}
