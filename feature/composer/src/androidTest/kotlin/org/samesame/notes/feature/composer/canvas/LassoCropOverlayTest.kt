package org.samesame.notes.feature.composer.canvas

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.Pt
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.frame
import org.samesame.notes.feature.composer.session.LassoCrop

@RunWith(AndroidJUnit4::class)
class LassoCropOverlayTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val floating = FloatingObject(ImageObject("i", "media/a.png", 400f, 300f), RectPu(100f, 100f, 500f, 400f))
    private var latest: List<Pt> = emptyList()

    @Composable
    private fun overlay(done: () -> Unit) {
        val viewport = remember {
            Viewport().apply {
                updateSize(IntSize(1080, 1080))
                updateContent(Size(1080f, 1080f))
            }
        }
        var outline by remember { mutableStateOf<List<Pt>>(emptyList()) }
        var drawing by remember { mutableStateOf(false) }
        SameSameTheme(darkTheme = true) {
            Box(Modifier.fillMaxSize()) {
                LassoCropOverlay(
                    frame = floating.frame,
                    pageOrigin = Offset.Zero,
                    viewport = viewport,
                    outline = outline,
                    drawing = drawing,
                    canFinish = LassoCrop.bounds(outline, LassoCrop.MIN_SIDE, LassoCrop.MIN_SIDE) != null,
                    onOutlineChange = { pts, isDrawing ->
                        outline = pts
                        drawing = isDrawing
                        latest = pts
                    },
                    onCancel = {},
                    onDone = done,
                )
            }
        }
    }

    private fun dragLoop() = rule.onRoot().performTouchInput {
        down(Offset(180f, 160f))
        moveTo(Offset(420f, 160f))
        moveTo(Offset(420f, 360f))
        moveTo(Offset(180f, 360f))
        moveTo(Offset(180f, 160f))
        up()
    }

    @Test fun aDragDrawsTheMappedOutlineAndEnablesDone() {
        var done = 0
        rule.setContent { overlay { done++ } }

        dragLoop()
        rule.waitForIdle()

        // View (180,160) on the frame (100,100)-(500,400) at scale 1 = image fraction (0.2, 0.2).
        assertEquals(0.2f, latest.first().x, 0.01f)
        assertEquals(0.2f, latest.first().y, 0.01f)
        assertEquals(0.8f, latest.maxOf { it.x }, 0.01f)
        assertEquals((360f - 250f) / 300f + 0.5f, latest.maxOf { it.y }, 0.01f)

        rule.onNodeWithText("Done").assertIsEnabled().performClick()
        assertEquals(1, done)
    }

    @Test fun tappingDoneKeepsTheDrawnOutlineForTheApplyStep() {
        var atDone: List<Pt> = emptyList()
        var done = 0
        rule.setContent { overlay { atDone = latest; done++ } }

        dragLoop()
        rule.waitForIdle()
        rule.onNodeWithText("Done").performClick()

        // finishCrop reads the outline when Done fires: a tap on the bar must not replace it with a single point.
        assertTrue(LassoCrop.bounds(atDone, LassoCrop.MIN_SIDE, LassoCrop.MIN_SIDE) != null)
        assertEquals(1, done)
    }

    @Test fun anOutlineStillGrowsWhenAnAncestorConsumesTheDrag() {
        var done = 0
        rule.setContent {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            ev.changes.forEach { if (it.pressed) it.consume() }
                            if (ev.changes.none { it.pressed }) break
                        }
                    }
                },
            ) { overlay { done++ } }
        }

        dragLoop()
        rule.waitForIdle()

        // An ancestor that consumes the drag must not stop the outline (the Done click is the caller's business).
        assertEquals(0.8f, latest.maxOf { it.x }, 0.01f)
    }

    @Test fun theLassoSetsOutsideTransparentAndKeepsTheInside() {
        val src = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        src.eraseColor(android.graphics.Color.RED)
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            src.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        val crop = org.samesame.notes.feature.composer.session.CropFraction(0.25f, 0.25f, 0.75f, 0.75f)
        val outline = listOf(Pt(0.5f, 0f), Pt(1f, 0.5f), Pt(0.5f, 1f), Pt(0f, 0.5f))
        val cropped = org.samesame.notes.feature.composer.menus.ObjectFileActions.lassoCrop(bytes, crop, outline)!!
        val out = android.graphics.BitmapFactory.decodeByteArray(cropped, 0, cropped.size)
        assertEquals(50, out.width)
        assertEquals(50, out.height)
        assertEquals(android.graphics.Color.RED, out.getPixel(25, 25))
        assertEquals(0, android.graphics.Color.alpha(out.getPixel(0, 0)))
        assertEquals(0, android.graphics.Color.alpha(out.getPixel(49, 49)))
    }
}
