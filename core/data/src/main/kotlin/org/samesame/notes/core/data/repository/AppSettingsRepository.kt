package org.samesame.notes.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NewNoteStyle
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.PdfPageSize
import org.samesame.notes.core.model.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton

interface AppSettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

private val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

private object SettingsKeys {
    val autoSave = booleanPreferencesKey("auto_save_notes")
    val showLinks = booleanPreferencesKey("show_links")
    val webPreviews = booleanPreferencesKey("show_web_previews")
    val hideScrollBar = booleanPreferencesKey("hide_scroll_bar")
    val blockBack = booleanPreferencesKey("block_back_key")
    val toolbarChangeStyle = booleanPreferencesKey("toolbar_change_style")
    val toolbarEasyPad = booleanPreferencesKey("toolbar_easy_writing_pad")
    val toolbarZoomLock = booleanPreferencesKey("toolbar_zoom_lock")
    val adjustDark = booleanPreferencesKey("adjust_colour_in_dark_mode")
    val newPageMode = stringPreferencesKey("new_note_page_mode")
    val newRatio = stringPreferencesKey("new_note_ratio")
    val newTemplate = stringPreferencesKey("new_note_template")
    val newBackground = longPreferencesKey("new_note_background")
    val newPdfPageSize = stringPreferencesKey("new_note_pdf_page_size")
    val themeMode = stringPreferencesKey("theme_mode")
}

/**
 * Synchronous copy of the theme choice. DataStore is async, but an activity must know the theme before its first frame
 * (otherwise the window shows the system theme's background for a moment). Written whenever the setting changes.
 */
object ThemeModeCache {
    private const val PREFS = "theme_mode_prefs"
    private const val KEY = "theme_mode"

    fun read(context: Context): ThemeMode {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }

    internal fun write(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
    }
}

/** Maps stored values to [AppSettings]; unknown or missing values fall back to the defaults. */
internal fun Preferences.toAppSettings(): AppSettings {
    val d = AppSettings()
    return AppSettings(
        autoSaveNotes = this[SettingsKeys.autoSave] ?: d.autoSaveNotes,
        showLinks = this[SettingsKeys.showLinks] ?: d.showLinks,
        showWebPreviews = this[SettingsKeys.webPreviews] ?: d.showWebPreviews,
        hideScrollBar = this[SettingsKeys.hideScrollBar] ?: d.hideScrollBar,
        blockBackKey = this[SettingsKeys.blockBack] ?: d.blockBackKey,
        toolbarChangeStyle = this[SettingsKeys.toolbarChangeStyle] ?: d.toolbarChangeStyle,
        toolbarEasyWritingPad = this[SettingsKeys.toolbarEasyPad] ?: d.toolbarEasyWritingPad,
        toolbarZoomLock = this[SettingsKeys.toolbarZoomLock] ?: d.toolbarZoomLock,
        adjustColourInDarkMode = this[SettingsKeys.adjustDark] ?: d.adjustColourInDarkMode,
        themeMode = enumOrDefault(this[SettingsKeys.themeMode], d.themeMode),
        newNote = NewNoteStyle(
            pageMode = enumOrDefault(this[SettingsKeys.newPageMode], d.newNote.pageMode),
            ratio = enumOrDefault(this[SettingsKeys.newRatio], d.newNote.ratio),
            template = enumOrDefault(this[SettingsKeys.newTemplate], d.newNote.template),
            backgroundColor = this[SettingsKeys.newBackground],
            pdfPageSize = enumOrDefault(this[SettingsKeys.newPdfPageSize], d.newNote.pdfPageSize),
        ),
    )
}

internal fun MutablePreferences.writeAppSettings(s: AppSettings) {
    this[SettingsKeys.autoSave] = s.autoSaveNotes
    this[SettingsKeys.showLinks] = s.showLinks
    this[SettingsKeys.webPreviews] = s.showWebPreviews
    this[SettingsKeys.hideScrollBar] = s.hideScrollBar
    this[SettingsKeys.blockBack] = s.blockBackKey
    this[SettingsKeys.toolbarChangeStyle] = s.toolbarChangeStyle
    this[SettingsKeys.toolbarEasyPad] = s.toolbarEasyWritingPad
    this[SettingsKeys.toolbarZoomLock] = s.toolbarZoomLock
    this[SettingsKeys.adjustDark] = s.adjustColourInDarkMode
    this[SettingsKeys.themeMode] = s.themeMode.name
    this[SettingsKeys.newPageMode] = s.newNote.pageMode.name
    this[SettingsKeys.newRatio] = s.newNote.ratio.name
    this[SettingsKeys.newTemplate] = s.newNote.template.name
    this[SettingsKeys.newPdfPageSize] = s.newNote.pdfPageSize.name
    val bg = s.newNote.backgroundColor
    if (bg == null) remove(SettingsKeys.newBackground) else this[SettingsKeys.newBackground] = bg
}

@Singleton
internal class DataStoreAppSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppSettingsRepository {
    override val settings: Flow<AppSettings> = context.appSettingsStore.data.map { it.toAppSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        var theme: ThemeMode? = null
        context.appSettingsStore.edit {
            val updated = transform(it.toAppSettings())
            it.writeAppSettings(updated)
            theme = updated.themeMode
        }
        theme?.let { ThemeModeCache.write(context, it) }
    }
}
