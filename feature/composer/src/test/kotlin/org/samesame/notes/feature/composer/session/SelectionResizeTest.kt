package org.samesame.notes.feature.composer.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.feature.composer.session.tools.ResizeHandle
import org.samesame.notes.feature.composer.session.tools.SelectionResize

class SelectionResizeTest {
    private val box = Rect(100f, 200f, 300f, 400f)

    private fun mapBox(m: androidx.ink.geometry.AffineTransform): Rect {
        val a = SelectionResize.apply(m, box.topLeft); val b = SelectionResize.apply(m, box.bottomRight)
        return Rect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    @Test fun `bottom right corner drag grows the box about the top left corner`() {
        val m = SelectionResize.matrix(ResizeHandle.BR, box, Offset(300f, 400f), Offset(500f, 600f))
        assertThat(mapBox(m)).isEqualTo(Rect(100f, 200f, 500f, 600f))
    }

    @Test fun `top left corner drag pivots on the bottom right`() {
        val m = SelectionResize.matrix(ResizeHandle.TL, box, Offset(100f, 200f), Offset(200f, 300f))
        assertThat(mapBox(m)).isEqualTo(Rect(200f, 300f, 300f, 400f))
    }

    @Test fun `edge drag scales one axis and the box matches the transformed content`() {
        val m = SelectionResize.matrix(ResizeHandle.R, box, Offset(300f, 300f), Offset(200f, 300f))
        // a point on the right edge of the content lands on the new right edge
        assertThat(SelectionResize.apply(m, Offset(300f, 250f))).isEqualTo(Offset(200f, 250f))
        assertThat(SelectionResize.apply(m, Offset(100f, 250f))).isEqualTo(Offset(100f, 250f))
        assertThat(SelectionResize.apply(m, Offset(200f, 400f))).isEqualTo(Offset(150f, 400f))
    }

    @Test fun `aspect lock keeps the ratio on corners`() {
        val m = SelectionResize.matrix(ResizeHandle.BR, box, Offset(300f, 400f), Offset(500f, 420f), lockAspect = true)
        val r = mapBox(m)
        assertThat(r.width / r.height).isWithin(1e-4f).of(1f)
        assertThat(r.width).isEqualTo(400f)
    }

    @Test fun `never shrinks below the minimum span nor flips`() {
        val m = SelectionResize.matrix(ResizeHandle.BR, box, Offset(300f, 400f), Offset(0f, 0f), minSpan = 48f)
        val r = mapBox(m)
        assertThat(r.width).isAtLeast(47.9f); assertThat(r.height).isAtLeast(47.9f)
    }

    @Test fun `move translates`() {
        val m = SelectionResize.matrix(ResizeHandle.Move, box, Offset(150f, 250f), Offset(160f, 230f))
        assertThat(mapBox(m)).isEqualTo(Rect(110f, 180f, 310f, 380f))
    }
}
