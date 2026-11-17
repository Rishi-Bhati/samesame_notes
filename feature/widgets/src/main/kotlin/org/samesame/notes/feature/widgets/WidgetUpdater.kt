package org.samesame.notes.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.data.di.ApplicationScope
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.data.repository.WidgetPinStore
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the home-screen widgets current: while at least one widget exists it watches the note catalog and, a moment after
 * the last change, redraws all of them (see [refreshTriggers]). Started by the app on launch and by the receivers when a
 * widget is added; stops when the last widget is removed.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notes: NoteRepository,
    private val pins: WidgetPinStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var job: Job? = null

    @Synchronized
    fun ensureStarted() {
        if (lastNight == null) onConfigurationChanged()
        publishPreviewsOnce()
        if (job?.isActive == true) return
        if (!hasWidgets()) return
        prunePins()
        watchWallpaper()
        WidgetLog.i("updater started")
        job = scope.launch {
            // Once per start (app launch, first widget, app update): widgets drawn by an older process are refreshed.
            updateAll()
            notes.observeNotes(NoteFilter.All, SortOrder())
                .map(WidgetContent::signature)
                .refreshTriggers()
                .collect { updateAll() }
        }
    }

    @Synchronized
    fun stopIfNoWidgets() {
        if (!hasWidgets()) { job?.cancel(); job = null }
    }

    private var lastNight: Boolean? = null

    /**
     * The system's day/night mode may have changed: a widget that matches dark mode shows a page rendered for the old mode,
     * so redraw them all (the app's `onConfigurationChanged` calls this).
     */
    @Synchronized
    fun onConfigurationChanged() {
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val changed = lastNight != null && lastNight != night
        lastNight = night
        if (changed && hasWidgets()) scope.launch { updateAll() }
    }

    /**
     * Redraws every widget now: live sessions reload their data ([WidgetSignals]), the others start a new session. Safe to
     * call from anywhere (a failed redraw is logged and skipped; the next change retries).
     */
    suspend fun updateAll() {
        WidgetSignals.bump()
        listOf(SingleNoteWidget(), NoteListWidget(), NoteCreatorWidget(), QuickCaptureWidget()).forEach { widget ->
            runCatching { widget.updateAll(context) }.onFailure { WidgetLog.w("updateAll ${widget.javaClass.simpleName} failed", it) }
        }
    }

    /**
     * After a configure screen saved [widgetId]'s settings: redraw it. Runs in the application scope, so it finishes after
     * the configure activity is gone.
     */
    fun redraw(widgetId: Int, widget: () -> androidx.glance.appwidget.GlanceAppWidget) {
        scope.launch {
            WidgetSignals.bump()
            runCatching {
                widget().update(context, androidx.glance.appwidget.GlanceAppWidgetManager(context).getGlanceIdBy(widgetId))
            }.onSuccess { WidgetLog.i("redraw #$widgetId requested") }
                .onFailure { WidgetLog.w("redraw #$widgetId failed", it) }
        }
        ensureStarted()
    }

    private var wallpaperListener: Any? = null

    /**
     * A see-through widget takes its text colour from the wallpaper (see [WidgetTheme.contentDark]): redraw when the
     * wallpaper's colours change while the app runs (any other redraw picks the new colours up too).
     */
    private fun watchWallpaper() {
        if (wallpaperListener != null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O_MR1) return
        val listener = android.app.WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and android.app.WallpaperManager.FLAG_SYSTEM != 0) scope.launch { updateAll() }
        }
        runCatching {
            android.app.WallpaperManager.getInstance(context)
                .addOnColorsChangedListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
            wallpaperListener = listener
        }
    }

    /**
     * Android 15+ shows widget-picker previews drawn by the widgets themselves (`providePreview`, sample content only).
     * They are published once per app version (the system rate-limits it); older systems use the XML previews.
     */
    private fun publishPreviewsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        val prefs = context.getSharedPreferences("widget_previews", Context.MODE_PRIVATE)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        if (prefs.getLong("published", -1L) == version) return
        scope.launch {
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
            val receivers = listOf(
                NoteCreatorWidgetReceiver::class, SingleNoteWidgetReceiver::class, NoteListWidgetReceiver::class, QuickCaptureWidgetReceiver::class,
            )
            val categories = androidx.collection.intSetOf(android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            val ok = receivers.all { r ->
                runCatching { manager.setWidgetPreviews(r, categories) }.getOrNull() ==
                    androidx.glance.appwidget.GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS
            }
            if (ok) prefs.edit().putLong("published", version).apply()
        }
    }

    /** Forgets pins of widgets that no longer exist (removed while the app was not running). */
    private fun prunePins() {
        val live = widgetIds(SingleNoteWidgetReceiver::class.java).toSet()
        pins.widgetIds().filter { it !in live }.forEach(pins::unpin)
    }

    private fun hasWidgets(): Boolean =
        listOf(
            SingleNoteWidgetReceiver::class.java, NoteListWidgetReceiver::class.java, NoteGridWidgetReceiver::class.java,
            NoteCreatorWidgetReceiver::class.java, QuickCaptureWidgetReceiver::class.java,
        )
            .any { widgetIds(it).isNotEmpty() }

    private fun widgetIds(receiver: Class<*>): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver)) }.getOrDefault(IntArray(0))
}
