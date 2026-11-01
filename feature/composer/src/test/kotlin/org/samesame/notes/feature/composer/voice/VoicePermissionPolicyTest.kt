package org.samesame.notes.feature.composer.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.VoiceSpeed

class VoicePermissionPolicyTest {
    @Test fun grantedStarts() {
        assertThat(VoicePermissionPolicy.next(true, false, false)).isEqualTo(MicStep.Start)
        assertThat(VoicePermissionPolicy.next(true, true, true)).isEqualTo(MicStep.Start)
    }

    @Test fun firstUseAsksTheSystem() {
        assertThat(VoicePermissionPolicy.next(false, false, false)).isEqualTo(MicStep.Request)
    }

    @Test fun deniedOnceExplainsFirst() {
        assertThat(VoicePermissionPolicy.next(false, true, true)).isEqualTo(MicStep.Rationale)
    }

    @Test fun deniedForGoodPointsToSettings() {
        assertThat(VoicePermissionPolicy.next(false, false, true)).isEqualTo(MicStep.Denied)
    }

    @Test fun speedLabels() {
        assertThat(listOf(1.0f, 1.25f, 1.5f, 2.0f, 0.5f, 0.75f).map(VoiceSpeed::label))
            .containsExactly("1.0x", "1.25x", "1.5x", "2.0x", "0.5x", "0.75x").inOrder()
    }
}
