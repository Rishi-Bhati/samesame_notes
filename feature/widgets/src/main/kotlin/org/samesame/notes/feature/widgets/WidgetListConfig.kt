package org.samesame.notes.feature.widgets

import android.content.Context
import org.samesame.notes.core.model.FolderId

/** Which folder each note-list widget shows (`widget id -> folder id`); no entry means the recent notes of every folder. */
class WidgetListConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("widget_list_config", Context.MODE_PRIVATE)

    fun folderOf(widgetId: Int): FolderId? = prefs.getString("f$widgetId", null)?.let(::FolderId)

    /** [folder] null = recent notes. */
    fun set(widgetId: Int, folder: FolderId?) {
        prefs.edit().apply { if (folder == null) remove("f$widgetId") else putString("f$widgetId", folder.value) }.apply()
    }

    fun clear(widgetId: Int) = set(widgetId, null)
}
