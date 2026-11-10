package org.samesame.notes.feature.files.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioPlayerTest {
    private class FakeEngine(var playable: Boolean = true, override var durationMs: Int = 60_000) : AudioEngine {
        override var onCompleted: (() -> Unit)? = null
        override var onError: (() -> Unit)? = null
        override var onInterrupted: (() -> Unit)? = null
        override var positionMs = 0
        var started = false
        var lastSpeed = 1f
        var released = false
        override fun prepare() = playable
        override fun start() { started = true }
        override fun pause() { started = false }
        override fun seekTo(ms: Int) { positionMs = ms }
        override fun setSpeed(speed: Float) { lastSpeed = speed }
        override fun release() { released = true; started = false }
    }

    @Test fun loadsAndAutoPlays() {
        val e = FakeEngine()
        val p = AudioPlayer(e)
        p.load(autoPlay = true)
        assertThat(p.state.value.ready).isTrue()
        assertThat(p.state.value.playing).isTrue()
        assertThat(p.state.value.durationMs).isEqualTo(60_000)
        assertThat(e.started).isTrue()
    }

    @Test fun anUnplayableFileIsReported() {
        val p = AudioPlayer(FakeEngine(playable = false))
        p.load(autoPlay = true)
        assertThat(p.state.value.failed).isTrue()
        assertThat(p.state.value.playing).isFalse()
        p.toggle()
        assertThat(p.state.value.playing).isFalse()
    }

    @Test fun pauseKeepsThePositionAndRefreshFollowsIt() {
        val e = FakeEngine()
        val p = AudioPlayer(e).apply { load(autoPlay = true) }
        e.positionMs = 5_000
        p.refresh()
        assertThat(p.state.value.positionMs).isEqualTo(5_000)
        e.positionMs = 7_000
        p.toggle()
        assertThat(p.state.value.playing).isFalse()
        assertThat(p.state.value.positionMs).isEqualTo(7_000)
        e.positionMs = 9_000
        p.refresh() // paused: ignored
        assertThat(p.state.value.positionMs).isEqualTo(7_000)
    }

    @Test fun seekAndSkipsAreClamped() {
        val e = FakeEngine()
        val p = AudioPlayer(e).apply { load(autoPlay = false) }
        p.rewind()
        assertThat(p.state.value.positionMs).isEqualTo(0)
        p.forward()
        assertThat(e.positionMs).isEqualTo(10_000)
        p.seekTo(999_999)
        assertThat(p.state.value.positionMs).isEqualTo(60_000)
    }

    @Test fun endingStopsAtTheEndAndPlayStartsOver() {
        val e = FakeEngine()
        val p = AudioPlayer(e).apply { load(autoPlay = true) }
        e.onCompleted!!.invoke()
        assertThat(p.state.value.playing).isFalse()
        assertThat(p.state.value.positionMs).isEqualTo(60_000)
        p.toggle()
        assertThat(e.positionMs).isEqualTo(0)
        assertThat(p.state.value.positionMs).isEqualTo(0)
        assertThat(p.state.value.playing).isTrue()
    }

    @Test fun speedCyclesAndAppliesWhilePlaying() {
        val e = FakeEngine()
        val p = AudioPlayer(e).apply { load(autoPlay = false) }
        p.cycleSpeed()
        assertThat(p.state.value.speed).isEqualTo(1.25f)
        assertThat(e.lastSpeed).isEqualTo(1f) // paused: applied on the next play
        p.toggle()
        assertThat(e.lastSpeed).isEqualTo(1.25f)
        p.cycleSpeed()
        assertThat(e.lastSpeed).isEqualTo(1.5f)
    }

    @Test fun interruptionPausesAndReleaseStopsEverything() {
        val e = FakeEngine()
        val p = AudioPlayer(e).apply { load(autoPlay = true) }
        e.onInterrupted!!.invoke()
        assertThat(p.state.value.playing).isFalse()
        p.release()
        assertThat(e.released).isTrue()
        p.toggle()
        assertThat(p.state.value.playing).isFalse()
    }

    @Test fun timeLabels() {
        assertThat(AudioPlayer.time(0)).isEqualTo("0:00")
        assertThat(AudioPlayer.time(7_900)).isEqualTo("0:07")
        assertThat(AudioPlayer.time(754_000)).isEqualTo("12:34")
        assertThat(AudioPlayer.time(3_723_000)).isEqualTo("1:02:03")
    }
}
