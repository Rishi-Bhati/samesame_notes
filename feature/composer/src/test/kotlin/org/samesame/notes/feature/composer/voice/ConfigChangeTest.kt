package org.samesame.notes.feature.composer.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Rotation (a configuration change) must not pause, close or rewind the process-wide player. */
class ConfigChangeTest {
    private val engines = mutableListOf<FakePlayerEngine>()
    private fun playing(): PlaybackController {
        val c = PlaybackController { FakePlayerEngine().also { engines += it } }
        c.setTracks(listOf(Track("a", File("a.m4a"), 10_000), Track("b", File("b.m4a"), 20_000)))
        c.playTrack("b")
        engines.last().position = 7_000
        c.refresh()
        return c
    }

    @Test fun stopDuringRotationKeepsPlaying() {
        val c = playing()
        c.onEditorStopped(changingConfigurations = true, locked = true)
        assertThat(c.state.value.playing).isTrue()
        assertThat(engines.last().playing).isTrue()
    }

    @Test fun stopOfAnUnlockedNoteKeepsPlayingInTheBackground() {
        val c = playing()
        c.onEditorStopped(changingConfigurations = false, locked = false)
        assertThat(c.state.value.playing).isTrue()
        assertThat(engines.last().playing).isTrue()
    }

    @Test fun stopOfALockedNotePauses() {
        val c = playing()
        c.onEditorStopped(changingConfigurations = false, locked = true)
        assertThat(c.state.value.playing).isFalse()
        assertThat(engines.last().playing).isFalse()
        assertThat(c.state.value.positionMs).isEqualTo(17_000)
    }

    @Test fun disposeDuringRotationKeepsThePlayerAndItsPosition() {
        val c = playing()
        c.onEditorDisposed(changingConfigurations = true)
        // The recreated editor registers the same tracks again.
        c.setTracks(listOf(Track("a", File("a.m4a"), 10_000), Track("b", File("b.m4a"), 20_000)))
        assertThat(engines).hasSize(1)
        assertThat(engines.single().released).isFalse()
        assertThat(c.state.value.objectId).isEqualTo("b")
        assertThat(c.state.value.playing).isTrue()
        assertThat(c.state.value.positionMs).isEqualTo(17_000)
    }

    @Test fun disposeForRealClosesThePlayer() {
        val c = playing()
        c.onEditorDisposed(changingConfigurations = false)
        assertThat(c.state.value.objectId).isNull()
        assertThat(engines.single().released).isTrue()
    }
}
