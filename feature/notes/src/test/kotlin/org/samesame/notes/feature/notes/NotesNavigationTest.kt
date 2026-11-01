package org.samesame.notes.feature.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteFilter

class NotesNavigationTest {
    private fun folder(id: String, parentId: String? = null) = Folder(
        id = FolderId(id),
        parentId = parentId?.let(::FolderId),
        name = id,
        color = null,
        sortOrder = 0,
        depth = if (parentId == null) 0 else 1,
        noteCount = 0,
    )

    private val folders = listOf(
        folder("top-a"),
        folder("top-b"),
        folder("child-a", parentId = "top-a"),
        folder("grandchild", parentId = "child-a"),
    )

    @Test
    fun childFolderGoesToItsParentFolder() {
        val result = parentFilterOf(folders, NoteFilter.InFolder(FolderId("child-a")))

        assertThat(result).isEqualTo(NoteFilter.InFolder(FolderId("top-a")))
    }

    @Test
    fun nestedChildGoesOneLevelUpOnly() {
        val result = parentFilterOf(folders, NoteFilter.InFolder(FolderId("grandchild")))

        assertThat(result).isEqualTo(NoteFilter.InFolder(FolderId("child-a")))
    }

    @Test
    fun topLevelFolderGoesToAllNotes() {
        val result = parentFilterOf(folders, NoteFilter.InFolder(FolderId("top-a")))

        assertThat(result).isEqualTo(NoteFilter.All)
    }

    @Test
    fun otherFiltersAreUnchanged() {
        for (filter in listOf(NoteFilter.All, NoteFilter.Uncategorized, NoteFilter.Trash)) {
            assertThat(parentFilterOf(folders, filter)).isEqualTo(filter)
        }
    }

    @Test
    fun unknownFolderGoesToAllNotes() {
        val result = parentFilterOf(folders, NoteFilter.InFolder(FolderId("missing")))

        assertThat(result).isEqualTo(NoteFilter.All)
    }
}
