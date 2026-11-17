package org.samesame.notes.feature.widgets

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode

class WidgetContentTest {
    private fun note(
        id: String,
        title: String = "t$id",
        modified: Long = 1,
        locked: Boolean = false,
        trashed: Long? = null,
        favorite: Boolean = false,
        folder: String? = null,
    ) = Note(
        id = NoteId(id), title = title, previewText = "secret body", folderId = folder?.let(::FolderId), isFavorite = favorite,
        isLocked = locked, pageMode = PageMode.Pages, backgroundColor = 0, createdAt = 0, modifiedAt = modified, lastOpenedAt = 0, trashedAt = trashed,
    )

    @Test fun `single is not chosen without a pin`() {
        assertThat(WidgetContent.single(pinned = false, note = note("a"))).isEqualTo(SingleNoteState.NotChosen)
    }

    @Test fun `single is gone for a missing or trashed note`() {
        assertThat(WidgetContent.single(true, null)).isEqualTo(SingleNoteState.Gone)
        assertThat(WidgetContent.single(true, note("a", trashed = 5))).isEqualTo(SingleNoteState.Gone)
    }

    @Test fun `single hides a locked note completely`() {
        assertThat(WidgetContent.single(true, note("a", locked = true))).isEqualTo(SingleNoteState.Locked)
    }

    @Test fun `single is ready for a normal note`() {
        assertThat(WidgetContent.single(true, note("a", title = "Plan", modified = 9)))
            .isEqualTo(SingleNoteState.Ready(NoteId("a"), "Plan", 9))
    }

    @Test fun `rows are newest first without trashed notes and limited`() {
        val rows = WidgetContent.rows(listOf(note("a", modified = 1), note("b", modified = 3), note("c", modified = 2, trashed = 1), note("d", modified = 2)), limit = 2)
        assertThat(rows.map { it.noteId.value }).containsExactly("b", "d").inOrder()
    }

    @Test fun `locked rows keep the title and lock flag and never show favourite`() {
        val row = WidgetContent.rows(listOf(note("a", locked = true, favorite = true))).single()
        assertThat(row.locked).isTrue()
        assertThat(row.favorite).isFalse()
    }

    @Test fun `only unlocked notes outside the trash can be pinned`() {
        assertThat(WidgetContent.canPin(note("a"))).isTrue()
        assertThat(WidgetContent.canPin(note("a", locked = true))).isFalse()
        assertThat(WidgetContent.canPin(note("a", trashed = 1))).isFalse()
    }

    @Test fun `signature changes with what the widgets show`() {
        val base = listOf(note("a"), note("b"))
        val s = WidgetContent.signature(base)
        assertThat(WidgetContent.signature(listOf(note("a"), note("b")))).isEqualTo(s)
        assertThat(WidgetContent.signature(listOf(note("a"), note("b", title = "new")))).isNotEqualTo(s)
        assertThat(WidgetContent.signature(listOf(note("a"), note("b", modified = 7)))).isNotEqualTo(s)
        assertThat(WidgetContent.signature(listOf(note("a"), note("b", locked = true)))).isNotEqualTo(s)
        assertThat(WidgetContent.signature(listOf(note("a"), note("b", trashed = 3)))).isNotEqualTo(s)
        assertThat(WidgetContent.signature(listOf(note("a")))).isNotEqualTo(s)
    }
}
