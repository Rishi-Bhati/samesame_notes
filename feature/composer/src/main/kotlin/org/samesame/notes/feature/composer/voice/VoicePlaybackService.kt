package org.samesame.notes.feature.composer.voice

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.samesame.notes.feature.composer.R

/**
 * Keeps a voice playback going when the editor is left for another app or the screen goes off: a media session over the
 * process-wide [PlaybackController] gives the notification (play/pause, back/forward 10 s, stop) and the lock-screen
 * controls. It also takes the audio focus and pauses when the headphones are unplugged. It ends with the playback
 * (the note closing, the last recording ending, Stop). A locked note never starts it (the editor pauses it instead).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VoicePlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null
    private var player: VoiceSessionPlayer? = null
    private var ticker: Job? = null
    private var focusRequest: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var noisyRegistered = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) VoicePlaybackHub.peek()?.pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val c = VoicePlaybackHub.peek() ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; c.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (c.state.value.playing) { pausedByFocus = true; c.pause() }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; c.togglePlay() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val controller = VoicePlaybackHub.get()
        val p = VoiceSessionPlayer(controller, getString(R.string.feature_composer_voice_title))
        player = p
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val s = MediaSession.Builder(this, p).apply { open?.let(::setSessionActivity) }.build()
        s.setMediaButtonPreferences(
            ImmutableList.of(
                CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10).setPlayerCommand(Player.COMMAND_SEEK_BACK)
                    .setDisplayName(getString(R.string.feature_composer_voice_rewind)).build(),
                CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10).setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                    .setDisplayName(getString(R.string.feature_composer_voice_forward)).build(),
                CommandButton.Builder(CommandButton.ICON_STOP).setPlayerCommand(Player.COMMAND_STOP)
                    .setDisplayName(getString(R.string.feature_composer_voice_stop)).build(),
            ),
        )
        session = s
        var wasPlaying = false
        scope.launch {
            controller.state.collect { st ->
                if (st.objectId == null) { stopSelf(); return@collect }
                if (st.playing && !wasPlaying) onPlayStarted(controller) else if (!st.playing && wasPlaying) onPaused()
                wasPlaying = st.playing
                p.refresh()
            }
        }
        // The controller's position only moves when refreshed; the editor does it while it is on screen, the service does it in the background.
        ticker = scope.launch { while (true) { delay(TICK_MS); controller.refresh() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private fun onPlayStarted(controller: PlaybackController) {
        if (!requestFocus()) { controller.pause(); return }
        if (!noisyRegistered) {
            registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
    }

    private fun onPaused() {
        if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false }
        // After a transient focus loss the focus stays requested, so the gain can resume playback.
        if (!pausedByFocus) abandonFocus()
    }

    private fun requestFocus(): Boolean {
        if (focusRequest != null) return true
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        val granted = am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = req
        return granted
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
        focusRequest = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from Recents ends the playback (the note is closed with it).
        VoicePlaybackHub.peek()?.close()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (noisyRegistered) { unregisterReceiver(noisy); noisyRegistered = false }
        abandonFocus()
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 500L

        /** Starts the service for the playback that just began (the app is in the foreground, so a plain start is allowed; the session promotes it while playing). */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, VoicePlaybackService::class.java))
            } catch (e: RuntimeException) {
                // Not allowed right now: the playback just stops with the editor, like before.
            }
        }
    }
}
