package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.launch

/**
 * Settings of the new-note and quick-capture widgets: background and opacity, with a live preview at the widget's size; the
 * quick-capture widget also picks the action of each of its buttons.
 */
class CreatorSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = requestedWidgetId()
        setConfigureResult(widgetId, ok = false)
        WidgetLog.i("creator configure #$widgetId opened")
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val styles = WidgetStyleStore(this)
        val buttonStore = CaptureButtonStore(this)
        val size = widgetSize(widgetId, DpSize(320.dp, 72.dp))
        // One settings screen for the two look-only widgets: new note and quick capture.
        val capture = runCatching { AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.className }.getOrNull() ==
            QuickCaptureWidgetReceiver::class.java.name
        setConfigContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            var background by rememberSaveable { mutableStateOf(styles.get(widgetId).background.ordinal) }
            var opacity by rememberSaveable { mutableStateOf(styles.get(widgetId).opacity) }
            var buttonsKey by rememberSaveable { mutableStateOf(buttonStore.get(widgetId).encode()) }
            val buttons = CaptureButtons.decode(buttonsKey)
            val style = WidgetStyle(WidgetBackground.entries[background], opacity)
            val icon = remember { AppBranding.icon(context, 96)?.asImageBitmap() }
            val name = remember { AppBranding.name(context) }
            ConfigScaffold(
                title = stringResource(if (capture) R.string.widget_settings_capture else R.string.widget_settings_creator),
                onClose = { finish() },
                onSave = {
                    styles.set(widgetId, style)
                    if (capture) buttonStore.set(widgetId, buttons)
                    setConfigureResult(widgetId, ok = true)
                    WidgetLog.i("creator #$widgetId configured")
                    WidgetEntryPoint.get(context).updater().redraw(widgetId) { if (capture) QuickCaptureWidget() else NoteCreatorWidget() }
                    finish()
                },
            ) {
                WidgetSettingsBody(
                    style = style,
                    onStyle = { background = it.background.ordinal; opacity = it.opacity },
                    widgetSize = size,
                    contentHeading = if (capture) stringResource(R.string.widget_section_buttons) else null,
                    content = if (capture) {
                        { CaptureButtonsEditor(buttons) { buttonsKey = it.encode() } }
                    } else {
                        null
                    },
                ) { roles, night, s ->
                    if (capture) QuickCaptureReplica(roles, night, s, buttons) else CreatorReplica(roles, night, s, name, icon)
                }
            }
        }
    }

    companion object {
        fun intent(context: Context, widgetId: Int): Intent = configureIntent(context, CreatorSettingsActivity::class.java, widgetId)
    }
}
