package org.samesame.notes.feature.notes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.samesame.notes.core.model.ViewMode

class GridColumnsTest {
    @Test fun phonePortraitKeepsThreeAndTwoColumns() {
        assertEquals(3, notesGridColumns(ViewMode.GridSmall, 411, 411, landscape = false))
        assertEquals(3, notesGridColumns(ViewMode.GridMedium, 411, 411, landscape = false))
        assertEquals(2, notesGridColumns(ViewMode.GridLarge, 411, 411, landscape = false))
        assertEquals(1, notesGridColumns(ViewMode.List, 411, 411, landscape = false))
        assertEquals(1, notesGridColumns(ViewMode.SimpleList, 411, 411, landscape = false))
    }

    @Test fun phoneLandscapeAddsColumns() {
        assertEquals(7, notesGridColumns(ViewMode.GridMedium, 891, 411, landscape = true))
        assertEquals(5, notesGridColumns(ViewMode.GridLarge, 891, 411, landscape = true))
        assertEquals(2, notesGridColumns(ViewMode.List, 891, 411, landscape = true))
        assertEquals(1, notesGridColumns(ViewMode.SimpleList, 891, 411, landscape = true))
    }

    @Test fun tabletsUseTheirOwnSteps() {
        assertEquals(4, notesGridColumns(ViewMode.GridSmall, 800, 600, landscape = false))
        assertEquals(3, notesGridColumns(ViewMode.GridLarge, 800, 600, landscape = false))
        assertEquals(1, notesGridColumns(ViewMode.List, 800, 600, landscape = false))
        assertEquals(7, notesGridColumns(ViewMode.GridSmall, 1280, 800, landscape = true))
        assertEquals(6, notesGridColumns(ViewMode.GridLarge, 1280, 800, landscape = true))
    }
}
