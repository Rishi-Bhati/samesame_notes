package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId

/** Configure screen of the note-list widget: recent notes of every folder, or the notes of one folder. */
class NoteListConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = requestedWidgetId()
        setConfigureResult(widgetId, ok = false)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val ep = WidgetEntryPoint.get(this)
        val config = WidgetListConfig(this)
        val styles = WidgetStyleStore(this)
        setConfigContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val foldersFlow = remember { ep.folders().observeFolders() }
            val folders by foldersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val notesFlow = remember { ep.notes().observeNotes(org.samesame.notes.core.model.NoteFilter.All, org.samesame.notes.core.model.SortOrder()) }
            val allNotes by notesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            // A configured widget starts on its settings; a new one starts by choosing what to show.
            var picking by rememberSaveable { mutableStateOf(!styles.has(widgetId)) }
            var folder by rememberSaveable { mutableStateOf(config.folderOf(widgetId)?.value) }
            var background by rememberSaveable { mutableStateOf(styles.get(widgetId).background.ordinal) }
            var opacity by rememberSaveable { mutableStateOf(styles.get(widgetId).opacity) }
            val style = WidgetStyle(WidgetBackground.entries[background], opacity)
            if (picking) {
                Column(Modifier.fillMaxSize()) {
                    ConfigTitle(stringResource(R.string.widget_pick_folder_title))
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                        item(key = "recent") { FolderPickRow(stringResource(R.string.widget_list_title), 0, folder == null) { folder = null; picking = false } }
                        items(folders, key = { it.id.value }) { f: Folder ->
                            FolderPickRow(f.name, f.depth, folder == f.id.value) { folder = f.id.value; picking = false }
                        }
                    }
                }
            } else {
                val heading = folders.firstOrNull { it.id.value == folder }?.name ?: stringResource(R.string.widget_list_title)
                val sample = remember(allNotes, folder) {
                    WidgetContent.rows(allNotes.filter { folder == null || it.folderId?.value == folder }, 3).map { it.title }
                }
                WidgetSettingsScreen(
                    title = stringResource(R.string.widget_settings_title),
                    style = style,
                    onStyle = { background = it.background.ordinal; opacity = it.opacity },
                    extra = { ChangeButton(R.string.widget_change_folder, { picking = true }, Modifier.weight(1f)) },
                    onDone = {
                        scope.launch {
                            config.set(widgetId, folder?.let(::FolderId))
                            styles.set(widgetId, style)
                            runCatching { NoteListWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)) }
                            ep.updater().ensureStarted()
                            setConfigureResult(widgetId, ok = true)
                            finish()
                        }
                    },
                    preview = { s, dark -> NoteListPreview(heading, sample, s, dark) },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context, widgetId: Int): Intent = configureIntent(context, NoteListConfigureActivity::class.java, widgetId)
    }
}

@Composable
private fun FolderPickRow(name: String, depth: Int, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(start = (depth.coerceIn(0, 6) * 16).dp),
    )
}
