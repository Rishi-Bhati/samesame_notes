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
        setConfigContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val foldersFlow = remember { ep.folders().observeFolders() }
            val folders by foldersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            val current = config.folderOf(widgetId)
            fun choose(folder: FolderId?) = scope.launch {
                config.set(widgetId, folder)
                runCatching { NoteListWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)) }
                ep.updater().ensureStarted()
                setConfigureResult(widgetId, ok = true)
                finish()
            }
            Column(Modifier.fillMaxSize()) {
                ConfigTitle(stringResource(R.string.widget_pick_folder_title))
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "recent") { FolderPickRow(stringResource(R.string.widget_list_title), 0, current == null) { choose(null) } }
                    items(folders, key = { it.id.value }) { folder: Folder ->
                        FolderPickRow(folder.name, folder.depth, current == folder.id) { choose(folder.id) }
                    }
                }
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
