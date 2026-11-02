package org.samesame.notes.feature.composer.voice

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.VoiceSpeed

class PlaybackStateSaverTest {

    private fun save(state: PlaybackState): Any = with(PlaybackStateSaver) { SaverScope { true }.save(state) }

    @Test
    fun openRecordingKeepsItsPositionAndComesBackPaused() {
        val state = PlaybackState(objectId = "a", playing = true, positionMs = 4_200, durationMs = 35_000, speed = 1.25f)
        val restored = PlaybackStateSaver.restore(save(state))
        assertThat(restored).isEqualTo(state.copy(playing = false))
    }

    @Test
    fun idleStateRoundTrips() {
        val state = PlaybackState(positionMs = 500, durationMs = 2_000)
        assertThat(PlaybackStateSaver.restore(save(state))).isEqualTo(state.copy(playing = false))
    }

    @Test
    fun defaultsFillInWhenTheSavedSpeedIsMissing() {
        val restored = PlaybackStateSaver.restore(arrayListOf("id", 100, 900, null))
        assertThat(restored?.speed).isEqualTo(VoiceSpeed.NORMAL)
        assertThat(PlaybackStateSaver.restore("junk")).isNull()
    }
}
