package org.samesame.notes.feature.textdoc

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Limits

class UndoRedoStateTest {

    @Test
    fun consecutiveTypedCharactersCoalesceIntoOneStep() {
        val state = UndoRedoState()
        state.reset("a")
        state.edit("ab")
        state.edit("abc")

        assertThat(state.undo()).isEqualTo("a")
        assertThat(state.canUndo).isFalse()
        assertThat(state.redo()).isEqualTo("abc")
    }

    @Test
    fun typingSomewhereElseStartsANewStep() {
        val state = UndoRedoState()
        state.reset("")
        state.edit("a")
        state.edit("ab")
        state.edit("Xab")

        assertThat(state.undo()).isEqualTo("ab")
        assertThat(state.undo()).isEqualTo("")
    }

    @Test
    fun anEditClearsTheRedoStack() {
        val state = UndoRedoState()
        state.reset("a")
        state.edit("ab")
        assertThat(state.undo()).isEqualTo("a")

        state.edit("ax")
        assertThat(state.canRedo).isFalse()
        assertThat(state.redo()).isNull()
    }

    @Test
    fun theUndoStackKeepsAtMostTwoHundredSteps() {
        val state = UndoRedoState()
        state.reset("")
        repeat(250) { i -> state.edit("value-$i") }

        var undone = 0
        while (state.undo() != null) undone++

        assertThat(undone).isEqualTo(Limits.MAX_UNDO_STEPS)
        // The oldest kept state is the one 200 edits back, not the empty document.
        assertThat(state.text).isEqualTo("value-49")
    }

    @Test
    fun resetDropsBothStacks() {
        val state = UndoRedoState()
        state.reset("a")
        state.edit("ab")
        state.undo()

        state.reset("fresh")
        assertThat(state.canUndo).isFalse()
        assertThat(state.canRedo).isFalse()
        assertThat(state.text).isEqualTo("fresh")
    }

    @Test
    fun undoStackDropsOldestSnapshotsWhenMemoryLimitExceeded() {
        val state = UndoRedoState(limit = 200, maxBytes = 40L)
        state.reset("start")

        state.edit("A1234")
        state.edit("B1234")
        state.edit("C1234")
        state.edit("D1234")

        assertThat(state.snapshotBytes).isEqualTo(40L)

        // Pushing another 10 bytes exceeds 40 bytes; oldest ("start") must be dropped
        state.edit("E1234")

        assertThat(state.snapshotBytes).isAtMost(40L)

        assertThat(state.undo()).isEqualTo("D1234")
        assertThat(state.undo()).isEqualTo("C1234")
        assertThat(state.undo()).isEqualTo("B1234")
        assertThat(state.undo()).isEqualTo("A1234")
        assertThat(state.undo()).isNull()
    }
}
