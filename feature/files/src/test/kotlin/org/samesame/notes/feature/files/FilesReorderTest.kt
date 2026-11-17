package org.samesame.notes.feature.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FilesReorderTest {
    private val cell = 100f

    @Test fun horizontalDragMovesByWholeCells() {
        assertThat(reorderTarget(0, 120f, 0f, cell, cell, 3, 9)).isEqualTo(1)
        assertThat(reorderTarget(0, -120f, 0f, cell, cell, 3, 9)).isEqualTo(0)
        assertThat(reorderTarget(2, -120f, 0f, cell, cell, 3, 9)).isEqualTo(1)
    }

    @Test fun verticalDragMovesByRows() {
        assertThat(reorderTarget(0, 0f, 120f, cell, cell, 3, 9)).isEqualTo(3)
        assertThat(reorderTarget(4, 0f, -120f, cell, cell, 3, 9)).isEqualTo(1)
        // A list has one column: vertical drags move one place at a time.
        assertThat(reorderTarget(0, 0f, 120f, cell, cell, 1, 5)).isEqualTo(1)
    }

    @Test fun diagonalAndClampedToTheList() {
        assertThat(reorderTarget(0, 120f, 120f, cell, cell, 3, 9)).isEqualTo(4)
        assertThat(reorderTarget(8, 500f, 500f, cell, cell, 3, 9)).isEqualTo(8)
        assertThat(reorderTarget(0, -500f, -500f, cell, cell, 3, 9)).isEqualTo(0)
    }

    @Test fun singleOrEmptyListStaysPut() {
        assertThat(reorderTarget(0, 120f, 120f, cell, cell, 1, 1)).isEqualTo(0)
        assertThat(reorderTarget(0, 120f, 120f, cell, cell, 3, 0)).isEqualTo(0)
    }
}
