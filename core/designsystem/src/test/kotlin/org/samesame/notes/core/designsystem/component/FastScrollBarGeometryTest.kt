package org.samesame.notes.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastScrollBarGeometryTest {
    @Test fun thumbTravelsBetweenTheMargins() {
        // 1000 px track, 40 px thumb, 0 / 20 px margins -> 940 px of travel.
        assertEquals(940f, FastScrollBarGeometry.travel(1000f, 40f, 0f, 20f), 0.001f)
        assertEquals(0f, FastScrollBarGeometry.along(940f, 0f), 0.001f)
        assertEquals(470f, FastScrollBarGeometry.along(940f, 0.5f), 0.001f)
        assertEquals(940f, FastScrollBarGeometry.along(940f, 1f), 0.001f)
        assertEquals(940f, FastScrollBarGeometry.along(940f, 3f), 0.001f) // clamped
        assertEquals(0f, FastScrollBarGeometry.along(940f, -1f), 0.001f)
    }

    @Test fun tooShortTracksDoNotTravel() {
        assertEquals(0f, FastScrollBarGeometry.travel(50f, 40f, 6f, 20f), 0.001f)
    }

    @Test fun thumbTouchZoneIsAtLeastTheMinimumTarget() {
        // A 40 px thumb at 100..140 with a 48 px minimum: 96..144 counts.
        assertTrue(FastScrollBarGeometry.isOnThumb(96f, 100f, 40f, 48f))
        assertTrue(FastScrollBarGeometry.isOnThumb(120f, 100f, 40f, 48f))
        assertTrue(FastScrollBarGeometry.isOnThumb(144f, 100f, 40f, 48f))
        assertFalse(FastScrollBarGeometry.isOnThumb(95f, 100f, 40f, 48f))
        assertFalse(FastScrollBarGeometry.isOnThumb(145f, 100f, 40f, 48f))
        // A thumb longer than the minimum: its own length.
        assertTrue(FastScrollBarGeometry.isOnThumb(100f, 100f, 80f, 48f))
        assertFalse(FastScrollBarGeometry.isOnThumb(99f, 100f, 80f, 48f))
    }

    @Test fun theEdgeStripOnlyTakesDragsAlongTheBar() {
        // Within the slop: still undecided (a tap goes to the card under the strip).
        assertFalse(FastScrollBarGeometry.stripTakesOver(0f, 5f, slop = 8f))
        // A vertical drag past the slop.
        assertTrue(FastScrollBarGeometry.stripTakesOver(2f, 9f, slop = 8f))
        assertTrue(FastScrollBarGeometry.stripTakesOver(0f, -12f, slop = 8f))
        // Sideways (drawer swipe) or diagonal-but-mostly-sideways: not taken.
        assertFalse(FastScrollBarGeometry.stripTakesOver(12f, 3f, slop = 8f))
        assertFalse(FastScrollBarGeometry.stripTakesOver(10f, 10f, slop = 8f))
        // A horizontal bar takes horizontal drags.
        assertTrue(FastScrollBarGeometry.stripTakesOver(12f, 3f, slop = 8f, horizontalBar = true))
    }

    @Test fun relativeDragFollowsTheFingerWithoutAJump() {
        // Thumb at 470 of 940 (0.5): a 94 px drag moves it a tenth of the travel, wherever the finger started.
        assertEquals(0.6f, ScrollBarGeometry.dragFraction(940f, 0f, 470f, 94f), 0.0001f)
        assertEquals(0.5f, ScrollBarGeometry.dragFraction(940f, 0f, 470f, 0f), 0.0001f)
        assertEquals(1f, ScrollBarGeometry.dragFraction(940f, 0f, 470f, 900f), 0.0001f)
    }
}
