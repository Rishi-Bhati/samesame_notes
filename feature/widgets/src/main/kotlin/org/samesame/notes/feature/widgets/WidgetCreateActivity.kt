package org.samesame.notes.feature.widgets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.samesame.notes.core.model.FolderId

/**
 * Invisible stop of the widgets' new-note buttons: creates an empty note (in the widget's folder, as a favourite for a
 * favourites widget), then opens it in the editor in the mode of the button that was tapped. Not exported (only the
 * widgets' own pending intents start it).
 */
class WidgetCreateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = StartAction.fromKey(intent.getStringExtra(WidgetIntents.EXTRA_START_ACTION)) ?: StartAction.Keypad
        // Recreated (rotation, process death) after the note was already made: don't make a second one.
        if (savedInstanceState != null) { finish(); return }
        lifecycleScope.launch {
            val ep = WidgetEntryPoint.get(this@WidgetCreateActivity)
            val notes = ep.notes()
            // Only a folder that still exists (it may have been deleted since the widget was drawn): otherwise no folder.
            val folder = intent.getStringExtra(WidgetIntents.EXTRA_FOLDER_ID)?.let(::FolderId)?.takeIf { wanted ->
                runCatching { ep.folders().observeFolders().first() }.getOrDefault(emptyList()).any { it.id == wanted && !it.isInTrash }
            }
            val id = runCatching {
                if (action == StartAction.Markdown) notes.createMarkdownNote(folder) else notes.createNote(folderId = folder)
            }.getOrNull()
            if (id != null && intent.getBooleanExtra(WidgetIntents.EXTRA_FAVORITE, false)) {
                runCatching { notes.setFavorite(listOf(id), true) }
            }
            if (id != null) startActivity(WidgetIntents.openNewNote(this@WidgetCreateActivity, id.value, action))
            finish()
        }
    }
}
