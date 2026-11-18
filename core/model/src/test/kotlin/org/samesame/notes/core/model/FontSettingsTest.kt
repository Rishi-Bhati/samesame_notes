package org.samesame.notes.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FontSettingsTest {
    @Test
    fun clampKeepsRangeAndSnapsToStep() {
        assertThat(FontScaling.clamp(10)).isEqualTo(85)
        assertThat(FontScaling.clamp(500)).isEqualTo(150)
        assertThat(FontScaling.clamp(100)).isEqualTo(100)
        assertThat(FontScaling.clamp(102)).isEqualTo(100)
        assertThat(FontScaling.clamp(103)).isEqualTo(105)
    }

    @Test
    fun appScaleMultipliesSectionScale() {
        val f = FontSettings(appPercent = 120, sectionPercent = mapOf(FontSection.Files to 85))
        assertThat(f.scaleFor(FontSection.Files)).isWithin(1e-4f).of(1.2f * 0.85f)
        assertThat(f.scaleFor(FontSection.Notes)).isWithin(1e-4f).of(1.2f)
        assertThat(FontScaling.effective(150, 150)).isWithin(1e-4f).of(2.25f)
    }

    @Test
    fun defaultsChangeNothing() {
        val f = FontSettings()
        FontSection.entries.forEach { assertThat(f.scaleFor(it)).isEqualTo(1f) }
        assertThat(f.sizesChanged).isFalse()
        assertThat(f.newTextSize()).isNull()
    }

    @Test
    fun sizesChangedSeesEitherLevel() {
        assertThat(FontSettings(appPercent = 110).sizesChanged).isTrue()
        assertThat(FontSettings(sectionPercent = mapOf(FontSection.Drawer to 90)).sizesChanged).isTrue()
    }

    @Test
    fun newTextSizeFollowsEditorAndAppScales() {
        assertThat(FontSettings(sectionPercent = mapOf(FontSection.Editor to 150)).newTextSize()).isEqualTo(26f)
        assertThat(FontSettings(appPercent = 85).newTextSize()).isEqualTo(14f)
        // Other sections do not touch new text.
        assertThat(FontSettings(sectionPercent = mapOf(FontSection.Notes to 150)).newTextSize()).isNull()
    }

    @Test
    fun fontChoiceRoundTripsAndUnknownFallsBack() {
        listOf(FontChoice(), FontChoice(FontKind.Custom), FontChoice(FontKind.Bundled, BundledFonts.ATKINSON)).forEach {
            assertThat(FontChoice.parse(it.serialize())).isEqualTo(it)
        }
        assertThat(FontChoice.parse("bundled:comic")).isEqualTo(FontChoice())
        assertThat(FontChoice.parse(null)).isEqualTo(FontChoice())
        assertThat(FontChoice.parse("garbage")).isEqualTo(FontChoice())
    }

    @Test
    fun customFontFileChecks() {
        val ttf = byteArrayOf(0, 1, 0, 0)
        assertThat(CustomFontFile.check(1000, ttf)).isEqualTo(CustomFontFile.Verdict.Ok)
        assertThat(CustomFontFile.check(1000, "OTTO".toByteArray())).isEqualTo(CustomFontFile.Verdict.Ok)
        assertThat(CustomFontFile.check(1000, "ttcf".toByteArray())).isEqualTo(CustomFontFile.Verdict.Ok)
        assertThat(CustomFontFile.check(1000, "%PDF".toByteArray())).isEqualTo(CustomFontFile.Verdict.NotAFont)
        assertThat(CustomFontFile.check(2, byteArrayOf(0, 1))).isEqualTo(CustomFontFile.Verdict.NotAFont)
        assertThat(CustomFontFile.check(0, ttf)).isEqualTo(CustomFontFile.Verdict.Empty)
        assertThat(CustomFontFile.check(Limits.MAX_CUSTOM_FONT_BYTES + 1L, ttf)).isEqualTo(CustomFontFile.Verdict.TooLarge)
        assertThat(CustomFontFile.check(Limits.MAX_CUSTOM_FONT_BYTES.toLong(), ttf)).isEqualTo(CustomFontFile.Verdict.Ok)
    }
}
