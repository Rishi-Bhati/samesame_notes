package org.samesame.notes.core.data.repository

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.samesame.notes.core.model.FontChoice
import org.samesame.notes.core.model.FontScaling
import org.samesame.notes.core.model.FontSection
import org.samesame.notes.core.model.FontSettings

/** Storage of [FontSettings] inside the app settings preferences (kept apart from the other keys). */
private object FontKeys {
    val appPercent = intPreferencesKey("font_app_percent")
    val choice = stringPreferencesKey("font_choice")
    val customName = stringPreferencesKey("font_custom_name")
    val customFileName = stringPreferencesKey("font_custom_file_name")
    fun section(s: FontSection) = intPreferencesKey("font_section_${s.name.lowercase()}")
}

/** Missing or out-of-range values read as the defaults (percents are clamped and snapped to the step). */
internal fun Preferences.toFontSettings(): FontSettings = FontSettings(
    appPercent = FontScaling.clamp(this[FontKeys.appPercent] ?: FontScaling.DEFAULT),
    sectionPercent = FontSection.entries.mapNotNull { s -> this[FontKeys.section(s)]?.let { s to FontScaling.clamp(it) } }.toMap(),
    choice = FontChoice.parse(this[FontKeys.choice]),
    customFontName = this[FontKeys.customName],
    customFontFileName = this[FontKeys.customFileName],
)

internal fun MutablePreferences.writeFontSettings(f: FontSettings) {
    this[FontKeys.appPercent] = FontScaling.clamp(f.appPercent)
    this[FontKeys.choice] = f.choice.serialize()
    f.customFontName?.let { this[FontKeys.customName] = it } ?: remove(FontKeys.customName)
    f.customFontFileName?.let { this[FontKeys.customFileName] = it } ?: remove(FontKeys.customFileName)
    FontSection.entries.forEach { s ->
        val p = f.sectionPercent[s]
        if (p == null || FontScaling.clamp(p) == FontScaling.DEFAULT) remove(FontKeys.section(s)) else this[FontKeys.section(s)] = FontScaling.clamp(p)
    }
}
