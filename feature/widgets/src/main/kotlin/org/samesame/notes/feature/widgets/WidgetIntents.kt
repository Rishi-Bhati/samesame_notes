package org.samesame.notes.feature.widgets

import android.content.Context
import android.content.Intent
import org.samesame.notes.core.model.FolderId

/** What a creator-widget button starts in a new note. The names are the values of [WidgetIntents.EXTRA_START_ACTION]. */
enum class StartAction(val key: String) {
    Keypad("keypad"),
    Pen("pen"),
    Brush("brush"),
    Image("image"),
    Voice("voice"),
    /** Take a photo straight into the new note (the editor's camera). */
    Camera("camera"),
    /** A new Markdown note (opens in the text editor, not the canvas). */
    Markdown("markdown"),
    ;

    companion object {
        fun fromKey(key: String?): StartAction? = entries.firstOrNull { it.key == key }
    }
}

/** Intents the widgets fire. The editor activity is addressed by class name: this module never depends on `:app`. */
object WidgetIntents {
    /** Same values as `ComposerActivity` (app module). */
    const val EXTRA_NOTE_ID = "org.samesame.notes.extra.NOTE_ID"
    const val EXTRA_START_ACTION = "org.samesame.notes.extra.START_ACTION"
    private const val COMPOSER_CLASS = "org.samesame.notes.ComposerActivity"
    private const val MAIN_CLASS = "org.samesame.notes.MainActivity"

    fun openNote(context: Context, noteId: String): Intent =
        Intent().setClassName(context.packageName, COMPOSER_CLASS).putExtra(EXTRA_NOTE_ID, noteId)

    fun openApp(context: Context): Intent = Intent().setClassName(context.packageName, MAIN_CLASS)

    /** Folder the new note goes into (list and pages widgets showing a folder); absent = no folder. */
    const val EXTRA_FOLDER_ID = "org.samesame.notes.widgets.extra.FOLDER_ID"

    /** The new note starts as a favourite (the "+" of a widget showing favourites). */
    const val EXTRA_FAVORITE = "org.samesame.notes.widgets.extra.FAVORITE"

    /**
     * Creates a new note and opens it in the mode of [action] (handled by [WidgetCreateActivity]), in [folderId] and as a
     * favourite when asked.
     */
    fun createNote(context: Context, action: StartAction, folderId: FolderId? = null, favorite: Boolean = false): Intent =
        Intent(context, WidgetCreateActivity::class.java)
            .putExtra(EXTRA_START_ACTION, action.key)
            .apply {
                if (folderId != null) putExtra(EXTRA_FOLDER_ID, folderId.value)
                if (favorite) putExtra(EXTRA_FAVORITE, true)
            }

    /** The editor for a note that was just created, started in the mode of [action]. */
    fun openNewNote(context: Context, noteId: String, action: StartAction): Intent =
        openNote(context, noteId).putExtra(EXTRA_START_ACTION, action.key)
}
