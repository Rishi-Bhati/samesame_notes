package org.samesame.notes.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.samesame.notes.core.model.NoteId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which note each single-note home-screen widget shows (`widget id -> note id`), kept in plain preferences outside the
 * backup. The repository reads it to refuse locking a note that is on a widget: a pinned note must never be locked,
 * because a widget would otherwise keep showing (or leak) its content.
 */
@Singleton
class WidgetPinStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Shows [noteId] in the widget [widgetId]. */
    fun pin(widgetId: Int, noteId: NoteId) {
        prefs.edit().putString(key(widgetId), noteId.value).apply()
    }

    /** The widget no longer exists or no longer shows a note. */
    fun unpin(widgetId: Int) {
        prefs.edit().remove(key(widgetId)).apply()
    }

    /** The note shown by [widgetId], or null. */
    fun noteOf(widgetId: Int): NoteId? = prefs.getString(key(widgetId), null)?.let(::NoteId)

    /** Whether any widget shows [noteId]. */
    fun isPinned(noteId: NoteId): Boolean = prefs.all.any { (k, v) -> k.startsWith(PREFIX) && v == noteId.value }

    /** The widget ids that have a note. */
    fun widgetIds(): Set<Int> = prefs.all.keys.mapNotNull { if (it.startsWith(PREFIX)) it.removePrefix(PREFIX).toIntOrNull() else null }.toSet()

    private fun key(widgetId: Int) = PREFIX + widgetId

    private companion object {
        const val FILE = "widget_pins"
        const val PREFIX = "w"
    }
}

/** A note was asked to be locked while a widget shows it (a pinned note can't be locked). */
class NotePinnedToWidgetException(val noteIds: List<NoteId>) :
    IllegalStateException("${noteIds.size} note(s) are shown on a widget and can't be locked")
