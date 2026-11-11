package org.samesame.notes.feature.composer.panels

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.feature.composer.panels.GlideJoystick.Companion.DEAD_ZONE_DP
import org.samesame.notes.feature.composer.panels.GlideJoystick.Companion.FULL_SPEED_DP
import org.samesame.notes.feature.composer.panels.GlideJoystick.Companion.MAX_SPEED
import org.samesame.notes.feature.composer.panels.GlideJoystick.Companion.MIN_SPEED
import org.samesame.notes.feature.composer.panels.GlideJoystick.Companion.RAMP_DP

/** rel 0.2.0 owner feedback: the glide is a rate-controlled joystick around the popup's centre. */
class GlideJoystickTest {
    // Pixel 6 Pro density; the popup: 3 headings + Body + 26 sizes = 30 rows, "17" is flat row 13.
    private val dp = 2.625f
    private val count = FontSizeRows.count(TextSizeCatalog.SIZES)
    private val seventeen = FontSizeRows.anchorRow(null, 17, TextSizeCatalog.SIZES)
    private val frame = 1f / 60f

    private fun armedAt(row: Int) = GlideJoystick(dp).apply {
        start(row, count)
        advance(0f, frame)
        assertThat(armed).isTrue()
    }

    /** Frames at [offsetDp] for [seconds] at [hz]. */
    private fun GlideJoystick.hold(offsetDp: Float, seconds: Float, hz: Float = 60f): Int {
        var row = row
        repeat((seconds * hz).toInt()) { row = advance(offsetDp * dp, 1f / hz) }
        return row
    }

    @Test fun nothingMovesInsideTheDeadZone() {
        val j = GlideJoystick(dp)
        for (o in -DEAD_ZONE_DP.toInt()..DEAD_ZONE_DP.toInt()) assertThat(j.velocity(o * dp)).isEqualTo(0f)
        assertThat(j.velocity((DEAD_ZONE_DP + 1f) * dp)).isGreaterThan(0f)
        assertThat(j.velocity(-(DEAD_ZONE_DP + 1f) * dp)).isLessThan(0f)
        val k = armedAt(seventeen)
        assertThat(k.hold(DEAD_ZONE_DP - 1f, 3f)).isEqualTo(seventeen)
        assertThat(k.hold(-(DEAD_ZONE_DP - 1f), 3f)).isEqualTo(seventeen)
        assertThat(k.position).isEqualTo(seventeen.toFloat())
    }

    @Test fun speedIsMonotonicSymmetricContinuousAndCapped() {
        val j = GlideJoystick(dp)
        var last = 0f
        for (i in 0..4000) {
            val o = i * 0.1f * dp
            val v = j.velocity(o)
            assertThat(v).isAtLeast(last)
            assertThat(v).isAtMost(MAX_SPEED)
            assertThat(j.velocity(-o)).isEqualTo(-v)
            // Continuous: no jump between neighbouring 0.1 dp offsets.
            assertThat(v - last).isLessThan(0.05f)
            last = v
        }
        assertThat(j.velocity((DEAD_ZONE_DP + FULL_SPEED_DP) * dp)).isEqualTo(MAX_SPEED)
        assertThat(j.velocity(1000f * dp)).isEqualTo(MAX_SPEED)
        // Fine control near the centre: just past the ramp the speed is barely above the minimum.
        assertThat(j.velocity((DEAD_ZONE_DP + RAMP_DP) * dp)).isWithin(0.1f).of(MIN_SPEED)
        // Eased: halfway to full travel is well under half the top speed.
        assertThat(j.velocity((DEAD_ZONE_DP + FULL_SPEED_DP / 2) * dp)).isLessThan(MAX_SPEED / 2)
    }

    @Test fun notArmedUntilTheFingerReachesTheCentre() {
        val j = GlideJoystick(dp)
        j.start(seventeen, count)
        // The finger comes from the button, far below the centre: nothing moves on the way up.
        for (o in 260 downTo 20 step 4) assertThat(j.advance(o * dp, frame)).isEqualTo(seventeen)
        assertThat(j.armed).isFalse()
        assertThat(j.position).isEqualTo(seventeen.toFloat())
        j.advance(10f * dp, frame)
        assertThat(j.armed).isTrue()
    }

