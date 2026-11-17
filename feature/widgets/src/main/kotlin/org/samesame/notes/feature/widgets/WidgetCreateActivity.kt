package org.samesame.notes.feature.widgets

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Invisible stop of the note-creator buttons: creates an empty note, then opens it in the editor in the mode of the
 * button that was tapped. Not exported (only the widget's own pending intents start it).
 */
class WidgetCreateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = StartAction.fromKey(intent.getStringExtra(WidgetIntents.EXTRA_START_ACTION)) ?: StartAction.Keypad
        // Recreated (rotation, process death) after the note was already made: don't make a second one.
        if (savedInstanceState != null) { finish(); return }
        lifecycleScope.launch {
            val notes = WidgetEntryPoint.get(this@WidgetCreateActivity).notes()
            val id = runCatching { notes.createNote(folderId = null) }.getOrNull()
            if (id != null) startActivity(WidgetIntents.openNewNote(this@WidgetCreateActivity, id.value, action))
            finish()
        }
    }
}
