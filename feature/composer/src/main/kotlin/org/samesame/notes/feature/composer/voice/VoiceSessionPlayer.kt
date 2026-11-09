package org.samesame.notes.feature.composer.voice

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.samesame.notes.core.model.Limits

/**
 * Shows the process-wide [PlaybackController] to a media session as a one-item player (the whole playlist is one
 * timeline, like the controller's cumulative position). It plays nothing itself: every command goes straight to
 * the controller and the service calls [invalidateState] when the controller's state changes. Main thread only.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class VoiceSessionPlayer(
    private val controller: PlaybackController,
    private val title: String,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    fun refresh() = invalidateState()

    override fun getState(): State {
        val s = controller.state.value
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_STOP, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            COMMAND_SET_SPEED_AND_PITCH, COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_METADATA, COMMAND_GET_TIMELINE,
        ).build()
        val b = State.Builder()
            .setAvailableCommands(commands)
            .setSeekBackIncrementMs(Limits.VOICE_SEEK_STEP_MS.toLong())
            .setSeekForwardIncrementMs(Limits.VOICE_SEEK_STEP_MS.toLong())
        if (s.objectId == null) return b.setPlaybackState(STATE_IDLE).build()
        val meta = MediaMetadata.Builder().setTitle(title).build()
        val item = MediaItemData.Builder(s.objectId)
            .setMediaItem(MediaItem.Builder().setMediaId(s.objectId).setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            .setDurationUs(if (s.durationMs > 0) s.durationMs * 1000L else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
        return b.setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(STATE_READY)
            .setPlayWhenReady(s.playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(s.positionMs.toLong())
            .setPlaybackParameters(PlaybackParameters(s.speed))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady != controller.state.value.playing) {
            if (playWhenReady) controller.togglePlay() else controller.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.close()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        controller.seekTo(positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> =
        Futures.immediateVoidFuture()
}
