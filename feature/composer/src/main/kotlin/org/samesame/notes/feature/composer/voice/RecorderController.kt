package org.samesame.notes.feature.composer.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.samesame.notes.core.model.Limits
import java.io.File

/** The part of `MediaRecorder` the controller needs, so the state machine runs on the JVM. */
interface AudioEngine {
    /** Called (any thread) when the duration or size cap was reached; the recording is still open and must be stopped. */
    var onLimitReached: (() -> Unit)?

    /** Called when the recorder failed while running. */
    var onError: (() -> Unit)?

    /** Starts recording into [file]; false when the microphone is busy or the encoder cannot be set up. */
    fun start(file: File): Boolean
    fun pause(): Boolean
    fun resume(): Boolean

    /** Finalises the file; false when it holds no usable audio. Releases the recorder either way. */
    fun stop(): Boolean

    /** Releases the recorder without caring about the file. */
    fun abort()
}

/** Where in the note the voice object goes (page space, centre). Taken when recording starts, so it lands where the user was. */
data class RecordingAnchor(val pageId: String?, val xPu: Float, val yPu: Float)

sealed interface RecorderState {
    data object Idle : RecorderState

    /** A recording in progress for [noteId]; [baseMs] is the time before the current run, [resumedAt] the clock reading when it began. */
    data class Active(
        val noteId: String,
        val anchor: RecordingAnchor?,
        val paused: Boolean,
        val baseMs: Long,
        val resumedAt: Long,
    ) : RecorderState {
        fun elapsedMs(now: Long): Long = (if (paused) baseMs else baseMs + (now - resumedAt)).coerceIn(0L, Limits.VOICE_MAX_DURATION_MS.toLong())
    }
}

enum class FinishReason { Stopped, MaxLength, Interrupted, EditorClosed }
enum class DiscardReason { TooShort, Failed }

/** The outcome of a recording, waiting for the editor of [noteId] to claim it. */
sealed interface RecordingResult {
    val noteId: String

    class Saved(
        override val noteId: String,
        val anchor: RecordingAnchor?,
        val file: File,
        val durationMs: Long,
        val reason: FinishReason,
    ) : RecordingResult

    class Discarded(override val noteId: String, val reason: DiscardReason) : RecordingResult
}

sealed interface StartOutcome {
    data object Started : StartOutcome

    /** A recording is already running (only one at a time, for any note). */
    data object Busy : StartOutcome
    data object Failed : StartOutcome
}

/**
 * Record / pause / resume / stop state machine of the voice recorder. One recording at a time. Main-thread only
 * (the engine callbacks are expected to be posted there). A finished recording is queued in [results] until the
 * editor of its note claims it with [consume], so a stop from the notification or an interruption while the editor is
 * away is not lost.
 *
 * the reference behaviour followed (read only): 6 h / 500 MiB caps, auto-save on interruption, "too short" recordings are dropped.
 */
class RecorderController(
    private val engineFactory: () -> AudioEngine,
    private val tempDir: () -> File,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _results = MutableStateFlow<List<RecordingResult>>(emptyList())
    val results: StateFlow<List<RecordingResult>> = _results.asStateFlow()

    private var engine: AudioEngine? = null
    private var file: File? = null

    val isActive: Boolean get() = _state.value is RecorderState.Active

    fun start(noteId: String, anchor: RecordingAnchor?): StartOutcome {
        if (isActive) return StartOutcome.Busy
        val dir = tempDir().apply { mkdirs() }
        val target = File(dir, "rec-${clock()}-${System.nanoTime()}.${Limits.VOICE_FILE_EXTENSION}")
        val e = engineFactory()
        e.onLimitReached = { finish(FinishReason.MaxLength) }
        e.onError = { finish(null) }
        val ok = try { e.start(target) } catch (t: RuntimeException) { false }
        if (!ok) {
            e.abort()
            target.delete()
            return StartOutcome.Failed
        }
        engine = e
        file = target
        _state.value = RecorderState.Active(noteId, anchor, paused = false, baseMs = 0L, resumedAt = clock())
        return StartOutcome.Started
    }

    fun pause(): Boolean {
        val s = _state.value as? RecorderState.Active ?: return false
        if (s.paused) return false
        if (engine?.pause() != true) return false
        _state.value = s.copy(paused = true, baseMs = s.elapsedMs(clock()))
        return true
    }

    fun resume(): Boolean {
        val s = _state.value as? RecorderState.Active ?: return false
        if (!s.paused) return false
        if (engine?.resume() != true) return false
        _state.value = s.copy(paused = false, resumedAt = clock())
        return true
    }

    /** Stop button, notification Stop. */
    fun stop() = finish(FinishReason.Stopped)

    /** A phone call or another app took the audio focus: save what we have. */
    fun interrupt() = finish(FinishReason.Interrupted)

    /** The editor of the recording's note is closing: save and hand over. */
    fun stopForClose() = finish(FinishReason.EditorClosed)

    /** Periodic check from the UI; stops at the duration cap even when the engine's own callback did not come. */
    fun tick() {
        val s = _state.value as? RecorderState.Active ?: return
        if (!s.paused && s.elapsedMs(clock()) >= Limits.VOICE_MAX_DURATION_MS) finish(FinishReason.MaxLength)
    }

    /** Throws the recording away (no result is queued). */
    fun cancel() {
        if (!isActive) return
        engine?.abort()
        file?.delete()
        reset()
    }

    /** The editor took [result]; removes it from the queue. */
    fun consume(result: RecordingResult) {
        _results.value = _results.value.filterNot { it === result }
    }

    private fun finish(reason: FinishReason?) {
        val s = _state.value as? RecorderState.Active ?: return
        val f = file
        val e = engine
        val duration = s.elapsedMs(clock())
        val usable = reason != null && try { e?.stop() == true } catch (t: RuntimeException) { false }
        if (reason == null) e?.abort()
        reset()
        val result: RecordingResult = when {
            f == null || !usable -> { f?.delete(); RecordingResult.Discarded(s.noteId, DiscardReason.Failed) }
            duration < Limits.VOICE_MIN_DURATION_MS -> { f.delete(); RecordingResult.Discarded(s.noteId, DiscardReason.TooShort) }
            else -> RecordingResult.Saved(s.noteId, s.anchor, f, duration, reason!!)
        }
        _results.value = _results.value + result
    }

    private fun reset() {
        engine = null
        file = null
        _state.value = RecorderState.Idle
    }
}
