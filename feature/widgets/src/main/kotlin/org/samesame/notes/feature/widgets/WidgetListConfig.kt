package org.samesame.notes.feature.widgets

import android.content.Context
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteFilter

/** What a notes widget shows. */
sealed interface ListSource {
    /** The most recently edited notes of every folder. */
    data object Recent : ListSource

    data object Favorites : ListSource

    data class Folder(val id: FolderId) : ListSource

    /** The catalog filter for this source. */
    fun filter(): NoteFilter = when (this) {
        Recent -> NoteFilter.All
        Favorites -> NoteFilter.Favorites
        is Folder -> NoteFilter.InFolder(id)
    }

    /** Stable string form (configure screen state, preferences). */
    fun key(): String = when (this) {
        Recent -> KEY_RECENT
        Favorites -> KEY_FAVORITES
        is Folder -> FOLDER_PREFIX + id.value
    }

    companion object {
        private const val KEY_RECENT = "recent"
        private const val KEY_FAVORITES = "favorites"
        private const val FOLDER_PREFIX = "folder:"

        fun fromKey(key: String?): ListSource = when {
            key == KEY_FAVORITES -> Favorites
            key != null && key.startsWith(FOLDER_PREFIX) && key.length > FOLDER_PREFIX.length ->
                Folder(FolderId(key.removePrefix(FOLDER_PREFIX)))
            else -> Recent
        }
    }
}

/**
 * Which source each note-list / pages widget shows (`widget id -> source`). The original key (`f<id>` = folder id, no entry =
 * recent notes) is still read, so widgets set up before favourites existed keep their folder.
 */
class WidgetListConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widget_list_config", Context.MODE_PRIVATE)

    fun sourceOf(widgetId: Int): ListSource {
        prefs.getString("s$widgetId", null)?.let { return ListSource.fromKey(it) }
        return prefs.getString("f$widgetId", null)?.let { ListSource.Folder(FolderId(it)) } ?: ListSource.Recent
    }

    fun set(widgetId: Int, source: ListSource) {
        prefs.edit().putString("s$widgetId", source.key()).remove("f$widgetId").apply()
    }

    /** The widget's chosen layout, or [fallback] when none was saved. */
    fun displayOf(widgetId: Int, fallback: NotesDisplay = NotesDisplay.Auto): NotesDisplay =
        NotesDisplay.fromKey(prefs.getString("d$widgetId", null)) ?: fallback

    fun setDisplay(widgetId: Int, display: NotesDisplay) {
        prefs.edit().putString("d$widgetId", display.key).apply()
    }

    fun clear(widgetId: Int) {
        prefs.edit().remove("s$widgetId").remove("f$widgetId").remove("d$widgetId").apply()
    }
}
