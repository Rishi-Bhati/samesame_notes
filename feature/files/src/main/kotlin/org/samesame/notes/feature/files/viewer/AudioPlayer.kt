package org.samesame.notes.feature.files.viewer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.VoiceSpeed

/** The part of the platform player the audio sheet needs (faked in tests). */
internal interface AudioEngine {
    var onCompleted: (() -> Unit)?
    var onError: (() -> Unit)?
    /** Another app took the audio output: playback must pause. */
    var onInterrupted: (() -> Unit)?

    /** Loads the file; false when it can't be played. Blocking. */
    fun prepare(): Boolean
    val durationMs: Int
    val positionMs: Int
    fun start()
    fun pause()
    fun seekTo(ms: Int)
    fun setSpeed(speed: Float)
    fun release()
}

internal data class AudioState(
    val ready: Boolean = false,
    val failed: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speed: Float = VoiceSpeed.NORMAL,
)

/**
 * Playback state of the audio sheet over one file: play / pause, seek, 10 s skips and the speed cycle of the note
 * recordings player. At the end it stops at the full length; the next play starts over.
 */
internal class AudioPlayer(private val engine: AudioEngine) {
    private val _state = MutableStateFlow(AudioState())
    val state: StateFlow<AudioState> = _state.asStateFlow()
    private var released = false

    init {
        engine.onCompleted = { _state.update { it.copy(playing = false, positionMs = it.durationMs) } }
        engine.onError = { _state.update { it.copy(playing = false, failed = true, ready = false) } }
        engine.onInterrupted = { pause() }
    }

    /** Loads the file (blocking; call off the main thread). */
    fun load(autoPlay: Boolean) {
        if (released || _state.value.ready) return
        if (!engine.prepare()) { _state.update { it.copy(failed = true) }; return }
        _state.update { it.copy(ready = true, failed = false, durationMs = engine.durationMs, positionMs = 0) }
        if (autoPlay) toggle()
    }

    fun toggle() {
        val s = _state.value
        if (!s.ready || released) return
        if (s.playing) { pause(); return }
        if (s.durationMs > 0 && s.positionMs >= s.durationMs) engine.seekTo(0)
        engine.setSpeed(s.speed)
        engine.start()
        _state.update { it.copy(playing = true, positionMs = if (it.durationMs > 0 && it.positionMs >= it.durationMs) 0 else it.positionMs) }
    }

    fun pause() {
        if (!_state.value.playing) return
        engine.pause()
        _state.update { it.copy(playing = false, positionMs = engine.positionMs.coerceIn(0, it.durationMs.coerceAtLeast(0))) }
    }

    fun seekTo(ms: Int) {
        val s = _state.value
        if (!s.ready) return
        val target = ms.coerceIn(0, s.durationMs.coerceAtLeast(0))
        engine.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun rewind() = seekTo(_state.value.positionMs - Limits.VOICE_SEEK_STEP_MS)
    fun forward() = seekTo(_state.value.positionMs + Limits.VOICE_SEEK_STEP_MS)

    fun cycleSpeed() {
        val next = VoiceSpeed.next(_state.value.speed)
        _state.update { it.copy(speed = next) }
        if (_state.value.playing) engine.setSpeed(next)
    }

    /** Follows the engine's position while it plays (the sheet calls this a few times a second). */
    fun refresh() {
        val s = _state.value
        if (s.playing) _state.update { it.copy(positionMs = engine.positionMs.coerceIn(0, it.durationMs.coerceAtLeast(0))) }
    }

    fun release() {
        if (released) return
        released = true
        engine.release()
        _state.update { it.copy(playing = false, ready = false) }
    }

    companion object {
        /** "0:07", "12:34", "1:02:03". */
        fun time(ms: Int): String {
            val total = (ms.coerceAtLeast(0) / 1000)
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
        }
    }
}

/** The platform `MediaPlayer` over a library file's provider URI, holding audio focus while it plays. */
internal class MediaPlayerAudioEngine(private val context: Context, private val uri: Uri) : AudioEngine {
    override var onCompleted: (() -> Unit)? = null
    override var onError: (() -> Unit)? = null
    override var onInterrupted: (() -> Unit)? = null
    private var player: MediaPlayer? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change -> if (change < 0) onInterrupted?.invoke() }
        .build()

    override fun prepare(): Boolean {
        val p = MediaPlayer()
        player = p
        return try {
            p.setAudioAttributes(attributes)
            p.setDataSource(context, uri)
            p.setOnCompletionListener { audioManager.abandonAudioFocusRequest(focusRequest); onCompleted?.invoke() }
            p.setOnErrorListener { _, _, _ -> onError?.invoke(); true }
            p.prepare()
            true
        } catch (e: Exception) {
            false
        }
    }

    override val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0).coerceAtLeast(0)
    override val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    override fun start() {
        audioManager.requestAudioFocus(focusRequest)
        runCatching { player?.start() }
    }

    override fun pause() {
        runCatching { player?.pause() }
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun seekTo(ms: Int) { runCatching { player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) } }

    override fun setSpeed(speed: Float) {
        val p = player ?: return
        runCatching {
            // Setting the speed on a paused player would start it, so its state is kept.
            val playing = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(speed)
            if (!playing) p.pause()
        }
    }

    override fun release() {
        val p = player ?: return
        player = null
        runCatching { p.release() }
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
