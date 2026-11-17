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
        if (job?.isActive == true) return
        if (!hasWidgets()) return
        prunePins()
        job = scope.launch {
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

    /** Redraws every widget now. Safe to call from anywhere (a failed redraw is skipped, the next change retries). */
    suspend fun updateAll() {
        runCatching { SingleNoteWidget().updateAll(context) }
        runCatching { NoteListWidget().updateAll(context) }
        runCatching { NoteCreatorWidget().updateAll(context) }
    }

    /** Forgets pins of widgets that no longer exist (removed while the app was not running). */
    private fun prunePins() {
        val live = widgetIds(SingleNoteWidgetReceiver::class.java).toSet()
        pins.widgetIds().filter { it !in live }.forEach(pins::unpin)
    }

    private fun hasWidgets(): Boolean =
        listOf(SingleNoteWidgetReceiver::class.java, NoteListWidgetReceiver::class.java, NoteCreatorWidgetReceiver::class.java)
            .any { widgetIds(it).isNotEmpty() }

    private fun widgetIds(receiver: Class<*>): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver)) }.getOrDefault(IntArray(0))
}
