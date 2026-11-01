package org.samesame.notes.feature.composer.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoicePlaylistTest {
    private val three = VoicePlaylist(listOf(10_000, 20_000, 5_000))

    @Test fun totalIsTheSumAndStartsAreCumulative() {
        assertThat(three.totalMs).isEqualTo(35_000)
        assertThat(three.startOf(0)).isEqualTo(0)
        assertThat(three.startOf(1)).isEqualTo(10_000)
        assertThat(three.startOf(2)).isEqualTo(30_000)
        assertThat(three.startOf(9)).isEqualTo(0)
    }

    @Test fun locateMapsInsideRecordings() {
        assertThat(three.locate(0)).isEqualTo(VoicePlaylist.Spot(0, 0))
        assertThat(three.locate(4_000)).isEqualTo(VoicePlaylist.Spot(0, 4_000))
        assertThat(three.locate(10_001)).isEqualTo(VoicePlaylist.Spot(1, 1))
        assertThat(three.locate(32_500)).isEqualTo(VoicePlaylist.Spot(2, 2_500))
    }

    @Test fun aBoundaryBelongsToTheEndOfTheEarlierRecording() {
        assertThat(three.locate(10_000)).isEqualTo(VoicePlaylist.Spot(0, 10_000))
        assertThat(three.locate(30_000)).isEqualTo(VoicePlaylist.Spot(1, 20_000))
    }

    @Test fun locateClampsToTheTimeline() {
        assertThat(three.locate(-5)).isEqualTo(VoicePlaylist.Spot(0, 0))
        assertThat(three.locate(35_000)).isEqualTo(VoicePlaylist.Spot(2, 5_000))
        assertThat(three.locate(99_999)).isEqualTo(VoicePlaylist.Spot(2, 5_000))
        assertThat(three.clamp(-1)).isEqualTo(0)
        assertThat(three.clamp(40_000)).isEqualTo(35_000)
    }

    @Test fun positionOfIsTheInverseOfLocate() {
        for (p in listOf(0L, 1L, 9_999L, 10_001L, 29_999L, 30_001L, 35_000L)) {
            val s = three.locate(p)!!
            assertThat(three.positionOf(s.index, s.offsetMs)).isEqualTo(p)
        }
    }

    @Test fun positionOfCapsTheOffsetAtTheRecordingAndRejectsBadIndexes() {
        assertThat(three.positionOf(0, 50_000)).isEqualTo(10_000)
        assertThat(three.positionOf(1, -3)).isEqualTo(10_000)
        assertThat(three.positionOf(7, 100)).isEqualTo(0)
    }

    @Test fun emptyList() {
        val p = VoicePlaylist(emptyList<Long>())
        assertThat(p.isEmpty).isTrue()
        assertThat(p.totalMs).isEqualTo(0)
        assertThat(p.locate(0)).isNull()
        assertThat(p.clamp(500)).isEqualTo(0)
    }

    @Test fun singleRecording() {
        val p = VoicePlaylist(listOf(7_000))
        assertThat(p.locate(7_000)).isEqualTo(VoicePlaylist.Spot(0, 7_000))
        assertThat(p.locate(3_000)).isEqualTo(VoicePlaylist.Spot(0, 3_000))
        assertThat(p.count).isEqualTo(1)
    }

    @Test fun unknownDurationsCountAsZero() {
        val p = VoicePlaylist(listOf(10_000, 0, 5_000, -4))
        assertThat(p.totalMs).isEqualTo(15_000)
        assertThat(p.startOf(2)).isEqualTo(10_000)
        assertThat(p.locate(10_000)).isEqualTo(VoicePlaylist.Spot(0, 10_000))
        assertThat(p.locate(12_000)).isEqualTo(VoicePlaylist.Spot(2, 2_000))
        // Everything unknown: nothing to seek in, but no crash.
        val u = VoicePlaylist(listOf(0, 0))
        assertThat(u.totalMs).isEqualTo(0)
        assertThat(u.locate(5)).isEqualTo(VoicePlaylist.Spot(0, 0))
    }

    @Test fun durationRuleUsesElapsedThenDisplayText() {
        assertThat(VoicePlaylist.durationOf(4_200, 9)).isEqualTo(4_200)
        assertThat(VoicePlaylist.durationOf(0, 9)).isEqualTo(9_000)
        assertThat(VoicePlaylist.durationOf(0, 0)).isEqualTo(0)
    }
}
