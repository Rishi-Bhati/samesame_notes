package org.samesame.notes.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DrawerSwipeTest {
    @Test fun aFlingWinsOverThePosition() {
        assertThat(DrawerSwipe.shouldOpen(fraction = 0.1f, velocityX = 900f)).isTrue()
        assertThat(DrawerSwipe.shouldOpen(fraction = 0.9f, velocityX = -900f)).isFalse()
    }

    @Test fun withoutAFlingTheHalfwayPointDecides() {
        assertThat(DrawerSwipe.shouldOpen(fraction = 0.51f, velocityX = 0f)).isTrue()
        assertThat(DrawerSwipe.shouldOpen(fraction = 0.49f, velocityX = 0f)).isFalse()
        assertThat(DrawerSwipe.shouldOpen(fraction = DrawerSwipe.COMMIT_FRACTION, velocityX = 0f)).isFalse()
    }

    @Test fun slowDriftKeepsTheCurrentStateAtTheEdges() {
        assertThat(DrawerSwipe.shouldOpen(fraction = 0f, velocityX = 100f)).isFalse()
        assertThat(DrawerSwipe.shouldOpen(fraction = 1f, velocityX = -100f)).isTrue()
    }
}
