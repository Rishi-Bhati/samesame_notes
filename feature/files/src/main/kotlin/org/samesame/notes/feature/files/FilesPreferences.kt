package org.samesame.notes.feature.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.samesame.notes.core.model.files.FileSort
import org.samesame.notes.core.model.files.FileSortField
import javax.inject.Inject
import javax.inject.Singleton

enum class FilesViewMode { Grid, List }

/** The Files screen's own settings: sort, view mode, last upload/receive destination. */
interface FilesPreferences {
    var sort: FileSort
    var viewMode: FilesViewMode
    /** Folder id of the last share-receive destination ("" = root). */
    var lastFolderId: String?
}

@Singleton
class SharedFilesPreferences @Inject constructor(@ApplicationContext context: Context) : FilesPreferences {
    private val prefs = context.getSharedPreferences("files_ui", Context.MODE_PRIVATE)

    override var sort: FileSort
        get() = FileSort(
            field = prefs.getString(KEY_FIELD, null)?.let { runCatching { FileSortField.valueOf(it) }.getOrNull() } ?: FileSortField.DateAdded,
            ascending = prefs.getBoolean(KEY_ASC, false),
        )
        set(value) { prefs.edit().putString(KEY_FIELD, value.field.name).putBoolean(KEY_ASC, value.ascending).apply() }

    override var viewMode: FilesViewMode
        get() = prefs.getString(KEY_VIEW, null)?.let { runCatching { FilesViewMode.valueOf(it) }.getOrNull() } ?: FilesViewMode.Grid
        set(value) { prefs.edit().putString(KEY_VIEW, value.name).apply() }

    override var lastFolderId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().putString(KEY_LAST, value).apply() }

    private companion object {
        const val KEY_FIELD = "sort_field"
        const val KEY_ASC = "sort_asc"
        const val KEY_VIEW = "view_mode"
        const val KEY_LAST = "last_folder"
    }
}
