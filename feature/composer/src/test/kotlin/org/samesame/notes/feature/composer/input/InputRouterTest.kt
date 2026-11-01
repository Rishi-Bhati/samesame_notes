package org.samesame.notes.feature.composer.input

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InputRouterTest {
    private var settings = InputSettings()
    private var pageHit = true
    private val router = InputRouter({ settings }, { pageHit })

    private fun finger(id: Long, x: Float, y: Float, pressed: Boolean = true, major: Float = 40f) =
        PointerState(id, PointerKind.Finger, pressed, Offset(x, y), major)

    private fun stylus(id: Long, x: Float, y: Float, pressed: Boolean = true, button: Boolean = false, eraser: Boolean = false) =
        PointerState(id, if (eraser) PointerKind.StylusEraser else PointerKind.Stylus, pressed, Offset(x, y), button = button)

    private fun frame(vararg p: PointerState, hover: Boolean = false) = InputFrame(p.toList(), hover)

    @Test fun stylusWithPenGoesToInkUnconsumed() {
        val d = router.route(frame(stylus(1, 10f, 10f)))
        assertThat(d.kind).isEqualTo(GestureKind.Ink)
        assertThat(d.consumeIds).isEmpty()
        assertThat(router.route(frame(stylus(1, 20f, 20f))).consumeIds).isEmpty()
        assertThat(router.route(frame(stylus(1, 20f, 20f, pressed = false))).ended).isTrue()
    }

    @Test fun eraserToolIsConsumedAndSentToHandler() {
        settings = settings.copy(tool = InputTool.Eraser)
        val d = router.route(frame(stylus(1, 10f, 10f)))
        assertThat(d.kind).isEqualTo(GestureKind.Tool)
        assertThat(d.consumeIds).containsExactly(1L)
        assertThat(d.tool).isEqualTo(ToolEvent.Down(Offset(10f, 10f), InputToolType.Stylus, InputTool.Eraser))
        assertThat(router.route(frame(stylus(1, 12f, 10f))).tool).isEqualTo(ToolEvent.Move(Offset(12f, 10f)))
        assertThat(router.route(frame(stylus(1, 12f, 10f, pressed = false))).tool).isEqualTo(ToolEvent.Up)
    }

    @Test fun stylusEraserEndIsTemporaryEraser() {
        val d = router.route(frame(stylus(1, 10f, 10f, eraser = true)))
        assertThat(d.kind).isEqualTo(GestureKind.Tool)
        assertThat((d.tool as ToolEvent.Down).tool).isEqualTo(InputTool.Eraser)
    }

    @Test fun stylusButtonIsTemporaryEraser() {
        val d = router.route(frame(stylus(1, 10f, 10f, button = true)))
        assertThat(d.kind).isEqualTo(GestureKind.Tool)
        assertThat((d.tool as ToolEvent.Down).tool).isEqualTo(InputTool.Eraser)
    }

    @Test fun fingerDrawsWhenEnabled() {
        assertThat(router.route(frame(finger(1, 5f, 5f))).kind).isEqualTo(GestureKind.Ink)
    }

    @Test fun fingerPansWhenFingerDrawingOff() {
        settings = settings.copy(fingerDrawing = false)
        val d0 = router.route(frame(finger(1, 100f, 100f)))
        assertThat(d0.kind).isEqualTo(GestureKind.Pan)
        assertThat(d0.consumeIds).containsExactly(1L)
        val d1 = router.route(frame(finger(1, 100f, 160f)))
        assertThat(d1.pan).isEqualTo(Offset(0f, 60f))
        assertThat(d1.zoom).isEqualTo(1f)
        assertThat(router.route(frame(finger(1, 100f, 160f, pressed = false))).ended).isTrue()
    }

    @Test fun secondFingerCancelsStrokeAndPinches() {
        router.route(frame(finger(1, 100f, 100f)))
        router.route(frame(finger(1, 110f, 110f)))
        val d = router.route(frame(finger(1, 110f, 110f), finger(2, 300f, 110f)))
        assertThat(d.cancelInk).isTrue()
        assertThat(d.kind).isEqualTo(GestureKind.PanZoom)
        assertThat(d.consumeIds).containsExactly(1L, 2L)
        // spread 190 -> 380 around the centroid: zoom 2x (+ centroid shift)
        val z = router.route(frame(finger(1, 10f, 110f), finger(2, 400f, 110f)))
        assertThat(z.zoom).isWithin(0.01f).of(390f / 190f)
        assertThat(z.cancelInk).isFalse()
    }

    @Test fun zoomLockDisablesPinchButTwoFingersScroll() {
        settings = settings.copy(zoomLock = true)
        router.route(frame(finger(1, 100f, 100f)))
        val d = router.route(frame(finger(1, 100f, 100f), finger(2, 300f, 100f)))
        assertThat(d.kind).isEqualTo(GestureKind.PanZoom)
        assertThat(d.cancelInk).isTrue()
        val m = router.route(frame(finger(1, 100f, 200f), finger(2, 500f, 200f)))
        assertThat(m.zoom).isEqualTo(1f)
        assertThat(m.pan.y).isEqualTo(100f)
    }

    @Test fun zoomLockWithFingerDrawingOffIgnoresOneFinger() {
        settings = settings.copy(zoomLock = true, fingerDrawing = false)
        val d = router.route(frame(finger(1, 100f, 100f)))
        assertThat(d.kind).isEqualTo(GestureKind.Ignored)
        assertThat(router.route(frame(finger(1, 100f, 300f))).pan).isEqualTo(Offset.Zero)
        val two = router.route(frame(finger(1, 100f, 300f), finger(2, 300f, 300f)))
        assertThat(two.kind).isEqualTo(GestureKind.PanZoom)
    }

    @Test fun fingerWhileStylusWritesIsPalm() {
        router.route(frame(stylus(1, 10f, 10f)))
        val d = router.route(frame(stylus(1, 12f, 12f), finger(2, 500f, 900f)))
        assertThat(d.kind).isEqualTo(GestureKind.Ink)
        assertThat(d.cancelInk).isFalse()
        assertThat(d.consumeIds).containsExactly(2L)
    }

    @Test fun touchWhileStylusHoveringIsIgnored() {
        val d = router.route(frame(finger(1, 10f, 10f), hover = true))
        assertThat(d.kind).isEqualTo(GestureKind.Ignored)
        assertThat(d.consumeIds).containsExactly(1L)
    }

    @Test fun largeContactAndCanceledAreIgnored() {
        assertThat(router.route(frame(finger(1, 10f, 10f, major = 400f))).kind).isEqualTo(GestureKind.Ignored)
        router.route(frame(finger(1, 10f, 10f, pressed = false)))
        assertThat(router.route(frame(finger(2, 10f, 10f).copy(canceled = true))).kind).isEqualTo(GestureKind.Ignored)
    }

    @Test fun stylusAfterPalmTakesOver() {
        router.route(frame(finger(1, 10f, 10f, major = 400f)))
        val d = router.route(frame(finger(1, 10f, 12f, major = 400f), stylus(2, 200f, 200f)))
        assertThat(d.kind).isEqualTo(GestureKind.Ink)
        assertThat(d.consumeIds).containsExactly(1L)
    }

    @Test fun penDownOutsideAPageIsIgnored() {
        pageHit = false
        val d = router.route(frame(stylus(1, 10f, 10f)))
        assertThat(d.kind).isEqualTo(GestureKind.Ignored)
        assertThat(d.consumeIds).containsExactly(1L)
    }

    @Test fun textModeTapPassesThroughAndDragScrolls() {
        settings = settings.copy(mode = InputMode.Text)
        val down = router.route(frame(finger(1, 100f, 100f)))
        assertThat(down.kind).isEqualTo(GestureKind.TextTouch)
        assertThat(down.consumeIds).isEmpty()
        assertThat(router.route(frame(finger(1, 104f, 100f))).consumeIds).isEmpty() // within slop
        val drag = router.route(frame(finger(1, 100f, 160f)))
        assertThat(drag.kind).isEqualTo(GestureKind.Pan)
        assertThat(drag.consumeIds).containsExactly(1L)
        assertThat(router.route(frame(finger(1, 100f, 200f))).pan).isEqualTo(Offset(0f, 40f))
    }

    @Test fun textModeStylusActsLikeFinger() {
        settings = settings.copy(mode = InputMode.Text)
        assertThat(router.route(frame(stylus(1, 5f, 5f))).kind).isEqualTo(GestureKind.TextTouch)
    }

    @Test fun textHandleDragIsNotHijacked() {
        val r = InputRouter({ InputSettings(mode = InputMode.Text) }, startsTextInteraction = { true })
        r.route(frame(finger(1, 100f, 100f)))
        val d = r.route(frame(finger(1, 100f, 300f)))
        assertThat(d.kind).isEqualTo(GestureKind.TextTouch)
        assertThat(d.consumeIds).isEmpty()
    }

    @Test fun textModePressOnTheSelectedObjectGoesToTheSelectionTool() {
        val r = InputRouter({ InputSettings(mode = InputMode.Text) }, startsObjectGesture = { it.x < 50f })
        val d = r.route(frame(finger(1, 10f, 10f)))
        assertThat(d.kind).isEqualTo(GestureKind.Tool)
        assertThat(d.consumeIds).containsExactly(1L)
        assertThat(d.tool).isEqualTo(ToolEvent.Down(Offset(10f, 10f), InputToolType.Finger, InputTool.Selection))
        assertThat(r.route(frame(finger(1, 30f, 10f))).tool).isEqualTo(ToolEvent.Move(Offset(30f, 10f)))
        // A second finger cancels the transform and pinches instead.
        val two = r.route(frame(finger(1, 30f, 10f), finger(2, 200f, 10f)))
        assertThat(two.kind).isEqualTo(GestureKind.PanZoom)
        assertThat(two.tool).isEqualTo(ToolEvent.Cancel)
        r.route(frame(finger(1, 30f, 10f, pressed = false), finger(2, 200f, 10f, pressed = false)))
        // Elsewhere it is a normal text touch.
        assertThat(r.route(frame(finger(3, 100f, 10f))).kind).isEqualTo(GestureKind.TextTouch)
    }

    @Test fun twoFingersOnTheSelectedObjectPinchItInsteadOfZooming() {
        val r = InputRouter({ InputSettings(mode = InputMode.Text) }, startsObjectGesture = { it.x < 100f })
        r.route(frame(finger(1, 10f, 10f)))
        val two = r.route(frame(finger(1, 10f, 10f), finger(2, 60f, 10f)))
        assertThat(two.kind).isEqualTo(GestureKind.ObjectPinch)
        assertThat(two.tool).isEqualTo(ToolEvent.Cancel) // the one-finger move is dropped
        assertThat(two.pinch).isEqualTo(PinchEvent.Start(Offset(10f, 10f), Offset(60f, 10f)))
        assertThat(two.consumeIds).containsExactly(1L, 2L)
        val move = r.route(frame(finger(1, 0f, 10f), finger(2, 90f, 10f)))
        assertThat(move.pinch).isEqualTo(PinchEvent.Move(Offset(0f, 10f), Offset(90f, 10f)))
        assertThat(move.zoom).isEqualTo(1f) // the page does not zoom
        // A third finger is ignored.
        assertThat(r.route(frame(finger(1, 0f, 10f), finger(2, 90f, 10f), finger(3, 50f, 50f))).pinch)
            .isEqualTo(PinchEvent.Move(Offset(0f, 10f), Offset(90f, 10f)))
        // Lifting one finger commits; the other one does nothing more.
        val up = r.route(frame(finger(1, 0f, 10f, pressed = false), finger(2, 90f, 10f), finger(3, 50f, 50f)))
        assertThat(up.pinch).isEqualTo(PinchEvent.End)
        assertThat(r.route(frame(finger(2, 95f, 10f), finger(3, 50f, 50f))).pinch).isNull()
        assertThat(r.route(frame(finger(2, 95f, 10f, pressed = false), finger(3, 50f, 50f, pressed = false))).pinch).isNull()
    }

    @Test fun aPinchWithOneFingerOffTheObjectZoomsThePage() {
        val r = InputRouter({ InputSettings(mode = InputMode.Text) }, startsObjectGesture = { it.x < 100f })
        r.route(frame(finger(1, 10f, 10f)))
        val two = r.route(frame(finger(1, 10f, 10f), finger(2, 300f, 10f)))
        assertThat(two.kind).isEqualTo(GestureKind.PanZoom)
        assertThat(two.pinch).isNull()
    }

    @Test fun penModeFingerInkTurnsIntoAnObjectPinch() {
        val r = InputRouter({ InputSettings(mode = InputMode.Pen) }, startsObjectGesture = { it.x < 100f })
        assertThat(r.route(frame(finger(1, 10f, 10f))).kind).isEqualTo(GestureKind.Ink)
        val two = r.route(frame(finger(1, 12f, 10f), finger(2, 60f, 10f)))
        assertThat(two.kind).isEqualTo(GestureKind.ObjectPinch)
        assertThat(two.cancelInk).isTrue()
        // Both lifting together ends it too.
        val end = r.route(frame(finger(1, 12f, 10f, pressed = false), finger(2, 60f, 10f, pressed = false)))
        assertThat(end.pinch).isEqualTo(PinchEvent.End)
        assertThat(end.ended).isTrue()
    }

    @Test fun simultaneousFingersOnTheObjectStartAPinch() {
        val r = InputRouter({ InputSettings(mode = InputMode.Text) }, startsObjectGesture = { it.x < 100f })
        val d = r.route(frame(finger(1, 10f, 10f), finger(2, 60f, 10f)))
        assertThat(d.kind).isEqualTo(GestureKind.ObjectPinch)
        assertThat(d.tool).isNull()
        assertThat(d.pinch).isEqualTo(PinchEvent.Start(Offset(10f, 10f), Offset(60f, 10f)))
    }

    @Test fun readingModeNeverPinchesObjects() {
        val r = InputRouter({ InputSettings(mode = InputMode.Reading) }, startsObjectGesture = { true })
        r.route(frame(finger(1, 10f, 10f)))
        assertThat(r.route(frame(finger(1, 10f, 10f), finger(2, 60f, 10f))).kind).isEqualTo(GestureKind.PanZoom)
    }

    @Test fun objectHookIsIgnoredOutsideTextMode() {
        val r = InputRouter({ InputSettings(mode = InputMode.Reading) }, startsObjectGesture = { true })
        assertThat(r.route(frame(finger(1, 10f, 10f))).kind).isEqualTo(GestureKind.TextTouch)
    }

    @Test fun readingModeScrollsAndPinches() {
        settings = settings.copy(mode = InputMode.Reading)
        router.route(frame(stylus(1, 100f, 100f)))
        val two = router.route(frame(stylus(1, 100f, 100f), finger(2, 200f, 100f)))
        assertThat(two.kind).isEqualTo(GestureKind.PanZoom)
    }

    @Test fun gestureResetsAfterAllPointersLift() {
        router.route(frame(finger(1, 1f, 1f)))
        router.route(frame(finger(1, 1f, 1f), finger(2, 50f, 1f)))
        router.route(frame(finger(1, 1f, 1f, pressed = false), finger(2, 50f, 1f, pressed = false)))
        assertThat(router.activeKind).isNull()
        assertThat(router.route(frame(stylus(3, 5f, 5f))).kind).isEqualTo(GestureKind.Ink)
    }
}
