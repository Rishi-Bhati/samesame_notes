package org.samesame.notes.feature.composer.voice

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import org.samesame.notes.core.model.VoiceSpeed

/**
 * Saves the voice player's position (and the open recording) across process death. Playback is never resumed
 * automatically: the state comes back paused, and Play continues from the saved position.
 */
object PlaybackStateSaver : Saver<PlaybackState, Any> {

    override fun SaverScope.save(value: PlaybackState): Any = arrayListOf(
        value.objectId.orEmpty(),
        value.positionMs,
        value.durationMs,
        value.speed,
    )

    override fun restore(value: Any): PlaybackState? {
        val parts = value as? List<*> ?: return null
        if (parts.size != PART_COUNT) return null
        val id = parts[0] as? String
        return PlaybackState(
            objectId = id?.takeIf { it.isNotEmpty() },
            playing = false,
            positionMs = parts[1] as? Int ?: 0,
            durationMs = parts[2] as? Int ?: 0,
            speed = parts[3] as? Float ?: VoiceSpeed.NORMAL,
        )
    }

    private const val PART_COUNT = 4
}
