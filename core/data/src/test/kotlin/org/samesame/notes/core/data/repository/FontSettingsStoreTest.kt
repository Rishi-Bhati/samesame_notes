package org.samesame.notes.core.data.repository

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.BundledFonts
import org.samesame.notes.core.model.FontChoice
import org.samesame.notes.core.model.FontKind
import org.samesame.notes.core.model.FontSection
import org.samesame.notes.core.model.FontSettings

class FontSettingsStoreTest {
    @Test
    fun emptyPreferencesGiveDefaultFont() {
        assertThat(mutablePreferencesOf().toAppSettings().font).isEqualTo(FontSettings())
    }

    @Test
    fun fontSettingsRoundTrip() {
        val font = FontSettings(
            appPercent = 130,
            sectionPercent = mapOf(FontSection.Notes to 90, FontSection.Editor to 150),
            choice = FontChoice(FontKind.Bundled, BundledFonts.PLEX_SERIF),
            customFontName = "My font",
        )
        val prefs = mutablePreferencesOf()
        prefs.writeAppSettings(AppSettings(font = font))
        assertThat(prefs.toAppSettings().font).isEqualTo(font)
    }

    @Test
    fun resettingASectionRemovesItsKey() {
        val prefs = mutablePreferencesOf()
        prefs.writeAppSettings(AppSettings(font = FontSettings(sectionPercent = mapOf(FontSection.Files to 120))))
        prefs.writeAppSettings(AppSettings(font = FontSettings()))
        assertThat(prefs.toAppSettings().font.sectionPercent).isEmpty()
    }

    @Test
    fun outOfRangeStoredValuesAreClamped() {
        val prefs = mutablePreferencesOf()
        prefs[intPreferencesKey("font_app_percent")] = 900
        prefs[intPreferencesKey("font_section_drawer")] = 3
        val font = prefs.toAppSettings().font
        assertThat(font.appPercent).isEqualTo(150)
        assertThat(font.section(FontSection.Drawer)).isEqualTo(85)
    }
}
