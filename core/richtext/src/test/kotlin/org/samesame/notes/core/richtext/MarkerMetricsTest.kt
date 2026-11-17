package org.samesame.notes.core.richtext

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.Limits
import kotlin.math.roundToInt

/** Pure metrics shared by the editor's marker drawing and its leading margin. */
class MarkerMetricsTest {

    private fun fontSizePu(size: Float?) = BodyTextMetrics.fontSizePu(size)

    @Test fun emptyParagraphUsesTheSizeOfTheTextItContinues() {
        // "Vuuvuvu\n": the empty paragraph starts at 8, so its marker follows the last character's size,
        // in the same model-point unit as the filled paragraph's first character.
        val sizes = mapOf(0 to 17f, 6 to 17f, 7 to 17f)
        val bodyLength = 8
        val filled = BodyTextMetrics.markerEm(bodyLength, 0) { sizes[it] }
        val empty = BodyTextMetrics.markerEm(bodyLength, bodyLength) { sizes[it] }
        assertThat(empty).isEqualTo(filled)
        assertThat(empty).isEqualTo(fontSizePu(17f))
    }

    @Test fun emptyParagraphDoesNotFallBackToTheNoteDefault() {
        // The preceding text is size 32; an empty item must not silently use the default 17.
        val em = BodyTextMetrics.markerEm(4, 4) { 32f }
        assertThat(em).isEqualTo(fontSizePu(32f))
        assertThat(em).isNotEqualTo(fontSizePu(Limits.DEFAULT_FONT_SIZE))
    }

    @Test fun nonEmptyParagraphUsesItsOwnFirstCharacter() {
        val em = BodyTextMetrics.markerEm(8, 4) { offset -> if (offset == 4) 10f else 32f }
        assertThat(em).isEqualTo(fontSizePu(10f))
    }

    @Test fun markerEmMatchesModelFontSizeAcrossSizes() {
        for (pt in listOf(10f, 17f, 32f, 96f)) {
            val filled = BodyTextMetrics.markerEm(3, 0) { pt }
            val empty = BodyTextMetrics.markerEm(3, 3) { pt }
            assertThat(empty).isEqualTo(filled)
            assertThat(empty).isEqualTo((pt * Limits.FONT_PU_PER_POINT * BodyTextMetrics.GLYPH_SCALE))
        }
    }

    @Test fun fontSizePuKeepsTheDefaultWhenUnset() {
        assertThat(fontSizePu(null)).isEqualTo(
            Limits.DEFAULT_FONT_SIZE * Limits.FONT_PU_PER_POINT * BodyTextMetrics.GLYPH_SCALE,
        )
        assertThat(fontSizePu(17f)).isEqualTo(fontSizePu(null))
    }

    @Test fun markerColumnsMatchMeasuredReferenceAtDefaultSize() {
        val em = fontSizePu(17f)
        assertThat(BodyTextMetrics.markerWidth(em)).isEqualTo(80f)
        assertThat(BodyTextMetrics.markerInset(em)).isWithin(0.01f).of(6.5f)
        // The box fits inside the marker column with the same gap a plain checkbox leaves before its text.
        val gap = BodyTextMetrics.markerWidth(em) - BodyTextMetrics.markerInset(em) - em * BodyTextMetrics.CHECKBOX_SIZE_EM
        assertThat(gap).isWithin(0.1f).of(23.7f)
    }

    @Test fun markerColumnsScaleWithSize() {
        for (pt in listOf(10f, 17f, 32f)) {
            val em = fontSizePu(pt)
            val expected = (80f * em / BodyTextMetrics.DEFAULT_TEXT_SIZE_PU).roundToInt().toFloat()
            assertThat(BodyTextMetrics.markerWidth(em)).isEqualTo(expected)
            assertThat(BodyTextMetrics.markerInset(em)).isGreaterThan(0f)
        }
    }
}
