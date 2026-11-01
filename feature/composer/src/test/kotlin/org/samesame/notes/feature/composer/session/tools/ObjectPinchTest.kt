package org.samesame.notes.feature.composer.session.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.ObjectFrame
import org.samesame.notes.core.model.document.Pt

class ObjectPinchTest {
    private val frame = ObjectFrame(cx = 500f, cy = 400f, w = 200f, h = 100f)

    private fun pinch(rotatable: Boolean = true, f: ObjectFrame = frame) =
        ObjectPinch(f, Pt(450f, 400f), Pt(550f, 400f), rotatable, minSpan = 48f)

    @Test fun spreadingTheFingersScalesAboutTheCentre() {
        val p = pinch()
        val m = p.update(Pt(400f, 400f), Pt(600f, 400f)) // distance 100 -> 200
        val out = frame.transformedBy(m)
        assertThat(out.w).isWithin(1e-3f).of(400f)
        assertThat(out.h).isWithin(1e-3f).of(200f) // ratio kept
        assertThat(out.cx).isWithin(1e-3f).of(500f)
        assertThat(out.cy).isWithin(1e-3f).of(400f)
        assertThat(out.rotationDeg).isEqualTo(0f)
        assertThat(p.changed).isTrue()
    }

    @Test fun movingBothFingersTogetherDoesNotMoveTheObject() {
        val m = pinch().update(Pt(650f, 900f), Pt(750f, 900f))
        val out = frame.transformedBy(m)
        assertThat(out.cx).isWithin(1e-3f).of(500f)
        assertThat(out.cy).isWithin(1e-3f).of(400f)
        assertThat(out.w).isWithin(1e-3f).of(200f)
    }

    @Test fun shrinkingStopsAtTheHandleMinimum() {
        val p = pinch()
        val out = frame.transformedBy(p.update(Pt(495f, 400f), Pt(505f, 400f))) // 10 % -> clamped
        assertThat(out.h).isWithin(1e-3f).of(48f)
        assertThat(out.w).isWithin(1e-3f).of(96f)
    }

    @Test fun anObjectAlreadyBelowTheMinimumDoesNotJump() {
        val small = ObjectFrame(500f, 400f, 40f, 30f)
        val p = pinch(f = small)
        val out = small.transformedBy(p.update(Pt(450f, 400f), Pt(550f, 400f)))
        assertThat(out.w).isWithin(1e-3f).of(40f)
        assertThat(ObjectPinch.clampScale(0.5f, 40f, 30f, 48f)).isEqualTo(1f)
        assertThat(ObjectPinch.clampScale(3f, 40f, 30f, 48f)).isEqualTo(3f) // no upper limit, like the handles
    }

    @Test fun aSmallTwistDoesNotRotate() {
        val p = pinch()
        // ~5.7 degrees: under the slop.
        val out = frame.transformedBy(p.update(Pt(450f, 395f), Pt(550f, 405f)))
        assertThat(out.rotationDeg).isEqualTo(0f)
        assertThat(p.rotationDelta).isEqualTo(0f)
    }

    @Test fun aTwistPastTheSlopRotatesWithoutAJump() {
        val p = pinch()
        // 30 degrees clockwise: rotation engages at 10, so the object turns by 20.
        val r = Math.toRadians(30.0)
        val dx = (50 * Math.cos(r)).toFloat(); val dy = (50 * Math.sin(r)).toFloat()
        val out = frame.transformedBy(p.update(Pt(500f - dx, 400f - dy), Pt(500f + dx, 400f + dy)))
        assertThat(out.rotationDeg).isWithin(0.01f).of(20f)
        assertThat(out.w).isWithin(0.01f).of(200f)
        // Turning back inside the slop keeps following (engaged once).
        val r2 = Math.toRadians(5.0)
        val ex = (50 * Math.cos(r2)).toFloat(); val ey = (50 * Math.sin(r2)).toFloat()
        val back = frame.transformedBy(p.update(Pt(500f - ex, 400f - ey), Pt(500f + ex, 400f + ey)))
        assertThat(back.rotationDeg).isWithin(0.01f).of(-5f)
    }

    @Test fun rotationSnapsLikeTheRotateHandle() {
        val p = pinch()
        val r = Math.toRadians(56.0) // 56 - 10 = 46 -> snaps to 45
        val dx = (50 * Math.cos(r)).toFloat(); val dy = (50 * Math.sin(r)).toFloat()
        val out = frame.transformedBy(p.update(Pt(500f - dx, 400f - dy), Pt(500f + dx, 400f + dy)))
        assertThat(out.rotationDeg).isWithin(0.01f).of(45f)
    }

    @Test fun voiceObjectsOnlyScale() {
        val p = pinch(rotatable = false)
        val out = frame.transformedBy(p.update(Pt(500f, 300f), Pt(500f, 500f))) // 90 degrees and 2x
        assertThat(out.rotationDeg).isEqualTo(0f)
        assertThat(out.w).isWithin(1e-3f).of(400f)
    }

    @Test fun noMovementIsNoChange() {
        val p = pinch()
        p.update(Pt(450f, 400f), Pt(550f, 400f))
        assertThat(p.changed).isFalse()
    }
}
