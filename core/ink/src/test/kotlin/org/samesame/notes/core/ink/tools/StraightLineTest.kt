package org.samesame.notes.core.ink.tools

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.ink.PenBrushes
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.core.model.PenType

class StraightLineTest {
    @Test
    fun samplesIncludeBothEndsAndStayOnTheLine() {
        val pts = StraightLine.samples(0f, 0f, 30f, 40f, stepPu = 10f)
        assertThat(pts.first()).isEqualTo(0f to 0f)
        assertThat(pts.last()).isEqualTo(30f to 40f)
        assertThat(pts).hasSize(6) // length 50 / step 10 -> 5 segments
        pts.forEach { (x, y) -> assertThat(y * 3f).isWithin(0.01f).of(x * 4f) }
    }

    @Test
    fun degenerateLineStillHasTwoPoints() {
        assertThat(StraightLine.samples(5f, 5f, 5f, 5f)).hasSize(2)
    }

    @Test
    fun highlighterTipsMapToFlatOrRoundAndStraight() {
        assertThat((0..3).map(PenCatalog::isStraightTip)).containsExactly(false, true, false, true).inOrder()
        assertThat((0..3).map { PenCatalog.highlighterFor(it).roundTip }).containsExactly(false, false, true, true).inOrder()
        assertThat((0..3).map { PenCatalog.highlighterFor(it).fixedAlpha }.toSet()).containsExactly(115)
    }

    @Test
    fun roundMarkerKeyParsesAsHighlighterKindAndExistingKeysAreUnchanged() {
        assertThat(PenBrushes.parseKey(PenBrushes.ROUND_MARKER_KEY)).isEqualTo(PenType.Highlighter to false)
        assertThat(PenBrushes.keyOf(PenType.Highlighter, true)).isEqualTo("samesame/Highlighter/pressure")
        assertThat(PenBrushes.keyOf(PenType.Fountain, false)).isEqualTo("samesame/Fountain/fixed")
    }
}
