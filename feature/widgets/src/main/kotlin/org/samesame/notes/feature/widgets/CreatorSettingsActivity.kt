package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.launch

/** Settings of the note-creator widget: background colour and opacity, with a live preview. */
class CreatorSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = requestedWidgetId()
        setConfigureResult(widgetId, ok = false)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val styles = WidgetStyleStore(this)
        setConfigContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var background by rememberSaveable { mutableStateOf(styles.get(widgetId).background.ordinal) }
            var opacity by rememberSaveable { mutableStateOf(styles.get(widgetId).opacity) }
            val style = WidgetStyle(WidgetBackground.entries[background], opacity)
            WidgetSettingsScreen(
                title = stringResource(R.string.widget_settings_title),
                style = style,
                onStyle = { background = it.background.ordinal; opacity = it.opacity },
                onDone = {
                    scope.launch {
                        styles.set(widgetId, style)
                        runCatching { NoteCreatorWidget().update(context, GlanceAppWidgetManager(context).getGlanceIdBy(widgetId)) }
                        WidgetEntryPoint.get(context).updater().ensureStarted()
                        setConfigureResult(widgetId, ok = true)
                        finish()
                    }
                },
                preview = { s, dark -> CreatorPreview(s, dark) },
            )
        }
    }

    companion object {
        fun intent(context: Context, widgetId: Int): Intent = configureIntent(context, CreatorSettingsActivity::class.java, widgetId)
    }
}
