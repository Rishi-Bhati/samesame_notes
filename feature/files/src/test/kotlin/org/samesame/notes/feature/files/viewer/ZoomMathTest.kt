package org.samesame.notes.feature.files.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ZoomMathTest {
    @Test fun fitsInsideTheContainer() {
        assertThat(ZoomMath.fit(1000f, 2000f, 500f, 500f)).isEqualTo(1000f to 1000f)
        assertThat(ZoomMath.fit(1000f, 500f, 400f, 400f)).isEqualTo(500f to 500f)
        assertThat(ZoomMath.fit(0f, 500f, 400f, 400f)).isEqualTo(0f to 0f)
    }

    @Test fun fittedImageCannotBePanned() {
        val z = ZoomMath.transform(Zoom(), 1f, 0f, 0f, 300f, 300f, 1000f, 2000f, 1000f, 1000f)
        assertThat(z).isEqualTo(Zoom())
    }

    @Test fun scaleIsClamped() {
        assertThat(ZoomMath.transform(Zoom(), 100f, 0f, 0f, 0f, 0f, 1000f, 2000f, 1000f, 1000f).scale).isEqualTo(ZoomMath.MAX_SCALE)
        assertThat(ZoomMath.transform(Zoom(2f), 0.01f, 0f, 0f, 0f, 0f, 1000f, 2000f, 1000f, 1000f).scale).isEqualTo(1f)
    }

    @Test fun zoomKeepsThePointUnderTheFingers() {
        // The content point under centroid c is (c - offset) / scale; it must be the same before and after.
        val before = Zoom(2f, 100f, 0f)
        val after = ZoomMath.transform(before, 1.5f, 200f, 0f, 0f, 0f, 1000f, 1000f, 1000f, 1000f)
        assertThat((200f - after.x) / after.scale).isWithin(0.01f).of((200f - before.x) / before.scale)
    }

    @Test fun panStopsAtTheEdges() {
        // 1000 wide at 2x = 2000: the centre may move 500 either way; the height (500 at 2x = 1000) fits exactly.
        val z = ZoomMath.transform(Zoom(2f), 1f, 0f, 0f, 9999f, 9999f, 1000f, 1000f, 1000f, 500f)
        assertThat(z.x).isEqualTo(500f)
        assertThat(z.y).isEqualTo(0f)
    }

    @Test fun doubleTapZoomsInThenBack() {
        val zoomedIn = ZoomMath.doubleTap(Zoom(), 0f, 0f, 1000f, 1000f, 1000f, 1000f)
        assertThat(zoomedIn.scale).isEqualTo(ZoomMath.DOUBLE_TAP_SCALE)
        assertThat(ZoomMath.doubleTap(zoomedIn, 0f, 0f, 1000f, 1000f, 1000f, 1000f)).isEqualTo(Zoom())
    }

    @Test fun sampleSizeRespectsPixelsAndSide() {
        assertThat(ZoomMath.sampleSize(4000, 3000, 12_000_000, 8192)).isEqualTo(1)
        assertThat(ZoomMath.sampleSize(8000, 6000, 12_000_000, 8192)).isEqualTo(2)
        assertThat(ZoomMath.sampleSize(1080, 30000, 12_000_000, 8192)).isEqualTo(4)
        assertThat(ZoomMath.sampleSize(0, 10, 12_000_000, 8192)).isEqualTo(0)
        assertThat(ZoomMath.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 12_000_000, 8192)).isEqualTo(0)
    }
}
