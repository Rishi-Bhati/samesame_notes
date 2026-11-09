package org.samesame.notes.feature.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Live lockout countdown: ceil rounding (so "30 seconds" ticks to 29, 28, ...) and 1 s tick alignment. */
class LockoutTimerTest {
    @Test fun secondsAreCeiledAndAtLeastOneWhileLocked() {
        assertThat(lockoutSeconds(0)).isEqualTo(0)
        assertThat(lockoutSeconds(-500)).isEqualTo(0)
        assertThat(lockoutSeconds(1)).isEqualTo(1)
        assertThat(lockoutSeconds(999)).isEqualTo(1)
        assertThat(lockoutSeconds(1_000)).isEqualTo(1)
        assertThat(lockoutSeconds(1_001)).isEqualTo(2)
        assertThat(lockoutSeconds(29_000)).isEqualTo(29)
        assertThat(lockoutSeconds(29_500)).isEqualTo(30)
        assertThat(lockoutSeconds(30_000)).isEqualTo(30)
        assertThat(lockoutSeconds(30_001)).isEqualTo(31)
        assertThat(lockoutSeconds(3_600_000)).isEqualTo(3_600)
    }

    @Test fun minutesAreCeiledAndOnlyForSixtySecondsOrMore() {
        assertThat(lockoutMinutes(0)).isEqualTo(0)
        assertThat(lockoutMinutes(-1)).isEqualTo(0)
        assertThat(lockoutMinutes(60)).isEqualTo(1)
        assertThat(lockoutMinutes(61)).isEqualTo(2)
        assertThat(lockoutMinutes(119)).isEqualTo(2)
        assertThat(lockoutMinutes(120)).isEqualTo(2)
        assertThat(lockoutMinutes(121)).isEqualTo(3)
        assertThat(lockoutMinutes(3_600)).isEqualTo(60)
        // The message shows seconds under 60 and minutes at 60 or more.
        assertThat(lockoutSeconds(59_000)).isEqualTo(59)
        assertThat(lockoutSeconds(60_000)).isEqualTo(60)
    }

    @Test fun tickDelayIsAlignedToTheNextWholeSecond() {
        assertThat(nextLockoutTickDelayMs(0)).isEqualTo(0)
        assertThat(nextLockoutTickDelayMs(-1)).isEqualTo(0)
        assertThat(nextLockoutTickDelayMs(1)).isEqualTo(1)
        assertThat(nextLockoutTickDelayMs(999)).isEqualTo(999)
        assertThat(nextLockoutTickDelayMs(1_000)).isEqualTo(1_000)
        assertThat(nextLockoutTickDelayMs(1_001)).isEqualTo(1)
        assertThat(nextLockoutTickDelayMs(29_500)).isEqualTo(500)
        assertThat(nextLockoutTickDelayMs(29_000)).isEqualTo(1_000)
        assertThat(nextLockoutTickDelayMs(29_001)).isEqualTo(1)
        assertThat(nextLockoutTickDelayMs(3_600_500)).isEqualTo(500)
    }

    @Test fun countdownTicksDownOneSecondPerTickUntilExpiry() {
        var remaining = 30_000L
        // Aligned lockouts tick down by exactly one second per delay,
        // whatever the starting sub-second offset.
        repeat(3) { i ->
            remaining -= nextLockoutTickDelayMs(remaining)
            assertThat(lockoutSeconds(remaining)).isEqualTo(30 - i - 1)
        }
        assertThat(remaining).isEqualTo(27_000)
        assertThat(lockoutSeconds(remaining)).isEqualTo(27)
        var offset = 29_500L
        assertThat(lockoutSeconds(offset)).isEqualTo(30)
        offset -= nextLockoutTickDelayMs(offset)
        assertThat(offset).isEqualTo(29_000)
        assertThat(lockoutSeconds(offset)).isEqualTo(29)
        var small = 1_500L
        assertThat(lockoutSeconds(small)).isEqualTo(2)
        small -= nextLockoutTickDelayMs(small)
        assertThat(small).isEqualTo(1_000)
        assertThat(lockoutSeconds(small)).isEqualTo(1)
        small -= nextLockoutTickDelayMs(small)
        assertThat(small).isEqualTo(0)
    }
}
