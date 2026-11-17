package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortOrder
import dev.oneuiproject.oneui.R as Oui

/** Which of the two source-based widgets a configure screen is for. */
enum class ListKind { List, Pages }

/** Configure screen of the note-list and pages widgets: recent notes, favourites or one folder, then the look. */
class NoteListConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = requestedWidgetId()
        setConfigureResult(widgetId, ok = false)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val kind = kindOf(this, widgetId, intent)
        val ep = WidgetEntryPoint.get(this)
        val config = WidgetListConfig(this)
        val styles = WidgetStyleStore(this)
        val size = widgetSize(widgetId, DpSize(320.dp, 260.dp))
        setConfigContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val foldersFlow = remember { ep.folders().observeFolders() }
            val folders by foldersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            // A configured widget starts on its settings; a new one starts by choosing what to show.
            var picking by rememberSaveable { mutableStateOf(!styles.has(widgetId)) }
            // Once something was chosen, leaving the picker goes back to the settings instead of closing.
            var chosenOnce by rememberSaveable { mutableStateOf(styles.has(widgetId)) }
            var sourceKey by rememberSaveable { mutableStateOf(config.sourceOf(widgetId).key()) }
            var background by rememberSaveable { mutableStateOf(styles.get(widgetId).background.ordinal) }
            var opacity by rememberSaveable { mutableStateOf(styles.get(widgetId).opacity) }
            val style = WidgetStyle(WidgetBackground.entries[background], opacity)
            val source = ListSource.fromKey(sourceKey)
            val liveFolders = folders.filter { !it.isInTrash }
            val heading = sourceName(source, liveFolders)

            if (picking) {
                BackHandler(enabled = chosenOnce) { picking = false }
                ConfigScaffold(
                    title = stringResource(R.string.widget_pick_source_title),
                    onClose = { if (chosenOnce) picking = false else finish() },
                ) {
                    SourcePicker(source, liveFolders) { sourceKey = it.key(); chosenOnce = true; picking = false }
                }
            } else {
                val notesFlow = remember(sourceKey) { ep.notes().observeNotes(source.filter(), SortOrder()) }
                val notes by notesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                ConfigScaffold(
                    title = stringResource(if (kind == ListKind.Pages) R.string.widget_settings_grid else R.string.widget_settings_list),
                    onClose = { finish() },
                    onSave = {
                        scope.launch {
                            config.set(widgetId, source)
                            styles.set(widgetId, style)
                            val widget: GlanceAppWidget = if (kind == ListKind.Pages) NoteGridWidget() else NoteListWidget()
                            runCatching { widget.update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)) }
                            ep.updater().ensureStarted()
                            setConfigureResult(widgetId, ok = true)
                            finish()
                        }
                    },
                ) {
                    WidgetSettingsBody(
                        style = style,
                        onStyle = { background = it.background.ordinal; opacity = it.opacity },
                        widgetSize = size,
                        content = {
                            ChoiceRow(
                                title = heading, subtitle = stringResource(R.string.widget_change),
                                icon = sourceIcon(source), iconTint = SameSameTheme.colors.accent, onClick = { picking = true },
                            )
                        },
                    ) { roles, night, s ->
                        val rows = previewRows(notes, style.isDark(night, wallpaperDarkText = !night), kind)
                        if (kind == ListKind.Pages) NoteGridReplica(roles, night, s, heading, sourceIcon(source), rows)
                        else NoteListReplica(roles, night, s, heading, sourceIcon(source), rows)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_KIND = "org.samesame.notes.widgets.extra.LIST_KIND"

        fun intent(context: Context, widgetId: Int, kind: ListKind): Intent =
            configureIntent(context, NoteListConfigureActivity::class.java, widgetId).putExtra(EXTRA_KIND, kind.name)

        /** From the widget's own tap the kind is in the intent; from the launcher, it is the provider the widget belongs to. */
        private fun kindOf(context: Context, widgetId: Int, intent: Intent): ListKind {
            intent.getStringExtra(EXTRA_KIND)?.let { k -> ListKind.entries.firstOrNull { it.name == k }?.let { return it } }
            val provider = runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)?.provider?.className }.getOrNull()
            return if (provider == NoteGridWidgetReceiver::class.java.name) ListKind.Pages else ListKind.List
        }
    }
}

@Composable
private fun sourceName(source: ListSource, folders: List<Folder>): String = when (source) {
    ListSource.Recent -> stringResource(R.string.widget_source_recent)
    ListSource.Favorites -> stringResource(R.string.widget_source_favorites)
    is ListSource.Folder -> folders.firstOrNull { it.id == source.id }?.name ?: stringResource(R.string.widget_folder_gone)
}

private fun sourceIcon(source: ListSource): Int = when (source) {
    ListSource.Recent -> Oui.drawable.ic_oui_recent_outline
    ListSource.Favorites -> Oui.drawable.ic_oui_favorite_on
    is ListSource.Folder -> Oui.drawable.ic_oui_folder_outline
}

@Composable
private fun SourcePicker(current: ListSource, folders: List<Folder>, onPick: (ListSource) -> Unit) {
    val accent = SameSameTheme.colors.accent
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard(null) {
            ChoiceRow(stringResource(R.string.widget_source_recent), { onPick(ListSource.Recent) }, icon = sourceIcon(ListSource.Recent), iconTint = accent, selected = current == ListSource.Recent)
            CardDivider(52.dp)
            ChoiceRow(stringResource(R.string.widget_source_favorites), { onPick(ListSource.Favorites) }, icon = sourceIcon(ListSource.Favorites), iconTint = accent, selected = current == ListSource.Favorites)
        }
        if (folders.isNotEmpty()) {
            SectionCard(stringResource(R.string.widget_pick_folders)) {
                folders.forEachIndexed { i, f ->
                    if (i > 0) CardDivider(52.dp)
                    val source = ListSource.Folder(f.id)
                    ChoiceRow(
                        f.name, { onPick(source) }, icon = Oui.drawable.ic_oui_folder_outline,
                        iconTint = f.color?.let { androidx.compose.ui.graphics.Color(it.toInt()) },
                        selected = current == source, indent = (f.depth.coerceIn(0, 6) * 16).dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Up to six rows of the chosen source with their thumbnails (rendered for the stage's day or night). */
@Composable
private fun previewRows(notes: List<Note>, dark: Boolean, kind: ListKind): List<ReplicaRow> {
    val context = LocalContext.current
    val rows = remember(notes) { WidgetContent.rows(notes, if (kind == ListKind.Pages) 6 else 5) }
    val ep = remember { WidgetEntryPoint.get(context) }
    val density = LocalDensity.current.density
    val thumbs by produceState<Map<NoteId, ImageBitmap>>(emptyMap(), rows, dark) {
        val w = ((if (kind == ListKind.Pages) 120f else NoteListLayout.THUMB.value) * density).toInt()
        val aspect = if (kind == ListKind.Pages) NoteGridLayout.ASPECT else 1f
        val out = HashMap<NoteId, ImageBitmap>()
        for (row in rows) {
            if (row.locked) continue
            WidgetBitmaps.thumbnail(ep.notes(), row.noteId, dark, w, (w * aspect).toInt())?.let { out[row.noteId] = it.asImageBitmap() }
            value = HashMap(out)
        }
    }
    return rows.map { ReplicaRow(it.title, it.snippet, widgetDate(context, it.modifiedAt), thumbs[it.noteId], it.locked, it.favorite) }
}
