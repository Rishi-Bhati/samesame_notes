package org.samesame.notes.feature.widgets

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Starts the catalog watcher when the first widget is added or the app is updated, and stops it with the last widget. */
abstract class WidgetReceiverBase : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetEntryPoint.get(context).updater().ensureStarted()
    }

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetEntryPoint.get(context).updater().ensureStarted()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetEntryPoint.get(context).updater().stopIfNoWidgets()
    }
}
