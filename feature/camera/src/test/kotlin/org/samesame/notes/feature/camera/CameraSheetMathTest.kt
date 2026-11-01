package org.samesame.notes.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSheetMathTest {
    @Test fun sheetCoversTheBottomOfTheWindowLikeTheReference() {
        // Reference: 891.4 dp screen, sheet top at 342.9 dp -> 548.6 dp tall.
        assertEquals(548.2f, CameraSheetMath.sheetHeight(891.4f), 0.5f)
        assertEquals(0f, CameraSheetMath.sheetHeight(-5f), 0f)
    }

    @Test fun dragOnlyMovesTheSheetDown() {
        assertEquals(0f, CameraSheetMath.dragOffset(-40f, 1000f), 0f)
        assertEquals(120f, CameraSheetMath.dragOffset(120f, 1000f), 0f)
        assertEquals(1000f, CameraSheetMath.dragOffset(4000f, 1000f), 0f)
    }

    @Test fun releaseDismissesPastAQuarterOrOnAFling() {
        assertFalse(CameraSheetMath.dismissOnRelease(200f, 1000f, 100f))
        assertTrue(CameraSheetMath.dismissOnRelease(250f, 1000f, 0f))
        assertTrue(CameraSheetMath.dismissOnRelease(30f, 1000f, 1500f))
        assertFalse(CameraSheetMath.dismissOnRelease(30f, 1000f, -3000f)) // flung up
    }

    @Test fun captureNamesAreUniqueJpegs() {
        assertEquals("capture-1730000000000.jpg", CameraSheetMath.captureFileName(1_730_000_000_000L))
    }

    @Test fun exifOrientationBecomesRotationAndMirror() {
        assertEquals(0 to false, CameraSheetMath.orientationFix(1))
        assertEquals(90 to false, CameraSheetMath.orientationFix(6))
        assertEquals(180 to false, CameraSheetMath.orientationFix(3))
        assertEquals(270 to false, CameraSheetMath.orientationFix(8))
        assertEquals(0 to true, CameraSheetMath.orientationFix(2))
        assertEquals(90 to true, CameraSheetMath.orientationFix(5))
        assertEquals(0 to false, CameraSheetMath.orientationFix(0))
    }

    @Test fun sampleSizeBoundsTheLongerSide() {
        assertEquals(1, CameraSheetMath.sampleSize(4000, 3000, 4096))
        assertEquals(2, CameraSheetMath.sampleSize(8000, 6000, 4096))
        assertEquals(8, CameraSheetMath.sampleSize(20000, 100, 4096))
        assertEquals(1, CameraSheetMath.sampleSize(0, 0, 4096))
    }
}