    @Test fun crossingTheCentreInOneEventArms() {
        val j = GlideJoystick(dp)
        j.start(seventeen, count)
        j.advance(240f * dp, frame)
        j.advance(-100f * dp, frame)
        assertThat(j.armed).isTrue()
        assertThat(j.hold(-100f, 0.5f)).isLessThan(seventeen)
    }

    @Test fun unknownCentreMovesNothing() {
        val j = GlideJoystick(dp)
        j.start(seventeen, count)
        repeat(100) { assertThat(j.advance(Float.NaN, frame)).isEqualTo(seventeen) }
        assertThat(j.armed).isFalse()
    }

    @Test fun integrationIsFrameRateIndependentAndStallsDoNotLeap() {
        val a = armedAt(0).apply { hold(DEAD_ZONE_DP + FULL_SPEED_DP, 1f, 60f) }
        val b = armedAt(0).apply { hold(DEAD_ZONE_DP + FULL_SPEED_DP, 1f, 120f) }
        assertThat(a.position).isWithin(0.05f).of(MAX_SPEED)
        assertThat(b.position).isWithin(0.05f).of(a.position)
        // A 2 s frame stall is integrated as one short frame.
        val c = armedAt(0)
        c.advance((DEAD_ZONE_DP + FULL_SPEED_DP) * dp, 2f)
        assertThat(c.position).isAtMost(MAX_SPEED * GlideJoystick.MAX_FRAME_S + 0.001f)
        // Negative dt (clock oddity) moves nothing.
        val d = armedAt(seventeen)
        d.advance(200f * dp, -1f)
        assertThat(d.position).isEqualTo(seventeen.toFloat())
    }

    @Test fun theWholeRangeIsReachableInOneGestureInUnderTwoSeconds() {
        val down = armedAt(0)
        var t = 0f
        while (down.row < count - 1) { down.advance((DEAD_ZONE_DP + FULL_SPEED_DP) * dp, frame); t += frame; assertThat(t).isLessThan(2f) }
        val up = armedAt(count - 1)
        t = 0f
        while (up.row > 0) { up.advance(-(DEAD_ZONE_DP + FULL_SPEED_DP) * dp, frame); t += frame; assertThat(t).isLessThan(2f) }
        // Clamped at the ends, however long the finger stays.
        assertThat(down.hold(300f, 3f)).isEqualTo(count - 1)
        assertThat(up.hold(-300f, 3f)).isEqualTo(0)
    }

    @Test fun oneStepIsPrecise() {
        // Just past the dead zone the first step comes after ~0.2 s (then one per ~0.4 s): time to feel the tick and come back.
        val j = armedAt(seventeen)
        var t = 0f
        while (j.row == seventeen) { j.advance((DEAD_ZONE_DP + RAMP_DP) * dp, frame); t += frame }
        assertThat(j.row).isEqualTo(seventeen + 1)
        assertThat(t).isGreaterThan(0.15f)
        assertThat(t).isLessThan(0.5f)
        // Back to the centre: it settles exactly on that row (no drift to the next one).
        assertThat(j.hold(0f, 0.5f)).isEqualTo(seventeen + 1)
        assertThat(j.position).isEqualTo((seventeen + 1).toFloat())
    }

    @Test fun restingSnapsOntoTheNearestRow() {
        for (fraction in listOf(0.2f, 0.45f, 0.55f, 0.8f)) {
            val j = armedAt(5)
            // Move to 5 + fraction at a known slow speed, then rest.
            while (j.position < 5f + fraction) j.advance((DEAD_ZONE_DP + RAMP_DP) * dp, 1f / 1000f)
            val expected = Math.round(j.position)
            j.hold(0f, 0.4f)
            assertThat(j.position).isEqualTo(expected.toFloat())
        }
    }

    @Test fun heldStopsTheTravel() {
        val j = armedAt(seventeen)
        j.hold(80f, 0.3f)
        val row = j.row
        repeat(120) { j.advance(200f * dp, frame, held = true) }
        assertThat(j.row).isEqualTo(row)
        assertThat(j.position).isEqualTo(row.toFloat())
    }

    @Test fun unstartedAndResetHaveNoRow() {
        val j = GlideJoystick(dp)
        assertThat(j.row).isEqualTo(-1)
        assertThat(j.advance(0f, frame)).isEqualTo(-1)
        j.start(40, count)
        assertThat(j.row).isEqualTo(count - 1)
        j.reset()
        assertThat(j.row).isEqualTo(-1)
        assertThat(j.started).isFalse()
    }
}
