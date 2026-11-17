package org.samesame.notes.feature.widgets

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Widget diagnostics: `adb logcat -s SsWidget` shows each widget's way from the update request to its content. */
internal object WidgetLog {
    const val TAG = "SsWidget"
    fun i(message: String) { runCatching { Log.i(TAG, message) } }
    fun w(message: String, t: Throwable? = null) { runCatching { Log.w(TAG, message, t) } }
}

/**
 * Redraw requests for widgets whose Glance session is already running. Glance keeps a session alive for a while after the
 * first draw, and an `update()` then does not call `provideGlance` again (it only reloads a Glance state definition, which
 * these widgets don't use). So every widget composes its data from [version]: bumping it reloads the data of every live
 * widget; widgets without a session load fresh anyway when their new session starts.
 */
internal object WidgetSignals {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun bump() = _version.update { it + 1 }
}

/** What a widget's data load came back with. */
internal sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
    /** The load failed or took too long: the widget shows a retry state instead of spinning forever. */
    data object Failed : Loaded<Nothing>
}

/** Load timeout: a widget never waits longer than this for its data. */
internal const val WIDGET_LOAD_TIMEOUT_MS = 15_000L

/** Runs [block] off the main thread with a timeout; failures and timeouts are logged and become [Loaded.Failed]. */
internal suspend fun <T> loadWidgetData(what: String, timeoutMs: Long = WIDGET_LOAD_TIMEOUT_MS, block: suspend () -> T): Loaded<T> {
    val started = System.currentTimeMillis()
    return try {
        val value = withTimeout(timeoutMs) { withContext(Dispatchers.Default) { block() } }
        WidgetLog.i("$what loaded in ${System.currentTimeMillis() - started} ms")
        Loaded.Ready(value)
    } catch (e: TimeoutCancellationException) {
        WidgetLog.w("$what timed out after $timeoutMs ms")
        Loaded.Failed
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        WidgetLog.w("$what failed", e)
        Loaded.Failed
    }
}

/** Every widget of the app: logs composition errors (Glance then shows its error layout) under [WidgetLog.TAG]. */
abstract class LoggingGlanceWidget : GlanceAppWidget() {
    override fun onCompositionError(context: Context, glanceId: GlanceId, appWidgetId: Int, throwable: Throwable) {
        WidgetLog.w("${javaClass.simpleName} #$appWidgetId composition error", throwable)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }
}
