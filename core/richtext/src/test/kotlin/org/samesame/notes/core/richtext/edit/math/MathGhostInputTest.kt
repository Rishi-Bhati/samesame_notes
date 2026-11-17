package org.samesame.notes.core.richtext.edit.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathGhostInputTest {
    @Test
    fun ghostIsInsertedAtAnchorOnly() {
        assertEquals("1 + 1 = 2\nnext", MathGhostInput.withGhost("1 + 1 =\nnext", 7, " 2"))
        assertEquals("1+1= 2", MathGhostInput.withGhost("1+1=", 4, " 2"))
    }

    @Test
    fun afterCursorShowsGhostThenRealText() {
        assertEquals(" 2\nnext", MathGhostInput.afterCursor("1 + 1 =\nnext", 7, " 2", 600))
        assertEquals(" 2", MathGhostInput.afterCursor("1+1=", 4, " 2", 600))
        assertEquals(" 2\n", MathGhostInput.afterCursor("1 + 1 =\nnext", 7, " 2", 3))
    }

    @Test
    fun selectionToTheRightAccepts() {
        val j = GhostSelectionJudge()
        assertEquals(SelectionVerdict.Accept, j.onSelection(10, 11, 11, 0))
        assertEquals(SelectionVerdict.Accept, j.onSelection(10, 14, 14, 1))
    }

    @Test
    fun selectionToTheLeftOrRangeDoesNotAccept() {
        val j = GhostSelectionJudge()
        assertEquals(SelectionVerdict.Pass, j.onSelection(10, 9, 9, 0))
        assertEquals(SelectionVerdict.Pass, j.onSelection(10, 10, 12, 1))
    }

    @Test
    fun stalledGlideAcceptsOnThirdIdenticalRequestInWindow() {
        val j = GhostSelectionJudge()
        assertEquals(SelectionVerdict.Pass, j.onSelection(10, 10, 10, 0))
        assertEquals(SelectionVerdict.Pass, j.onSelection(10, 10, 10, 20))
        assertEquals(SelectionVerdict.Accept, j.onSelection(10, 10, 10, 40))
    }

    @Test
    fun slowRepeatsOfSameCaretDoNotAccept() {
        val j = GhostSelectionJudge()
        repeat(6) { assertEquals(SelectionVerdict.Pass, j.onSelection(10, 10, 10, it * 400L)) }
    }

    @Test
    fun otherSelectionResetsStall() {
        val j = GhostSelectionJudge()
        j.onSelection(10, 10, 10, 0)
        j.onSelection(10, 10, 10, 10)
        assertEquals(SelectionVerdict.Pass, j.onSelection(10, 5, 5, 20))
        assertEquals(SelectionVerdict.Pass, j.onSelection(10, 10, 10, 30))
    }

    @Test
    fun secondSpaceWithin400msAcceptsAndIsSwallowed() {
        val d = GhostDoubleSpace()
        assertFalse(d.onCommit(" ", 1000, ghostShowing = true, charBeforeCaretIsSpace = false))
        assertTrue(d.onCommit(" ", 1300, ghostShowing = true, charBeforeCaretIsSpace = true))
    }

    @Test
    fun secondSpaceAfter400msDoesNotAccept() {
        val d = GhostDoubleSpace()
        d.onCommit(" ", 1000, true, false)
        assertFalse(d.onCommit(" ", 1401, true, true))
    }

    @Test
    fun spaceWithoutGhostNeverAccepts() {
        val d = GhostDoubleSpace()
        assertFalse(d.onCommit(" ", 0, false, false))
        assertFalse(d.onCommit(" ", 100, false, true))
    }

    @Test
    fun letterBetweenSpacesBreaksTheChain() {
        val d = GhostDoubleSpace()
        d.onCommit(" ", 0, true, false)
        assertFalse(d.onCommit("a", 100, true, true))
        assertFalse(d.onCommit(" ", 200, true, true))
    }

    @Test
    fun gboardDoublSpacePeriodSequenceAcceptsInsteadOfPeriod() {
        val d = GhostDoubleSpace()
        assertFalse(d.onCommit(" ", 0, true, false))
        d.onDelete(250)
        assertTrue(d.onCommit(". ", 252, true, false))
    }

    @Test
    fun periodAfterDeleteWithoutRecentSpaceIsLeftAlone() {
        val d = GhostDoubleSpace()
        d.onDelete(250)
        assertFalse(d.onCommit(". ", 252, true, false))
    }

    @Test
    fun periodSequenceWithoutGhostIsLeftAlone() {
        val d = GhostDoubleSpace()
        d.onCommit(" ", 0, false, false)
        d.onDelete(250)
        assertFalse(d.onCommit(". ", 252, false, false))
    }

    @Test
    fun resyncOnAppearChangeAndDisappear() {
        val s = ImeGhostSync()
        assertTrue(s.onGhost(5, " 164", false))
        assertFalse(s.onGhost(5, " 164", false))
        assertTrue(s.onGhost(6, "164", false))
        assertTrue(s.onGhost(-1, null, false))
        assertFalse(s.onGhost(-1, null, false))
    }

    @Test
    fun noResyncWhileComposing() {
        val s = ImeGhostSync()
        assertFalse(s.onGhost(5, " 1", true))
        assertTrue(s.onGhost(-1, null, false))
    }
}
