package org.samesame.notes.feature.widgets

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId

/** What the single-note widget shows. Never carries content of a locked note. */
sealed interface SingleNoteState {
    /** No note is chosen yet (the widget was added but not configured). */
    data object NotChosen : SingleNoteState

    /** The note was deleted, trashed or no longer exists. */
    data object Gone : SingleNoteState

    /** The note is locked: a lock symbol only, no title and no page. */
    data object Locked : SingleNoteState

    /** [snippet] is the note's text preview (shown when the page can't be drawn, and on very small widgets). */
    data class Ready(val noteId: NoteId, val title: String, val modifiedAt: Long, val snippet: String) : SingleNoteState
}

/** One row of the note-list / pages widget. A locked row has a title and a lock symbol, nothing else (no snippet, no page). */
data class ListRow(
    val noteId: NoteId,
    val title: String,
    val locked: Boolean,
    val favorite: Boolean,
    val modifiedAt: Long,
    val snippet: String = "",
)

/** Pure mapping from catalog notes to widget states (the widgets themselves only draw these). */
object WidgetContent {

    /** [pinned] is whether a note id is stored for the widget; [note] is its catalog row (null when it is gone). */
    fun single(pinned: Boolean, note: Note?): SingleNoteState = when {
        !pinned -> SingleNoteState.NotChosen
        note == null || note.isInTrash -> SingleNoteState.Gone
        note.isLocked -> SingleNoteState.Locked
        else -> SingleNoteState.Ready(note.id, note.title, note.modifiedAt, snippet(note.previewText))
    }

    /** Newest first, trashed notes dropped, at most [limit] rows; locked notes keep title and lock flag only. */
    fun rows(notes: List<Note>, limit: Int = Limits.WIDGET_LIST_MAX_ROWS): List<ListRow> =
        notes.asSequence()
            .filter { !it.isInTrash }
            .sortedByDescending { it.modifiedAt }
            .take(limit)
            .map {
                ListRow(it.id, it.title, it.isLocked, it.isFavorite && !it.isLocked, it.modifiedAt, if (it.isLocked) "" else snippet(it.previewText))
            }
            .toList()

    /** The preview text as one tidy line: whitespace runs (line breaks included) collapse to one space, capped in length. */
    fun snippet(previewText: String, max: Int = SNIPPET_MAX): String =
        previewText.replace(WHITESPACE, " ").trim().take(max)

    private val WHITESPACE = Regex("\\s+")
    private const val SNIPPET_MAX = 280

    /** Whether a note may be pinned to a single-note widget: not locked and not in the trash. */
    fun canPin(note: Note): Boolean = !note.isLocked && !note.isInTrash

    /**
     * A cheap fingerprint of everything the widgets show (ids, titles, times, lock, trash, folder, favourite): when it
     * is unchanged after a catalog emission nothing needs redrawing.
     */
    fun signature(notes: List<Note>): Int {
        var h = notes.size
        for (n in notes) {
            h = 31 * h + n.id.value.hashCode()
            h = 31 * h + n.title.hashCode()
            h = 31 * h + n.modifiedAt.hashCode()
            h = 31 * h + (n.trashedAt?.hashCode() ?: 0)
            h = 31 * h + (n.folderId?.value?.hashCode() ?: 0)
            h = 31 * h + n.isLocked.hashCode()
            h = 31 * h + n.isFavorite.hashCode()
            h = 31 * h + n.previewText.hashCode()
        }
        return h
    }
}
