package org.samesame.notes.feature.composer.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class FakePlayerEngine(val durations: Map<String, Int> = emptyMap(), val prepareOk: Boolean = true) : PlayerEngine {
    var duration = 60_000
    var file: File? = null
    override var onCompleted: (() -> Unit)? = null
    override var onError: (() -> Unit)? = null
    var position = 0
    var playing = false
    var released = false
    var appliedSpeed = 1f
    override fun prepare(file: File): Boolean { this.file = file; duration = durations[file.name] ?: 60_000; return prepareOk }
    override val durationMs get() = duration
    override val positionMs get() = position
    override fun start() { playing = true }
    override fun pause() { playing = false }
    override fun seekTo(ms: Int) { position = ms }
    override fun setSpeed(speed: Float) { appliedSpeed = speed }
    override fun release() { released = true; playing = false }
}

class PlaybackControllerTest {
    private val engines = mutableListOf<FakePlayerEngine>()
    private val file = File("x.m4a")
    private val lengths = mutableMapOf<String, Int>()
    private fun controller() = PlaybackController { FakePlayerEngine(lengths).also { engines += it } }
    private fun track(id: String, ms: Long = 10_000) = Track(id, File("$id.m4a"), ms)

    /** Three recordings of 10 s, 20 s and 5 s. */
    private fun playlist(): PlaybackController = controller().also { it.setTracks(listOf(track("a", 10_000), track("b", 20_000), track("c", 5_000))) }
    private val PlaybackController.pos get() = state.value.positionMs
    private val last get() = engines.last()

    // ---- single page voice object (tap)
    @Test fun tapPlaysFromTheStartAndSecondTapPausesAndResumes() {
        val c = controller()
        assertThat(c.tap("a", file)).isTrue()
        assertThat(c.state.value.playing).isTrue()
        assertThat(c.state.value.objectId).isEqualTo("a")
        assertThat(c.state.value.durationMs).isEqualTo(60_000)
        assertThat(c.tap("a", file)).isTrue()
        assertThat(c.state.value.playing).isFalse()
        c.tap("a", file)
        assertThat(c.state.value.playing).isTrue()
        assertThat(engines).hasSize(1)
    }

    @Test fun onlyOnePlayerAtATime() {
        val c = controller()
        c.tap("a", file)
        c.tap("b", file)
        assertThat(engines).hasSize(2)
        assertThat(engines[0].released).isTrue()
        assertThat(c.state.value.objectId).isEqualTo("b")
        assertThat(engines[1].playing).isTrue()
    }

    @Test fun singleObjectFinishingClosesThePanel() {
        val c = controller()
        c.tap("a", file)
        engines.single().onCompleted!!.invoke()
        assertThat(c.state.value.objectId).isNull()
        assertThat(c.state.value.playing).isFalse()
        assertThat(engines.single().released).isTrue()
    }

    @Test fun aBrokenFileIsReportedAndLeavesNothingOpen() {
        val c = PlaybackController { FakePlayerEngine(prepareOk = false) }
        assertThat(c.tap("a", file)).isFalse()
        assertThat(c.state.value.objectId).isNull()
    }

    // ---- playlist
    @Test fun idleStateShowsTheCumulativeTotal() {
        val c = playlist()
        assertThat(c.state.value.durationMs).isEqualTo(35_000)
        assertThat(c.pos).isEqualTo(0)
        assertThat(c.state.value.objectId).isNull()
    }

    @Test fun playFromIdleStartsTheFirstRecording() {
        val c = playlist()
        assertThat(c.togglePlay()).isTrue()
        assertThat(c.state.value.objectId).isEqualTo("a")
        assertThat(c.state.value.playing).isTrue()
        assertThat(last.file!!.name).isEqualTo("a.m4a")
    }

    @Test fun playGoesOnWithTheNextRecordingAndStopsAtTheEnd() {
        val c = playlist()
        c.togglePlay()
        last.onCompleted!!.invoke()
        assertThat(c.state.value.objectId).isEqualTo("b")
        assertThat(c.state.value.playing).isTrue()
        assertThat(c.pos).isEqualTo(10_000)
        assertThat(engines[0].released).isTrue()
        last.position = 4_000
        c.refresh()
        assertThat(c.pos).isEqualTo(14_000)
        last.onCompleted!!.invoke()
        assertThat(c.state.value.objectId).isEqualTo("c")
        assertThat(c.pos).isEqualTo(30_000)
        last.onCompleted!!.invoke()
        // the reference: after the last recording playback stops and the bar is back at 0, panel idle.
        assertThat(c.state.value.playing).isFalse()
        assertThat(c.state.value.objectId).isNull()
        assertThat(c.pos).isEqualTo(0)
        assertThat(c.state.value.durationMs).isEqualTo(35_000)
        assertThat(last.released).isTrue()
    }

    @Test fun playAgainAfterTheEndStartsFromTheTop() {
        val c = playlist()
        c.togglePlay(); last.onCompleted!!.invoke(); last.onCompleted!!.invoke(); last.onCompleted!!.invoke()
        c.togglePlay()
        assertThat(c.state.value.objectId).isEqualTo("a")
        assertThat(c.state.value.playing).isTrue()
    }

