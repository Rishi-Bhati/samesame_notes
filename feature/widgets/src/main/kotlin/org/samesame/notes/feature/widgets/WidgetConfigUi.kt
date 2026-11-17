package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.AppSettings

/** The widget id a configure activity was started for, or [AppWidgetManager.INVALID_APPWIDGET_ID]. */
internal fun ComponentActivity.requestedWidgetId(): Int =
    intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

internal fun configureIntent(context: android.content.Context, cls: Class<*>, widgetId: Int): Intent =
    Intent(context, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

/** The result the launcher expects: the id, with OK once configured or CANCELED to drop a widget that was just added. */
internal fun ComponentActivity.setConfigureResult(widgetId: Int, ok: Boolean) {
    setResult(if (ok) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
}

/** Themed (app theme setting, else the system one) full-screen host for a configure screen. */
internal fun ComponentActivity.setConfigContent(content: @Composable () -> Unit) {
    val settingsFlow = WidgetEntryPoint.get(this).settings().settings
    setContent {
        val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
        SameSameTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.systemBarsPadding()) { content() }
            }
        }
    }
}

@Composable
internal fun ConfigTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp))
}
