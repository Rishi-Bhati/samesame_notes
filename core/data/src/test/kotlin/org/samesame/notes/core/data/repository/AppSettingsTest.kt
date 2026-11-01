package org.samesame.notes.core.data.repository

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NewNoteStyle
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.PdfPageSize
import org.samesame.notes.core.model.ThemeMode

class AppSettingsTest {
    @Test
    fun emptyPreferencesGiveReferenceDefaults() {
        val s = mutablePreferencesOf().toAppSettings()
        assertThat(s).isEqualTo(AppSettings())
        assertThat(s.autoSaveNotes).isTrue()
        assertThat(s.showLinks).isTrue()
        assertThat(s.showWebPreviews).isFalse()
        assertThat(s.hideScrollBar).isFalse()
        assertThat(s.blockBackKey).isFalse()
        assertThat(s.newNote.pageMode).isEqualTo(PageMode.Pages)
        assertThat(s.newNote.backgroundColor).isNull()
        assertThat(s.newNote.pdfPageSize).isEqualTo(PdfPageSize.Original)
        assertThat(s.themeMode).isEqualTo(ThemeMode.System)
    }

    @Test
    fun pdfPageSizeRoundTrips() {
        val prefs = mutablePreferencesOf()
        PdfPageSize.entries.forEach { size ->
            prefs.writeAppSettings(AppSettings(newNote = NewNoteStyle(pdfPageSize = size)))
            assertThat(prefs.toAppSettings().newNote.pdfPageSize).isEqualTo(size)
        }
    }

    @Test
    fun themeModeRoundTrips() {
        val prefs = mutablePreferencesOf()
        ThemeMode.entries.forEach { mode ->
            prefs.writeAppSettings(AppSettings(themeMode = mode))
            assertThat(prefs.toAppSettings().themeMode).isEqualTo(mode)
        }
    }

    @Test
    fun unknownThemeFallsBackToSystem() {
        val prefs = mutablePreferencesOf()
        prefs.writeAppSettings(AppSettings())
        prefs[androidx.datastore.preferences.core.stringPreferencesKey("theme_mode")] = "Sepia"
        assertThat(prefs.toAppSettings().themeMode).isEqualTo(ThemeMode.System)
    }

    @Test
    fun writeThenReadRoundTrips() {
        val changed = AppSettings(
            autoSaveNotes = false, showLinks = false, showWebPreviews = true, hideScrollBar = true, blockBackKey = true,
            toolbarChangeStyle = false, toolbarEasyWritingPad = false, toolbarZoomLock = false, adjustColourInDarkMode = false,
            newNote = NewNoteStyle(
                PageMode.Infinite, PageRatio.Long, PageTemplate.GridWide, 0xFFFFEC94, PdfPageSize.FitToNote,
            ),
        )
        val prefs = mutablePreferencesOf()
        prefs.writeAppSettings(changed)
        assertThat(prefs.toAppSettings()).isEqualTo(changed)
        prefs.writeAppSettings(changed.copy(newNote = changed.newNote.copy(backgroundColor = null)))
        assertThat(prefs.toAppSettings().newNote.backgroundColor).isNull()
    }

    @Test
    fun unknownEnumNamesFallBack() {
        val prefs = mutablePreferencesOf()
        prefs.writeAppSettings(AppSettings())
        prefs[androidx.datastore.preferences.core.stringPreferencesKey("new_note_template")] = "Bogus"
        assertThat(prefs.toAppSettings().newNote.template).isEqualTo(PageTemplate.Blank)
    }
}
