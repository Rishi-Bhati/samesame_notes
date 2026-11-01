package org.samesame.notes.feature.composer.toolbar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackPolicyTest {
    @Test
    fun chainActionsAlwaysRunEvenWhenBackIsBlocked() {
        for (a in BackAction.entries - BackAction.LeaveEditor) {
            assertThat(BackPolicy.systemBack(a, blockBack = true, autoSave = false, hasUnsaved = true)).isEqualTo(BackOutcome.Chain)
        }
    }

    @Test
    fun blockBackIgnoresTheKeyAtTopLevelButNotTheArrow() {
        assertThat(BackPolicy.systemBack(BackAction.LeaveEditor, true, true, false)).isEqualTo(BackOutcome.Ignore)
        assertThat(BackPolicy.toolbarBack(true, false)).isEqualTo(BackOutcome.Leave)
    }

    @Test
    fun autoSaveOffAsksOnlyWithUnsavedEdits() {
        assertThat(BackPolicy.systemBack(BackAction.LeaveEditor, false, false, true)).isEqualTo(BackOutcome.AskSaveOrDiscard)
        assertThat(BackPolicy.systemBack(BackAction.LeaveEditor, false, false, false)).isEqualTo(BackOutcome.Leave)
        assertThat(BackPolicy.toolbarBack(autoSave = false, hasUnsaved = true)).isEqualTo(BackOutcome.AskSaveOrDiscard)
    }

    @Test
    fun autoSaveOnLeavesDirectly() {
        assertThat(BackPolicy.systemBack(BackAction.LeaveEditor, false, true, true)).isEqualTo(BackOutcome.Leave)
    }
}
