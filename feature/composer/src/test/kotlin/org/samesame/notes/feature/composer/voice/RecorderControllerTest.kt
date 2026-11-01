package org.samesame.notes.feature.composer.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.Limits
import java.io.File

internal class FakeAudioEngine(var startOk: Boolean = true, var stopOk: Boolean = true) : AudioEngine {
    override var onLimitReached: (() -> Unit)? = null
    override var onError: (() -> Unit)? = null
    val calls = mutableListOf<String>()
    private var file: File? = null

    override fun start(file: File): Boolean {
        calls += "start"
        if (!startOk) return false
        this.file = file
        file.writeBytes(ByteArray(10))
        return true
    }
    override fun pause(): Boolean { calls += "pause"; return true }
    override fun resume(): Boolean { calls += "resume"; return true }
    override fun stop(): Boolean { calls += "stop"; return stopOk }
    override fun abort() { calls += "abort" }
}

class RecorderControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private val engines = mutableListOf<FakeAudioEngine>()
    private var nextEngine: () -> FakeAudioEngine = { FakeAudioEngine() }

    private fun controller() = RecorderController(
        engineFactory = { nextEngine().also { engines += it } },
        tempDir = { tmp.root },
        clock = { now },
    )

    private val anchor = RecordingAnchor("p1", 540f, 300f)

    @Test fun recordPauseResumeStopYieldsASavedResultWithTheRecordedTime() {
        val c = controller()
        assertThat(c.start("n1", anchor)).isEqualTo(StartOutcome.Started)
        now += 3_000
        assertThat(c.pause()).isTrue()
        now += 10_000 // paused time does not count
        val paused = c.state.value as RecorderState.Active
        assertThat(paused.paused).isTrue()
        assertThat(paused.elapsedMs(now)).isEqualTo(3_000)
        assertThat(c.resume()).isTrue()
        now += 2_000
        assertThat((c.state.value as RecorderState.Active).elapsedMs(now)).isEqualTo(5_000)
        c.stop()

        assertThat(c.state.value).isEqualTo(RecorderState.Idle)
        assertThat(engines.single().calls).containsExactly("start", "pause", "resume", "stop").inOrder()
        val r = c.results.value.single() as RecordingResult.Saved
        assertThat(r.noteId).isEqualTo("n1")
        assertThat(r.anchor).isEqualTo(anchor)
        assertThat(r.durationMs).isEqualTo(5_000)
        assertThat(r.reason).isEqualTo(FinishReason.Stopped)
        assertThat(r.file.exists()).isTrue()
    }

    @Test fun stopWhilePausedKeepsThePausedTime() {
        val c = controller()
        c.start("n1", anchor)
        now += 4_000
        c.pause()
        now += 60_000
        c.stop()
        assertThat((c.results.value.single() as RecordingResult.Saved).durationMs).isEqualTo(4_000)
    }

    @Test fun onlyOneRecordingAtATime() {
        val c = controller()
        assertThat(c.start("n1", anchor)).isEqualTo(StartOutcome.Started)
        assertThat(c.start("n2", anchor)).isEqualTo(StartOutcome.Busy)
        assertThat(engines).hasSize(1)
    }

    @Test fun pauseAndResumeOutOfOrderDoNothing() {
        val c = controller()
        assertThat(c.pause()).isFalse()
        c.start("n1", anchor)
        assertThat(c.resume()).isFalse()
        c.pause()
        assertThat(c.pause()).isFalse()
    }

    @Test fun cancelDropsTheFileAndQueuesNothing() {
        val c = controller()
        c.start("n1", anchor)
        now += 5_000
        c.cancel()
        assertThat(c.state.value).isEqualTo(RecorderState.Idle)
        assertThat(c.results.value).isEmpty()
        assertThat(engines.single().calls).containsExactly("start", "abort").inOrder()
        assertThat(tmp.root.listFiles()).isEmpty()
    }

    @Test fun aRecordingUnderTheMinimumIsDiscardedAsTooShort() {
        val c = controller()
        c.start("n1", anchor)
        now += Limits.VOICE_MIN_DURATION_MS - 1
        c.stop()
        val r = c.results.value.single() as RecordingResult.Discarded
        assertThat(r.reason).isEqualTo(DiscardReason.TooShort)
        assertThat(tmp.root.listFiles()).isEmpty()
    }

    @Test fun anUnusableFileIsDiscardedAsFailed() {
        nextEngine = { FakeAudioEngine(stopOk = false) }
        val c = controller()
        c.start("n1", anchor)
        now += 5_000
        c.stop()
        assertThat((c.results.value.single() as RecordingResult.Discarded).reason).isEqualTo(DiscardReason.Failed)
        assertThat(tmp.root.listFiles()).isEmpty()
    }

    @Test fun aMicrophoneThatCannotStartLeavesTheRecorderIdle() {
        nextEngine = { FakeAudioEngine(startOk = false) }
        val c = controller()
        assertThat(c.start("n1", anchor)).isEqualTo(StartOutcome.Failed)
        assertThat(c.state.value).isEqualTo(RecorderState.Idle)
        assertThat(c.results.value).isEmpty()
        assertThat(tmp.root.listFiles()).isEmpty()
    }

    @Test fun anInterruptionSavesTheRecording() {
        val c = controller()
        c.start("n1", anchor)
        now += 8_000
        c.interrupt()
        val r = c.results.value.single() as RecordingResult.Saved
        assertThat(r.reason).isEqualTo(FinishReason.Interrupted)
        assertThat(r.durationMs).isEqualTo(8_000)
    }

    @Test fun theEngineReachingItsCapStopsAndSaves() {
        val c = controller()
        c.start("n1", anchor)
        now += Limits.VOICE_MAX_DURATION_MS.toLong() + 500 // elapsed is clamped to the cap
        engines.single().onLimitReached!!.invoke()
        val r = c.results.value.single() as RecordingResult.Saved
        assertThat(r.reason).isEqualTo(FinishReason.MaxLength)
        assertThat(r.durationMs).isEqualTo(Limits.VOICE_MAX_DURATION_MS.toLong())
    }

    @Test fun tickStopsAtTheDurationCapButNotWhilePaused() {
        val c = controller()
        c.start("n1", anchor)
        now += Limits.VOICE_MAX_DURATION_MS - 1L
        c.tick()
        assertThat(c.isActive).isTrue()
        now += 1
        c.tick()
        assertThat(c.isActive).isFalse()
        assertThat((c.results.value.single() as RecordingResult.Saved).reason).isEqualTo(FinishReason.MaxLength)
    }

    @Test fun anEngineErrorDiscardsTheRecording() {
        val c = controller()
        c.start("n1", anchor)
        now += 5_000
        engines.single().onError!!.invoke()
        assertThat((c.results.value.single() as RecordingResult.Discarded).reason).isEqualTo(DiscardReason.Failed)
        assertThat(c.isActive).isFalse()
    }

    @Test fun closingTheEditorSavesForThatNoteAndResultsAreClaimedOnce() {
        val c = controller()
        c.start("n1", anchor)
        now += 2_000
        c.stopForClose()
        val r = c.results.value.single()
        assertThat((r as RecordingResult.Saved).reason).isEqualTo(FinishReason.EditorClosed)
        c.consume(r)
        assertThat(c.results.value).isEmpty()
        // A new recording can start right away.
        assertThat(c.start("n2", anchor)).isEqualTo(StartOutcome.Started)
    }

    @Test fun limitsMatchTheReferenceRecorder() {
        assertThat(Limits.VOICE_MAX_DURATION_MS).isEqualTo(6 * 60 * 60 * 1000)
        assertThat(Limits.VOICE_MAX_FILE_BYTES).isEqualTo(500L * 1024 * 1024)
        assertThat(Limits.VOICE_SAMPLE_RATE_HZ).isEqualTo(44_100)
        assertThat(Limits.VOICE_BIT_RATE).isEqualTo(128_000)
        assertThat(Limits.VOICE_CHANNELS).isEqualTo(2)
        assertThat(Limits.VOICE_FILE_EXTENSION).isEqualTo("m4a")
    }

    @Test fun recordingsAreStoredWithTheVoiceExtension() {
        val c = controller()
        c.start("n1", anchor)
        now += 2_000
        c.stop()
        val r = c.results.value.single() as RecordingResult.Saved
        assertThat(r.file.name).endsWith("." + Limits.VOICE_FILE_EXTENSION)
    }
}
