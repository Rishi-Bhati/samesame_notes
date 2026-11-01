package org.samesame.notes.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollBarHideTimerTest {

    @Test fun hidesOnceTheDelayPassedAfterTheLastPoke() = runTest {
        val timer = ScrollBarHideTimer(300L) { testScheduler.currentTime }
        var hidden = 0
        val job = launch { timer.run { hidden++ } }
        runCurrent()
        timer.poke()
        advanceTimeBy(200); runCurrent()
        timer.poke() // scrolled again: the deadline moves, nothing restarts
        advanceTimeBy(250); runCurrent()
        assertThat(hidden).isEqualTo(0)
        advanceTimeBy(60); runCurrent()
        assertThat(hidden).isEqualTo(1)
        assertThat(timer.pending).isFalse()
        advanceTimeBy(5_000); runCurrent()
        assertThat(hidden).isEqualTo(1) // idle: no further hides
        job.cancel()
    }

    @Test fun manyPokesPerFrameStillHideOnce() = runTest {
        val timer = ScrollBarHideTimer(300L) { testScheduler.currentTime }
        var hidden = 0
        val job = launch { timer.run { hidden++ } }
        runCurrent()
        repeat(60) { advanceTimeBy(16); runCurrent(); timer.poke() } // one second of scrolling
        assertThat(hidden).isEqualTo(0)
        advanceTimeBy(301); runCurrent()
        assertThat(hidden).isEqualTo(1)
        job.cancel()
    }

    @Test fun cancelKeepsTheBarUntilTheNextPoke() = runTest {
        val timer = ScrollBarHideTimer(300L) { testScheduler.currentTime }
        var hidden = 0
        val job = launch { timer.run { hidden++ } }
        runCurrent()
        timer.poke()
        timer.cancel() // thumb held
        advanceTimeBy(1_000); runCurrent()
        assertThat(hidden).isEqualTo(0)
        timer.poke() // released
        advanceTimeBy(301); runCurrent()
        assertThat(hidden).isEqualTo(1)
        job.cancel()
    }
}
