package org.samesame.notes.feature.composer.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.samesame.notes.feature.composer.R

/**
 * Foreground host of a voice recording (type microphone) with the Record / Pause / Stop notification. The recorder
 * itself lives in [VoiceRecordingHub]; this service keeps the process alive, shows the notification, forwards its
 * actions, watches the audio focus (a phone call saves the recording, like the reference's) and stops itself when the
 * recorder goes idle.
 */
class VoiceRecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var foregrounded = false
    private var idleSeen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = VoiceRecordingHub.get(this)
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP -> controller.stop()
        }
        if (!foregrounded) {
            if (!controller.isActive) { stopSelf(); return START_NOT_STICKY }
            promote(controller.state.value as RecorderState.Active)
            requestFocus(controller)
            watcher = scope.launch {
                controller.state.collect { s ->
                    when (s) {
                        is RecorderState.Active -> notificationManager().notify(NOTIFICATION_ID, build(s))
                        RecorderState.Idle -> { idleSeen = true; release(); ServiceCompat.stopForeground(this@VoiceRecordService, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(s: RecorderState.Active) {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(s), type)
        foregrounded = true
    }

    private fun build(s: RecorderState.Active): android.app.Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.feature_composer_ic_notification_mic)
            .setContentTitle(getString(if (s.paused) R.string.feature_composer_voice_paused else R.string.feature_composer_voice_recording))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // The system chronometer follows the elapsed time without the service ticking; while paused it shows a static time.
        val elapsed = s.elapsedMs(android.os.SystemClock.elapsedRealtime())
        b.setShowWhen(true).setUsesChronometer(!s.paused)
            .setWhen(System.currentTimeMillis() - elapsed)
        if (s.paused) b.setContentText(org.samesame.notes.feature.composer.canvas.ObjectPainters.formatDuration(elapsed))
        if (s.paused) b.addAction(0, getString(R.string.feature_composer_voice_resume), action(ACTION_RESUME))
        else b.addAction(0, getString(R.string.feature_composer_voice_pause), action(ACTION_PAUSE))
        b.addAction(0, getString(R.string.feature_composer_voice_stop), action(ACTION_STOP))
        return b.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(), Intent(this, VoiceRecordService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.feature_composer_voice_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun requestFocus(controller: RecorderController) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) controller.interrupt()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun release() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onDestroy() {
        release()
        scope.cancel()
        // The system killed the service while recording: keep what was recorded.
        if (!idleSeen) VoiceRecordingHub.peek()?.let { if (it.isActive) it.stop() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_recording"
        private const val NOTIFICATION_ID = 0x5EC0
        private const val ACTION_PAUSE = "org.samesame.notes.voice.PAUSE"
        private const val ACTION_RESUME = "org.samesame.notes.voice.RESUME"
        private const val ACTION_STOP = "org.samesame.notes.voice.STOP"

        /** Starts the foreground service for the recording that was just started on [VoiceRecordingHub]. Returns false when the system refused. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, VoiceRecordService::class.java))
            true
        } catch (e: RuntimeException) {
            false
        }
    }
}
