package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.document.Pt

class StraightenTest {
    @Test fun aWobblyLineBecomesItsChord() {
        val pts = (0..20).map { i -> Pt(i * 10f, 100f + i * 6f + if (i % 2 == 0) 3f else -3f) }
        val s = Straighten.segmentFor(pts)!!
        assertThat(s.a).isEqualTo(pts.first())
        assertThat(s.b).isEqualTo(pts.last())
    }

    @Test fun nearlyHorizontalLinesSnapToTheAxisAboutTheirMiddle() {
        val pts = listOf(Pt(0f, 0f), Pt(100f, 3f), Pt(200f, 6f)) // ~1.7 degrees
        val s = Straighten.segmentFor(pts)!!
        assertThat(s.a.y).isWithin(1e-3f).of(3f)
        assertThat(s.b.y).isWithin(1e-3f).of(3f)
        assertThat(s.b.x - s.a.x).isWithin(0.1f).of(200.09f)
        val v = Straighten.snapToAxis(Straighten.Segment(Pt(100f, 0f), Pt(104f, 100f)))
        assertThat(v.a.x).isWithin(1e-3f).of(102f)
        assertThat(v.b.x).isWithin(1e-3f).of(102f)
    }

    @Test fun curvesWritingAndDotsAreLeftAlone() {
        val arc = (0..20).map { i -> val a = Math.PI * i / 20; Pt((100 * Math.cos(a)).toFloat(), (100 * Math.sin(a)).toFloat()) }
        assertThat(Straighten.segmentFor(arc)).isNull()
        val zigzag = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(0f, 2f), Pt(100f, 2f))
        assertThat(Straighten.segmentFor(zigzag)).isNull()
        assertThat(Straighten.segmentFor(listOf(Pt(0f, 0f), Pt(5f, 5f)))).isNull()
        assertThat(Straighten.segmentFor(listOf(Pt(0f, 0f)))).isNull()
    }

    @Test fun diagonalsKeepTheirAngle() {
        val s = Straighten.segmentFor(listOf(Pt(0f, 0f), Pt(50f, 52f), Pt(100f, 100f)))!!
        assertThat(s).isEqualTo(Straighten.Segment(Pt(0f, 0f), Pt(100f, 100f)))
    }
}
