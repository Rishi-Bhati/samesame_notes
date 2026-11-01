package org.samesame.notes.feature.composer.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class VoiceFormatTest {
    @Test fun firstNameIsVoice001() = assertThat(VoiceFormat.nextName(emptyList())).isEqualTo("Voice 001")

    @Test fun nextNameSkipsUsedNumbersAndIgnoresOtherNames() {
        assertThat(VoiceFormat.nextName(listOf("Voice 001", "Meeting", "Voice 002"))).isEqualTo("Voice 003")
        assertThat(VoiceFormat.nextName(listOf("Voice 002"))).isEqualTo("Voice 001")
        assertThat(VoiceFormat.nextName(listOf("Voice 1", "Voice ref 1"))).isEqualTo("Voice 001")
    }

    @Test fun clockIsMinutesSeconds() {
        assertThat(VoiceFormat.clock(72_000)).isEqualTo("01:12")
        assertThat(VoiceFormat.clock(198_400)).isEqualTo("03:18")
        assertThat(VoiceFormat.clock(-5)).isEqualTo("00:00")
        assertThat(VoiceFormat.clock(3_725_000)).isEqualTo("62:05")
    }

    @Test fun listDurationIsZeroPaddedHms() {
        assertThat(VoiceFormat.listDuration(198_000)).isEqualTo("00:03:18")
        assertThat(VoiceFormat.listDuration(3_725_000)).isEqualTo("01:02:05")
    }

    @Test fun listDateUsesThePattern() {
        val ms = java.util.GregorianCalendar(2026, 9, 24, 22, 15).timeInMillis
        assertThat(VoiceFormat.listDate(ms, "dd/MM, h:mm a", Locale.UK).lowercase()).isEqualTo("24/10, 10:15 pm")
        assertThat(VoiceFormat.dateSkeleton(true)).isEqualTo("MM.dd HH:mm")
        assertThat(VoiceFormat.dateSkeleton(false)).isEqualTo("MM.dd hh:mm")
    }

    @Test fun renameNeedsAChangedNonBlankName() {
        assertThat(VoiceFormat.canRename("Voice 001", "Voice 001")).isFalse()
        assertThat(VoiceFormat.canRename("Voice 001", "  ")).isFalse()
        assertThat(VoiceFormat.canRename("Voice 001", "")).isFalse()
        assertThat(VoiceFormat.canRename("Voice 001", "Ref")).isTrue()
    }
}

class VoiceRecordingListSelectTest {
    private val recs = listOf(
        VoiceRecording("a", "m/a", 1_000, "Voice 001"),
        VoiceRecording("b", "m/b", 2_000, "Voice 002"),
    )
    private fun state() = VoiceRecordingList.update(VoiceListState(), recs)

    @Test fun editStartsSelectModeWithNothingChecked() {
        val s = VoiceRecordingList.enterSelectMode(state())
        assertThat(s.selectMode).isTrue()
        assertThat(s.selected).isEmpty()
    }

    @Test fun longPressChecksTheRowAndSelectAllToggles() {
        var s = VoiceRecordingList.longPress(state(), "a")
        assertThat(s.selectMode).isTrue()
        assertThat(s.selected).containsExactly("a")
        s = VoiceRecordingList.selectAll(s)
        assertThat(s.allSelected).isTrue()
        s = VoiceRecordingList.selectAll(s)
        assertThat(s.selected).isEmpty()
    }

    @Test fun deletingEverythingLeavesSelectMode() {
        val s = VoiceRecordingList.update(VoiceRecordingList.longPress(state(), "a"), emptyList())
        assertThat(s.selectMode).isFalse()
    }
}
