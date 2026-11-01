package org.samesame.notes.feature.composer.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.samesame.notes.core.model.Limits
import java.io.File

/**
 * `MediaRecorder` with the reference's profile: microphone source, 3GPP
 * container, AAC encoder, 44.1 kHz, stereo, 128 kbps. Capped at 6 h / 500 MiB (or the free space). The file is stored
 * as `.m4a` ([Limits.VOICE_FILE_EXTENSION]), the extension the reference uses for voice files.
 */
class MediaRecorderEngine(private val context: Context) : AudioEngine {
    override var onLimitReached: (() -> Unit)? = null
    override var onError: (() -> Unit)? = null
    private var recorder: MediaRecorder? = null
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    override fun start(file: File): Boolean {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = r
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(Limits.VOICE_SAMPLE_RATE_HZ)
            r.setAudioEncodingBitRate(Limits.VOICE_BIT_RATE)
            r.setAudioChannels(Limits.VOICE_CHANNELS)
            r.setMaxDuration(Limits.VOICE_MAX_DURATION_MS)
            val free = (file.parentFile ?: file).usableSpace
            r.setMaxFileSize(minOf(Limits.VOICE_MAX_FILE_BYTES, free).coerceAtLeast(1L))
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                    what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED
                ) main.post { onLimitReached?.invoke() }
            }
            r.setOnErrorListener { _, _, _ -> main.post { onError?.invoke() } }
            r.prepare()
            r.start()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun pause(): Boolean = try { recorder?.pause(); recorder != null } catch (e: Exception) { false }
    override fun resume(): Boolean = try { recorder?.resume(); recorder != null } catch (e: Exception) { false }

    override fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try { r.stop(); true } catch (e: RuntimeException) { false } finally { r.release() }
    }

    override fun abort() {
        val r = recorder ?: return
        recorder = null
        try { r.release() } catch (e: Exception) { /* nothing to recover */ }
    }
}

/** `MediaPlayer` over a file of the note bundle. Debug builds trace load/start/pause/complete/error to [VoiceLog]. */
class MediaPlayerEngine : PlayerEngine {
    override var onCompleted: (() -> Unit)? = null
    override var onError: (() -> Unit)? = null
    private var player: MediaPlayer? = null
    private var name = "?"

    override fun prepare(file: File): Boolean {
        val p = MediaPlayer()
        player = p
        name = file.name
        return try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { VoiceLog.d("completed $name at ${positionMs}ms of ${durationMs}ms"); onCompleted?.invoke() }
            p.setOnErrorListener { _, what, extra -> VoiceLog.d("error $name what=$what extra=$extra"); onError?.invoke(); true }
            p.prepare()
            VoiceLog.d("prepared $name (${file.length()} bytes) duration=${durationMs}ms")
            true
        } catch (e: Exception) {
            VoiceLog.d("prepare $name failed: $e (exists=${file.isFile}, bytes=${file.length()})")
            false
        }
    }

    override val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0).coerceAtLeast(0)
    override val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    override fun start() {
        runCatching { player?.start() }.onFailure { VoiceLog.d("start $name failed: $it") }
        VoiceLog.d("start $name at ${positionMs}ms")
    }
    override fun pause() {
        runCatching { player?.pause() }.onFailure { VoiceLog.d("pause $name failed: $it") }
        VoiceLog.d("pause $name at ${positionMs}ms")
    }
    override fun seekTo(ms: Int) { runCatching { player?.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) } }

    override fun setSpeed(speed: Float) {
        val p = player ?: return
        runCatching {
            // Setting params on a paused player would start it, so keep its state.
            val playing = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(speed)
            if (!playing) p.pause()
        }.onFailure { VoiceLog.d("speed $speed on $name failed: $it") }
    }

    override fun release() {
        val p = player ?: return
        player = null
        runCatching { p.release() }
    }
}

/** Monotonic clock for the recorder timer. */
internal fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
