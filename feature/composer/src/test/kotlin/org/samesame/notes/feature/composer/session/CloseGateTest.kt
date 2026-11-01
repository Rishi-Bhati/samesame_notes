package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloseGateTest {
    /** The re-opened note's session must not load before the previous session's final pack finished. */
    @Test fun reopenWaitsForPendingClose() = runTest {
        val gate = CloseGate<String>()
        var packedAt = -1L
        var openedAt = -1L
        gate.register("n1", backgroundScope.launch { delay(800); packedAt = currentTime })
        val open = backgroundScope.launch { gate.awaitClosed("n1"); openedAt = currentTime }
        runCurrent()
        assertThat(gate.isClosing("n1")).isTrue()
        advanceTimeBy(799); runCurrent()
        assertThat(open.isCompleted).isFalse()
        advanceTimeBy(2); runCurrent() // backgroundScope work is not driven by advanceUntilIdle
        assertThat(openedAt).isAtLeast(packedAt)
        assertThat(gate.isClosing("n1")).isFalse()
    }

    @Test fun otherNotesAndUnknownKeysDoNotWait() = runTest {
        val gate = CloseGate<String>()
        gate.register("n1", backgroundScope.launch { delay(10_000) })
        gate.awaitClosed("n2") // returns immediately
        assertThat(currentTime).isEqualTo(0)
    }
}
