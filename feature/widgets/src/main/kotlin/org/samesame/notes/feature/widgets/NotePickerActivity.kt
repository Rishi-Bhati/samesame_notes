package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortOrder
import dev.oneuiproject.oneui.R as Oui

/**
 * Configure screen of the single-note widget: pick the note, then the look (background, opacity) with a live preview.
 * A locked note can't be pinned (shown dimmed with a lock), and a note already on another widget is refused too. Nothing is
 * saved until Save.
 */
class NotePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = requestedWidgetId()
        setConfigureResult(widgetId, ok = false)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val ep = WidgetEntryPoint.get(this)
        val styles = WidgetStyleStore(this)
        val size = widgetSize(widgetId, DpSize(320.dp, 180.dp))
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
                // Back from the list returns to the settings when a note was already chosen.
                BackHandler(enabled = chosen != null) { picking = false }
                ConfigScaffold(
                    title = stringResource(R.string.widget_pick_note_title),
                    onClose = { if (chosen != null) picking = false else finish() },
                ) {
                    val visible = notes.filter { !it.isInTrash }
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.widget_pick_note_empty), color = SameSameTheme.colors.textSecondary, fontSize = 15.sp)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                            itemsIndexed(visible, key = { _, n -> n.id.value }) { i, note ->
                                SectionCard(null, Modifier.padding(top = if (i == 0) 0.dp else 6.dp)) {
                                    ChoiceRow(
                                        title = note.title.ifBlank { stringResource(R.string.widget_untitled) },
                                        subtitle = if (note.isLocked) stringResource(R.string.widget_locked_note)
                                        else widgetDate(context, note.modifiedAt) + WidgetContent.snippet(note.previewText, 80).let { if (it.isEmpty()) "" else "  ·  $it" },
                                        icon = if (note.isLocked) Oui.drawable.ic_oui_lock else Oui.drawable.ic_oui_notes,
                                        iconTint = if (note.isLocked) null else SameSameTheme.colors.accent,
                                        selected = note.id.value == chosen,
                                        enabled = WidgetContent.canPin(note),
                                        onClick = {
                                            when {
                                                !WidgetContent.canPin(note) -> Toast.makeText(context, R.string.widget_pick_locked, Toast.LENGTH_SHORT).show()
                                                ep.pins().isPinned(note.id) && current != note.id -> Toast.makeText(context, R.string.widget_pick_already, Toast.LENGTH_SHORT).show()
                                                else -> { chosen = note.id.value; picking = false }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val note = notes.firstOrNull { it.id.value == chosen }
                val density = LocalDensity.current.density
                val area = SingleNoteLayout.pageArea(size)
                ConfigScaffold(
                    title = stringResource(R.string.widget_settings_single),
                    onClose = { finish() },
                    onSave = {
                        val id = chosen?.let(::NoteId) ?: return@ConfigScaffold
                        scope.launch {
                            ep.pins().pin(widgetId, id)
                            styles.set(widgetId, style)
                            runCatching { SingleNoteWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)) }
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
                                title = note?.title?.ifBlank { null } ?: stringResource(R.string.widget_untitled),
                                subtitle = stringResource(R.string.widget_change),
                                icon = Oui.drawable.ic_oui_notes, iconTint = SameSameTheme.colors.accent,
                                onClick = { picking = true },
                            )
                        },
                    ) { roles, night, s ->
                        // The page as the widget would draw it for this style on the stage's day or night.
                        val dark = style.isDark(night, wallpaperDarkText = !night)
                        val page by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, chosen, dark) {
                            val id = chosen?.let(::NoteId)
                            value = if (id != null && area != null) {
                                WidgetBitmaps.pageTop(ep.notes(), id, dark, (area.width.value * density).toInt(), area.height.value / area.width.value)
                                    ?.asImageBitmap()
                            } else null
                        }
                        SingleNoteReplica(
                            roles, night, s, note?.title.orEmpty(), note?.let { widgetDate(context, it.modifiedAt) }.orEmpty(),
                            note?.let { WidgetContent.snippet(it.previewText) }.orEmpty(), page,
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, widgetId: Int): Intent = configureIntent(context, NotePickerActivity::class.java, widgetId)
    }
}
