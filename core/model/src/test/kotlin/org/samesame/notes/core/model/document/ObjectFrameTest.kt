package org.samesame.notes.core.model.document

import kotlin.math.abs
import org.junit.Test
import org.junit.Assert.assertTrue

class ObjectFrameTest {
    private fun near(a: Float, b: Float, eps: Float = 0.01f) = assertTrue("expected $b got $a", abs(a - b) <= eps)
    private fun Affine2.apply(p: Pt) = Pt(mapX(p.x, p.y), mapY(p.x, p.y))

    private val box = ObjectFrame(cx = 300f, cy = 100f, w = 400f, h = 100f)

    @Test fun cornerDragTracksFingerExactly() {
        // The overshoot bug: the dragged corner must land under the finger, not scale uniformly.
        val m = FrameGestures.resize(box, FrameHandle.BR, Pt(560f, 190f))
        val f = box.transformedBy(m)
        near(f.toRect().right, 560f); near(f.toRect().bottom, 190f)
        near(f.toRect().left, 100f); near(f.toRect().top, 50f)
    }

    @Test fun edgeDragMovesOnlyThatEdge() {
        val f = box.transformedBy(FrameGestures.resize(box, FrameHandle.L, Pt(40f, 500f)))
        near(f.toRect().left, 40f); near(f.toRect().right, 500f); near(f.h, 100f); near(f.cy, 100f)
    }

    @Test fun lockedAspectKeepsRatio() {
        val f = box.transformedBy(FrameGestures.resize(box, FrameHandle.BR, Pt(700f, 160f), lockAspect = true))
        near(f.w / f.h, 4f)
        near(f.toRect().left, 100f); near(f.toRect().top, 50f)
    }

    @Test fun minSpanAndNoFlip() {
        val f = box.transformedBy(FrameGestures.resize(box, FrameHandle.BR, Pt(0f, 0f), minSpan = 48f))
        near(f.w, 48f); near(f.h, 48f)
    }

    @Test fun rotatedResizeKeepsOppositeCornerAndRotation() {
        val r = box.copy(rotationDeg = 30f)
        val anchor = r.handlePoint(FrameHandle.TL)
        // Drag BR along the box's own diagonal by +60 in local x and +20 in local y.
        val target = r.toWorld(r.w / 2f + 60f, r.h / 2f + 20f)
        val f = r.transformedBy(FrameGestures.resize(r, FrameHandle.BR, target))
        near(f.rotationDeg, 30f); near(f.w, 460f); near(f.h, 120f)
        val a2 = f.handlePoint(FrameHandle.TL)
        near(a2.x, anchor.x); near(a2.y, anchor.y)
        val br = f.handlePoint(FrameHandle.BR)
        near(br.x, target.x); near(br.y, target.y)
    }

    @Test fun rotationHandleFollowsFrame() {
        val r = box.copy(rotationDeg = 90f)
        val p = r.rotateHandlePoint(30f) // "down" of a box turned 90 degrees clockwise points to -x
        near(p.x, 300f - 50f - 30f); near(p.y, 100f)
    }

    @Test fun rotationGestureAboutCentre() {
        // Finger from straight right of the centre to straight below it: +90 degrees clockwise.
        val a = FrameGestures.rotatedAngle(box, Pt(500f, 100f), Pt(300f, 300f))
        near(a, 90f)
        val f = box.transformedBy(FrameGestures.rotation(box, a))
        near(f.rotationDeg, 90f); near(f.cx, 300f); near(f.w, 400f)
    }

    @Test fun snapping() {
        near(FrameGestures.snap(43f), 45f)
        near(FrameGestures.snap(40f), 40f)
        near(FrameGestures.snap(-89f), -90f)
        near(FrameGestures.snap(181f), 180f)
        near(normalizeAngle(270f), -90f)
    }

    @Test fun hitTestFollowsRotation() {
        val r = box.copy(rotationDeg = 90f) // now 100 wide, 400 tall around (300,100)
        assertTrue(r.contains(Pt(300f, 280f)))
        assertTrue(!r.contains(Pt(450f, 100f)))
    }

    @Test fun rotatedBoundsAndObjectTransform() {
        val f = FloatingObject(TextBoxObject("t", org.samesame.notes.core.model.richtext.RichText.of("x")), RectPu(100f, 50f, 500f, 150f), rotationDeg = 90f)
        val b = f.rotatedBounds
        near(b.left, 250f); near(b.right, 350f); near(b.top, -100f); near(b.bottom, 300f)
        val moved = f.transformedBy(Affine2(1f, 0f, 10f, 0f, 1f, 20f))
        near(moved.rotationDeg, 90f); near(moved.rect.left, 110f); near(moved.rect.top, 70f)
        val plain = f.copy(rotationDeg = 0f).transformedBy(Affine2(2f, 0f, 0f, 0f, 1f, 0f))
        near(plain.rect.right, 1000f)
    }
}