    @Test fun seekAcrossBoundariesSwitchesRecordingKeepingPlayState() {
        val c = playlist()
        c.togglePlay()
        c.seekTo(25_000)
        assertThat(c.state.value.objectId).isEqualTo("b")
        assertThat(last.position).isEqualTo(15_000)
        assertThat(last.playing).isTrue()
        assertThat(c.pos).isEqualTo(25_000)
        c.seekTo(2_000)
        assertThat(c.state.value.objectId).isEqualTo("a")
        assertThat(last.position).isEqualTo(2_000)
    }

    @Test fun seekWithinTheSameRecordingReusesThePlayer() {
        val c = playlist()
        c.togglePlay()
        c.seekTo(3_000)
        assertThat(engines).hasSize(1)
        assertThat(engines[0].position).isEqualTo(3_000)
    }

    @Test fun seekWhilePausedStaysPaused() {
        val c = playlist()
        c.togglePlay(); c.togglePlay()
        c.seekTo(32_000)
        assertThat(c.state.value.objectId).isEqualTo("c")
        assertThat(c.state.value.playing).isFalse()
        assertThat(last.playing).isFalse()
        assertThat(c.pos).isEqualTo(32_000)
        c.togglePlay()
        assertThat(last.playing).isTrue()
        assertThat(last.position).isEqualTo(2_000)
    }

    @Test fun seekWhileIdleIsRememberedAndPlayStartsThere() {
        val c = playlist()
        c.seekTo(12_000)
        assertThat(c.pos).isEqualTo(12_000)
        assertThat(engines).isEmpty()
        c.togglePlay()
        assertThat(c.state.value.objectId).isEqualTo("b")
        assertThat(last.position).isEqualTo(2_000)
    }

    @Test fun skipButtonsMoveTenSecondsAcrossRecordingsAndClampAtZero() {
        val c = playlist()
        c.togglePlay()
        c.seekTo(8_000)
        c.forward()
        assertThat(c.pos).isEqualTo(18_000)
        assertThat(c.state.value.objectId).isEqualTo("b")
        c.rewind()
        assertThat(c.pos).isEqualTo(8_000)
        assertThat(c.state.value.objectId).isEqualTo("a")
        c.rewind()
        assertThat(c.pos).isEqualTo(0)
        c.rewind()
        assertThat(c.pos).isEqualTo(0)
    }

    @Test fun seekingToTheEndWhilePlayingEndsPlaybackLikeTheLastRecordingEnding() {
        val c = playlist()
        c.togglePlay()
        c.seekTo(99_000)
        assertThat(c.state.value.objectId).isNull()
        assertThat(c.pos).isEqualTo(0)
    }

    @Test fun forwardNearTheEndWrapsToStoppedAtZero() {
        val c = playlist()
        c.togglePlay()
        c.seekTo(30_000)
        c.forward()
        assertThat(c.state.value.playing).isFalse()
        assertThat(c.pos).isEqualTo(0)
    }

    @Test fun forwardNearTheEndWhilePausedGoesBackToZeroAndStaysOpen() {
        val c = playlist()
        c.togglePlay(); c.seekTo(30_000); c.togglePlay()
        c.forward()
        assertThat(c.pos).isEqualTo(0)
        assertThat(c.state.value.playing).isFalse()
        assertThat(c.state.value.objectId).isEqualTo("a")
    }

    @Test fun rowTapSeeksToThatRecordingStartAndPlaysFromAnyState() {
        val c = playlist()
        assertThat(c.playTrack("b")).isTrue()
        assertThat(c.state.value.objectId).isEqualTo("b")
        assertThat(c.pos).isEqualTo(10_000)
        assertThat(c.state.value.playing).isTrue()
        c.togglePlay() // pause
        c.playTrack("c")
        assertThat(c.state.value.playing).isTrue()
        assertThat(c.pos).isEqualTo(30_000)
        c.seekTo(32_000)
        c.playTrack("c") // same row again restarts it
        assertThat(c.pos).isEqualTo(30_000)
        assertThat(last.position).isEqualTo(0)
    }

    @Test fun rowTapOfAnUnknownIdDoesNothing() {
        val c = playlist()
        assertThat(c.playTrack("zzz")).isFalse()
        assertThat(engines).isEmpty()
    }

    @Test fun speedCyclesLikeReferenceAndSticksAcrossRecordings() {
        val c = playlist()
        c.togglePlay()
        val seen = (1..6).map { c.cycleSpeed(); c.state.value.speed }
        assertThat(seen).containsExactly(1.25f, 1.5f, 2.0f, 0.5f, 0.75f, 1.0f).inOrder()
        c.cycleSpeed()
        assertThat(engines[0].appliedSpeed).isEqualTo(1.25f)
        last.onCompleted!!.invoke()
        assertThat(engines[1].appliedSpeed).isEqualTo(1.25f)
        c.seekTo(2_000)
        assertThat(last.appliedSpeed).isEqualTo(1.25f)
    }

