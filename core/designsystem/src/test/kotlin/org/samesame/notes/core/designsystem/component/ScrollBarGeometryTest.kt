package org.samesame.notes.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollBarGeometryTest {
    @Test fun `fitting content fills the track`() {
        assertThat(ScrollBarGeometry.thumbFraction(800f, 800f)).isWithin(0.0001f).of(1f)
        assertThat(ScrollBarGeometry.thumbFraction(500f, 800f)).isWithin(0.0001f).of(1f)
    }

    @Test fun `thumb share is viewport over content`() {
        assertThat(ScrollBarGeometry.thumbFraction(1600f, 800f)).isWithin(0.0001f).of(0.5f)
        assertThat(ScrollBarGeometry.thumbFraction(3200f, 800f)).isWithin(0.0001f).of(0.25f)
    }

    @Test fun `empty sizes do not divide by zero`() {
        assertThat(ScrollBarGeometry.thumbFraction(0f, 0f)).isEqualTo(1f)
    }

    @Test fun `thumb length keeps a minimum and never exceeds the track`() {
        assertThat(ScrollBarGeometry.thumbLength(1000f, 0.01f, 36f)).isEqualTo(36f)
        assertThat(ScrollBarGeometry.thumbLength(100f, 1f, 36f)).isEqualTo(100f)
        assertThat(ScrollBarGeometry.thumbLength(1000f, 0.5f, 36f)).isEqualTo(500f)
    }

    @Test fun `offset uses the travel left after the thumb`() {
        assertThat(ScrollBarGeometry.thumbOffset(1000f, 200f, 0f)).isEqualTo(0f)
        assertThat(ScrollBarGeometry.thumbOffset(1000f, 200f, 1f)).isEqualTo(800f)
        assertThat(ScrollBarGeometry.thumbOffset(1000f, 200f, 0.5f)).isEqualTo(400f)
    }

    @Test fun `offset clamps out of range fractions`() {
        assertThat(ScrollBarGeometry.thumbOffset(1000f, 200f, 2f)).isEqualTo(800f)
        assertThat(ScrollBarGeometry.thumbOffset(1000f, 200f, -1f)).isEqualTo(0f)
    }

    @Test fun `pointer fraction centres the thumb`() {
        // Track 1000, thumb 200 => travel 800. Centre of thumb at 100 lands at fraction 0.
        assertThat(ScrollBarGeometry.scrollFractionFor(1000f, 200f, 100f)).isWithin(0.0001f).of(0f)
        assertThat(ScrollBarGeometry.scrollFractionFor(1000f, 200f, 500f)).isWithin(0.0001f).of(0.5f)
        assertThat(ScrollBarGeometry.scrollFractionFor(1000f, 200f, 900f)).isWithin(0.0001f).of(1f)
    }

    @Test fun `no travel means top`() {
        assertThat(ScrollBarGeometry.scrollFractionFor(500f, 500f, 400f)).isEqualTo(0f)
    }

    @Test fun `needed only when content is longer than the viewport`() {
        assertThat(ScrollBarGeometry.isNeeded(900f, 800f)).isTrue()
        assertThat(ScrollBarGeometry.isNeeded(800f, 800f)).isFalse()
    }

    @Test fun `scroll fraction maps document offsets onto the stem`() {
        assertThat(ScrollBarGeometry.scrollFraction(0f, 1600f, 800f)).isEqualTo(0f)
        assertThat(ScrollBarGeometry.scrollFraction(400f, 1600f, 800f)).isWithin(0.0001f).of(0.5f)
        assertThat(ScrollBarGeometry.scrollFraction(800f, 1600f, 800f)).isWithin(0.0001f).of(1f)
        assertThat(ScrollBarGeometry.scrollFraction(400f, 500f, 800f)).isEqualTo(0f)
    }

    @Test fun `relative drag follows the finger without a jump`() {
        // Editor fast scroll bar: fixed 40 px thumb on a 1000 px track (960 px travel), thumb at 480 (fraction 0.5).
        assertThat(ScrollBarGeometry.dragFraction(960f, 0f, 480f, 0f)).isWithin(0.0001f).of(0.5f)
        assertThat(ScrollBarGeometry.dragFraction(960f, 0f, 480f, 96f)).isWithin(0.0001f).of(0.6f)
        assertThat(ScrollBarGeometry.dragFraction(960f, 0f, 480f, -2000f)).isEqualTo(0f)
        assertThat(ScrollBarGeometry.dragFraction(960f, 0f, 480f, 2000f)).isEqualTo(1f)
        assertThat(ScrollBarGeometry.dragFraction(40f, 40f, 0f, 10f)).isEqualTo(0f)
    }
}
