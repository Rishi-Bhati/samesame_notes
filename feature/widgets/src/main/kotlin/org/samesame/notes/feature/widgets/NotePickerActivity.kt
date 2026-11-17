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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortOrder

/**
 * Configure screen of the single-note widget: pick the note, then the look (background, opacity) with a live preview.
 * A locked note can't be pinned, and a note already on another widget is refused too. Nothing is saved until Done.
 */
class NotePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = requestedWidgetId()
        setConfigureResult(widgetId, ok = false)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val ep = WidgetEntryPoint.get(this)
        val styles = WidgetStyleStore(this)
        setConfigContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val notesFlow = remember { ep.notes().observeNotes(NoteFilter.All, SortOrder()) }
            val notes by notesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val current = remember { ep.pins().noteOf(widgetId) }
            // A widget that already has a note starts on its settings; a new one starts by choosing the note.
            var chosen by rememberSaveable { mutableStateOf(current?.value) }
            var picking by rememberSaveable { mutableStateOf(current == null) }
            var background by rememberSaveable { mutableStateOf(styles.get(widgetId).background.ordinal) }
            var opacity by rememberSaveable { mutableStateOf(styles.get(widgetId).opacity) }
            val style = WidgetStyle(WidgetBackground.entries[background], opacity)

            if (picking) {
                Column(Modifier.fillMaxSize()) {
                    ConfigTitle(stringResource(R.string.widget_pick_note_title))
                    if (notes.isEmpty()) {
                        Text(stringResource(R.string.widget_pick_note_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(notes, key = { it.id.value }) { note ->
                            NotePickRow(note, selected = note.id.value == chosen) {
                                when {
                                    !WidgetContent.canPin(note) -> Toast.makeText(context, R.string.widget_pick_locked, Toast.LENGTH_SHORT).show()
                                    ep.pins().isPinned(note.id) && current != note.id -> Toast.makeText(context, R.string.widget_pick_already, Toast.LENGTH_SHORT).show()
                                    else -> { chosen = note.id.value; picking = false }
                                }
                            }
                        }
                    }
                }
            } else {
                val note = notes.firstOrNull { it.id.value == chosen }
                WidgetSettingsScreen(
                    title = stringResource(R.string.widget_settings_title),
                    style = style,
                    onStyle = { background = it.background.ordinal; opacity = it.opacity },
                    extra = { ChangeButton(R.string.widget_change_note, { picking = true }, Modifier.weight(1f)) },
                    onDone = {
                        val id = chosen?.let(::NoteId) ?: return@WidgetSettingsScreen
                        scope.launch {
                            ep.pins().pin(widgetId, id)
                            styles.set(widgetId, style)
                            runCatching { SingleNoteWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)) }
                            ep.updater().ensureStarted()
                            setConfigureResult(widgetId, ok = true)
                            finish()
                        }
                    },
                    preview = { s, dark -> SingleNotePreview(chosen?.let(::NoteId), note?.title.orEmpty(), s, dark) },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context, widgetId: Int): Intent = configureIntent(context, NotePickerActivity::class.java, widgetId)
    }
}

@Composable
private fun NotePickRow(note: Note, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(note.title.ifBlank { stringResource(R.string.widget_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { if (note.isLocked) Text(stringResource(R.string.widget_locked_note), maxLines = 1) },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
