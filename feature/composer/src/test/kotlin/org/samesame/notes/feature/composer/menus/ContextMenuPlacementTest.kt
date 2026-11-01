package org.samesame.notes.feature.composer.menus

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMenuPlacementTest {
    // 1 px = 1 dp to keep the numbers readable.
    private val m = ContextMenuPlacement.Metrics(contentInflate = 38.4f, marginH = 16f, marginV = 8f, bottomAllowance = 20f)
    private val window = Rect(0f, 0f, 400f, 800f)
    private val canvas = Rect(0f, 56f, 400f, 744f) // below the 56 dp app bar, above the 56 dp toolbar
    private val pillW = 293f
    private val pillH = 47.6f

    private fun pill(sel: Rect) = ContextMenuPlacement.placePill(sel, canvas, window, pillW, pillH, m)

    @Test fun pillSits94dpAboveWhenThereIsRoom() {
        val p = pill(Rect(100f, 300f, 300f, 400f))!!
        assertTrue(p.above)
        assertEquals(300f - 94f, p.position.y, 0.01f)
        assertEquals(200f - pillW / 2f, p.position.x, 0.01f)
    }

    @Test fun pillFlipsBelowWhenTheSelectionIsNearTheTop() {
        val p = pill(Rect(100f, 70f, 300f, 200f))!!
        assertFalse(p.above)
        assertEquals(200f + 46.4f, p.position.y, 0.01f)
        assertTrue(p.position.y >= window.top)
    }

    @Test fun selectionAboveTheCanvasIsClippedToTheCanvasTop() {
        // Scrolled half under the app bar: the clipped top (56) leaves only 56 dp above, so it goes below.
        val p = pill(Rect(100f, -100f, 300f, 300f))!!
        assertFalse(p.above)
        assertEquals(300f + 46.4f, p.position.y, 0.01f)
    }

    @Test fun selectionFillingTheScreenPutsThePillAtTheWindowTop() {
        val p = pill(Rect(0f, 0f, 400f, 2000f))!!
        assertEquals(8f, p.position.y, 0.01f)
        assertTrue(p.position.y + pillH <= window.bottom)
    }

    @Test fun pillJustFitsBelowWithoutMargin() {
        val tall = pill(Rect(50f, 60f, 350f, 700f))!!
        // 700 + 38.4 = 738.4 leaves 61.6 dp below: >= 47.6 but < 63.6, so it sits right on the content bottom.
        assertFalse(tall.above)
        assertEquals(738.4f, tall.position.y, 0.01f)
    }

    @Test fun pillIsClampedHorizontally() {
        val left = pill(Rect(0f, 300f, 20f, 320f))!!
        assertEquals(16f, left.position.x, 0.01f)
        val right = pill(Rect(380f, 300f, 400f, 320f))!!
        assertEquals(400f - 16f - pillW, right.position.x, 0.01f)
    }

    @Test fun invisibleSelectionHasNoPill() {
        assertNull(pill(Rect(100f, -400f, 300f, -200f)))
        assertNull(pill(Rect(100f, 900f, 300f, 1000f)))
    }

    @Test fun menuOpensUpFromAPillAbove() {
        val p = pill(Rect(100f, 600f, 300f, 700f))!!
        val menuH = 9 * 47.5f
        val o = ContextMenuPlacement.placeMenu(p, pillW, pillH, 220f, menuH, window, m)
        assertEquals(p.position.x + pillW - 220f, o.x, 0.01f)
        assertEquals(p.position.y + pillH - menuH, o.y, 0.01f)
    }

    @Test fun menuOpensDownFromAPillBelow() {
        val p = pill(Rect(100f, 70f, 300f, 200f))!!
        val o = ContextMenuPlacement.placeMenu(p, pillW, pillH, 220f, 300f, window, m)
        assertEquals(p.position.y, o.y, 0.01f)
    }

    @Test fun menuFlipsDownWhenUpRunsOffTheTop() {
        val p = pill(Rect(100f, 200f, 300f, 300f))!! // pill above at y = 106
        assertTrue(p.above)
        val o = ContextMenuPlacement.placeMenu(p, pillW, pillH, 220f, 9 * 47.5f, window, m)
        assertEquals(p.position.y, o.y, 0.01f)
    }

    @Test fun menuIsClampedInsideTheWindow() {
        val small = Rect(0f, 0f, 400f, 300f) // keyboard up
        val p = ContextMenuPlacement.Pill(androidx.compose.ui.geometry.Offset(53f, 120f), above = true)
        val o = ContextMenuPlacement.placeMenu(p, pillW, pillH, 220f, 250f, small, m)
        assertTrue(o.y >= 8f)
        assertTrue(o.y + 250f <= 300f - 8f + 0.01f)
        val wide = ContextMenuPlacement.placeMenu(p, pillW, pillH, 220f, 100f, Rect(0f, 0f, 200f, 800f), m)
        assertEquals((200f - 220f) / 2f, wide.x, 0.01f)
    }
}
