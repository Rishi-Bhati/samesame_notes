package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.SortDirection
import org.samesame.notes.core.model.SortField
import org.samesame.notes.core.model.SortOrder
import org.samesame.notes.core.model.UserPreferences
import org.samesame.notes.core.model.ViewMode

class SortOrderTest {
    private fun note(id: String, title: String = id, modified: Long = 0, created: Long = 0, fav: Boolean = false) = Note(
        id = NoteId(id), title = title, previewText = "", folderId = null as FolderId?, isFavorite = fav,
        isLocked = false, pageMode = PageMode.Infinite, backgroundColor = 0, createdAt = created,
        modifiedAt = modified, lastOpenedAt = 0, trashedAt = null,
    )

    private fun ids(order: SortOrder, vararg notes: Note) = notes.sortedWith(order.comparator()).map { it.id.value }

    @Test
    fun `date modified descending puts newest first`() {
        assertThat(ids(SortOrder(), note("a", modified = 1), note("b", modified = 3), note("c", modified = 2)))
            .containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `title ascending ignores case`() {
        val order = SortOrder(SortField.Title, SortDirection.Ascending)
        assertThat(ids(order, note("1", "banana"), note("2", "Apple"))).containsExactly("2", "1").inOrder()
    }

    @Test
    fun `pinned favourites come first regardless of direction`() {
        val order = SortOrder(SortField.DateModified, SortDirection.Ascending, favoritesFirst = true)
        assertThat(ids(order, note("a", modified = 1), note("b", modified = 2, fav = true), note("c", modified = 0)))
            .containsExactly("b", "c", "a").inOrder()
    }

    @Test
    fun `preferences map to a sort order`() {
        val prefs = UserPreferences(ViewMode.List, SortField.Title, SortDirection.Ascending, pinFavoritesToTop = true)
        assertThat(prefs.sortOrder).isEqualTo(SortOrder(SortField.Title, SortDirection.Ascending, true))
        assertThat(UserPreferences().viewMode).isEqualTo(ViewMode.GridLarge)
    }

    @Test
    fun `unknown stored enum names fall back to the default`() {
        assertThat(enumOrDefault("Bogus", ViewMode.GridLarge)).isEqualTo(ViewMode.GridLarge)
        assertThat(enumOrDefault(null, SortField.DateModified)).isEqualTo(SortField.DateModified)
        assertThat(enumOrDefault("List", ViewMode.GridLarge)).isEqualTo(ViewMode.List)
    }
}
