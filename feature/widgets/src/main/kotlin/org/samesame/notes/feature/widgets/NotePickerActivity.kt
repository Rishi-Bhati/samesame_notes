package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.SortOrder
import org.samesame.notes.core.model.NoteId
import androidx.compose.runtime.remember

/**
 * Configure screen of the single-note widget: pick the note to show. A locked note can't be pinned, and a note that is
 * already on another widget is refused too.
 */
class NotePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = requestedWidgetId()
        setConfigureResult(widgetId, ok = false)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val ep = WidgetEntryPoint.get(this)
        setConfigContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val notesFlow = remember { ep.notes().observeNotes(NoteFilter.All, SortOrder()) }
            val notes by notesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val current = ep.pins().noteOf(widgetId)
            Column(Modifier.fillMaxSize()) {
                ConfigTitle(stringResource(R.string.widget_pick_note_title))
                if (notes.isEmpty()) {
                    Text(stringResource(R.string.widget_pick_note_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable { }.then(Modifier))
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(notes, key = { it.id.value }) { note ->
                        NotePickRow(note, selected = note.id == current) {
                            when {
                                !WidgetContent.canPin(note) -> Toast.makeText(context, R.string.widget_pick_locked, Toast.LENGTH_SHORT).show()
                                ep.pins().isPinned(note.id) && current != note.id -> Toast.makeText(context, R.string.widget_pick_already, Toast.LENGTH_SHORT).show()
                                else -> scope.launch {
                                    ep.pins().pin(widgetId, note.id)
                                    runCatching {
                                        val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)
                                        SingleNoteWidget().update(context, glanceId)
                                    }
                                    ep.updater().ensureStarted()
                                    setConfigureResult(widgetId, ok = true)
                                    finish()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, widgetId: Int): Intent = configureIntent(context, NotePickerActivity::class.java, widgetId)
    }
}

@androidx.compose.runtime.Composable
private fun NotePickRow(note: Note, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(note.title.ifBlank { stringResource(R.string.widget_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (note.isLocked) Text(stringResource(R.string.widget_locked_note), maxLines = 1) },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
