package org.samesame.notes.feature.composer.toolbar

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.data.repository.EditorPreferences
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.core.model.PenType

/**
 * The live (in-progress) stroke paints with this style; it must be the highlighter's / pen's own colour, alpha and tip,
 * not another tool's (the reported bug showed the default pen blue while highlighting).
 */
class PenStylesTest {

    private val prefs = EditorPreferences(
        penType = PenType.Fountain,
        penLevel = 30,
        penColor = 0xFF335FFF,
        penPressureSensitive = true,
        highlighterTip = 0,
        highlighterLevel = 50,
        highlighterColor = 0xFFFFB426,
        highlighterOpacity = 45,
    )

    @Test fun highlighterUsesItsOwnColourAndOpacity() {
        val style = PenStyles.current(PenModeTool.Highlighter, prefs)
        assertThat(style.color).isEqualTo(0xFFFFB426)
        // 45 % of 255 = 114.75 -> 115 = 0x73.
        assertThat(style.argb).isEqualTo(0x73FFB426L)
        assertThat(style.spec).isSameInstanceAs(PenCatalog.highlighter)
        assertThat(style.level).isEqualTo(50)
        assertThat(style.straightLine).isFalse()
    }

    @Test fun highlighterTipsPickTheFlatOrRoundFamilyAndTheStraightLineFlag() {
        assertThat((0..3).map { PenStyles.current(PenModeTool.Highlighter, prefs.copy(highlighterTip = it)).spec.roundTip })
            .containsExactly(false, false, true, true).inOrder()
        assertThat((0..3).map { PenStyles.current(PenModeTool.Highlighter, prefs.copy(highlighterTip = it)).straightLine })
            .containsExactly(false, true, false, true).inOrder()
    }

    @Test fun penUsesThePickedColourAndPressureSettingWithoutAForcedAlpha() {
        val style = PenStyles.current(PenModeTool.Pen, prefs.copy(penType = PenType.Pen, penColor = 0xFF123456, penPressureSensitive = false))
        assertThat(style.spec).isSameInstanceAs(PenCatalog.specFor(PenType.Pen))
        assertThat(style.color).isEqualTo(0xFF123456)
        assertThat(style.argb).isEqualTo(0xFF123456)
        assertThat(style.pressure).isFalse()
        assertThat(style.straightLine).isFalse()
    }

    @Test fun translucentPensKeepTheirForcedOpacity() {
        val pencil = PenStyles.current(PenModeTool.Pen, prefs.copy(penType = PenType.Pencil, penColor = 0xFFFF0000))
        assertThat(pencil.argb).isEqualTo(0xA0FF0000L) // PenType.Pencil.fixedAlpha = 160
    }

    @Test fun opacityPercentMapsToAlphaAndIsClamped() {
        assertThat(PenStyles.alpha(0)).isEqualTo(0)
        assertThat(PenStyles.alpha(45)).isEqualTo(115)
        assertThat(PenStyles.alpha(100)).isEqualTo(255)
        assertThat(PenStyles.alpha(250)).isEqualTo(255)
        assertThat(PenStyles.alpha(-5)).isEqualTo(0)
    }
}
