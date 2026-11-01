package org.samesame.notes.feature.composer.voice

import android.content.Context
import java.io.File

/**
 * Process-wide recorder: the foreground service, the notification actions and the editors all talk to this one
 * [RecorderController], so a recording survives leaving the editor.
 */
object VoiceRecordingHub {
    @Volatile private var instance: RecorderController? = null

    fun get(context: Context): RecorderController {
        instance?.let { return it }
        val app = context.applicationContext
        return synchronized(this) {
            instance ?: RecorderController(
                engineFactory = { MediaRecorderEngine(app) },
                // noBackupFilesDir: a half-written recording must not be restored by a backup, and the cache may be cleared mid-recording.
                tempDir = { recordingsDir(app) },
                clock = ::elapsedRealtimeMs,
            ).also { instance = it }
        }
    }

    /** The controller if it was ever created (no recording can be running otherwise). */
    fun peek(): RecorderController? = instance

    private fun recordingsDir(context: Context) = File(context.noBackupFilesDir, "voice-recordings")

    /**
     * Deletes recordings left by a process that died before they were added to their note (they are plaintext,
     * possibly of a locked note; security review L1). Only files older than [processStartMs] are touched, so a
     * recording started since is never hit.
     */
    fun discardOrphans(context: Context, processStartMs: Long) {
        if (instance?.isActive == true) return
        recordingsDir(context).listFiles()?.forEach { if (it.lastModified() < processStartMs) it.delete() }
    }
}

/** Process-wide player: one voice object plays at a time across editors. */
object VoicePlaybackHub {
    @Volatile private var instance: PlaybackController? = null

    fun get(): PlaybackController =
        instance ?: synchronized(this) { instance ?: PlaybackController(VoiceLog::d) { MediaPlayerEngine() }.also { instance = it } }

    fun peek(): PlaybackController? = instance
}