    @Test fun pauseKeepsTheCumulativePosition() {
        val c = playlist()
        c.playTrack("b")
        last.position = 7_000
        c.pause()
        assertThat(c.pos).isEqualTo(17_000)
        assertThat(c.state.value.playing).isFalse()
    }

    @Test fun pausedAtTheVeryEndPlayStartsOver() {
        val c = playlist()
        c.togglePlay(); c.seekTo(34_000); c.togglePlay()
        c.seekTo(35_000)
        assertThat(c.pos).isEqualTo(35_000)
        c.togglePlay()
        assertThat(c.state.value.objectId).isEqualTo("a")
        assertThat(c.pos).isEqualTo(0)
    }

    @Test fun unknownDurationsAreLearnedFromThePlayer() {
        lengths["u.m4a"] = 8_000
        val c = controller()
        c.setTracks(listOf(track("u", 0), track("v", 4_000)))
        assertThat(c.state.value.durationMs).isEqualTo(4_000)
        c.playTrack("u")
        assertThat(c.state.value.durationMs).isEqualTo(12_000)
        last.onCompleted!!.invoke()
        assertThat(c.pos).isEqualTo(8_000)
        assertThat(c.state.value.objectId).isEqualTo("v")
    }

    @Test fun deletingThePlayingRecordingStopsPlayback() {
        val c = playlist()
        c.playTrack("b")
        c.setTracks(listOf(track("a", 10_000), track("c", 5_000)))
        assertThat(c.state.value.objectId).isNull()
        assertThat(c.state.value.playing).isFalse()
        assertThat(engines.last().released).isTrue()
        assertThat(c.state.value.durationMs).isEqualTo(15_000)
    }

    @Test fun deletingAnotherRecordingKeepsPlayingAndRebasesThePosition() {
        val c = playlist()
        c.playTrack("c")
        last.position = 1_000
        c.setTracks(listOf(track("b", 20_000), track("c", 5_000)))
        assertThat(c.state.value.objectId).isEqualTo("c")
        assertThat(c.state.value.playing).isTrue()
        assertThat(c.pos).isEqualTo(21_000)
        assertThat(c.state.value.durationMs).isEqualTo(25_000)
        // And it continues past the new last one correctly.
        last.onCompleted!!.invoke()
        assertThat(c.state.value.objectId).isNull()
    }

    @Test fun renamingKeepsPlayingBecauseIdsAreStable() {
        val c = playlist()
        c.playTrack("b")
        c.setTracks(listOf(track("a", 10_000), track("b", 20_000), track("c", 5_000)))
        assertThat(c.state.value.playing).isTrue()
        assertThat(engines).hasSize(1)
    }

    @Test fun addingARecordingExtendsTheTotalWithoutRestarting() {
        val c = playlist()
        c.playTrack("a")
        c.setTracks(listOf(track("a", 10_000), track("b", 20_000), track("c", 5_000), track("d", 3_000)))
        assertThat(c.state.value.durationMs).isEqualTo(38_000)
        assertThat(c.state.value.playing).isTrue()
    }

    @Test fun emptyPlaylistCannotPlay() {
        val c = controller()
        assertThat(c.togglePlay()).isFalse()
        c.seekTo(500)
        assertThat(c.pos).isEqualTo(0)
        assertThat(engines).isEmpty()
    }

    @Test fun aMissingFileEndsPlaybackInsteadOfCrashing() {
        val c = controller()
        c.setTracks(listOf(track("a", 10_000), Track("b", null, 10_000)))
        c.togglePlay()
        last.onCompleted!!.invoke()
        assertThat(c.state.value.objectId).isNull()
        assertThat(c.state.value.playing).isFalse()
        assertThat(c.playTrack("b")).isFalse()
    }

    @Test fun aStaleEnginesCompletionIsIgnored() {
        val c = playlist()
        c.togglePlay()
        val old = engines[0]
        c.seekTo(15_000)
        old.onCompleted!!.invoke()
        assertThat(c.state.value.objectId).isEqualTo("b")
        assertThat(engines).hasSize(2)
    }

    @Test fun refreshFollowsThePlayerWhilePlaying() {
        val c = playlist()
        c.togglePlay()
        last.position = 6_345
        c.refresh()
        assertThat(c.pos).isEqualTo(6_345)
    }

    @Test fun closeReleasesThePlayerAndResetsToIdle() {
        val c = playlist()
        c.playTrack("b")
        c.close()
        assertThat(engines.single().released).isTrue()
        assertThat(c.state.value.objectId).isNull()
        assertThat(c.state.value.playing).isFalse()
        assertThat(c.pos).isEqualTo(0)
        assertThat(c.state.value.durationMs).isEqualTo(35_000)
    }

    @Test fun tapOnAPageObjectLeavesThePlaylistRegisteredForLater() {
        val c = playlist()
        c.tap("chip", file)
        c.setTracks(listOf(track("a", 10_000)))
        assertThat(c.state.value.objectId).isEqualTo("chip")
        c.close()
        assertThat(c.state.value.durationMs).isEqualTo(10_000)
    }
}
