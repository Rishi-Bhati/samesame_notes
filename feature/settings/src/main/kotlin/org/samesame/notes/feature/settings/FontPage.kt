package org.samesame.notes.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.LocalExtraFontScale
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.cappedSp
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.BundledFonts
import org.samesame.notes.core.model.CustomFontFile
import org.samesame.notes.core.model.FontChoice
import org.samesame.notes.core.model.FontKind
import org.samesame.notes.core.model.FontScaling
import org.samesame.notes.core.model.FontSection
import org.samesame.notes.core.model.FontSettings

/** Mime types a font file may report; many pickers only say octet-stream. */
private val FontMimeTypes = arrayOf(
    "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
    "application/vnd.ms-opentype", "application/octet-stream",
)

private fun FontSection.labelRes(): Int = when (this) {
    FontSection.Notes -> R.string.feature_settings_font_section_notes
    FontSection.Editor -> R.string.feature_settings_font_section_editor
    FontSection.Chrome -> R.string.feature_settings_font_section_chrome
    FontSection.Files -> R.string.feature_settings_font_section_files
    FontSection.Drawer -> R.string.feature_settings_font_section_drawer
}

/** Settings > Font size and style: live preview, app-wide and per-section sizes, and the font. */
@Composable
internal fun FontPage(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, viewModel: SettingsViewModel, onBack: () -> Unit) {
    val font = settings.font
    val change: (FontSettings.() -> FontSettings) -> Unit = { f -> update { it.copy(font = it.font.f()) } }
    // While a slider is dragged the preview follows it; the setting is written when the finger lifts.
    var livePercent by remember(font.appPercent) { mutableIntStateOf(font.appPercent) }
    var sectionsOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val busy by viewModel.fontBusy.collectAsStateWithLifecycle()
    val customMissing by produceState(true, font.customFontFileName, font.customFontName) {
        value = withContext(Dispatchers.IO) {
            !java.io.File(java.io.File(context.filesDir, "fonts"), CustomFontFile.storageName(font.customFontFileName)).isFile
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.fontErrors.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    val fallbackName = stringResource(R.string.feature_settings_font_custom_default_name)
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFont(uri, fallbackName)
    }

    SettingsScaffold(stringResource(R.string.feature_settings_font_title), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Spacer(Modifier.height(8.dp))
            GroupCard(Modifier.padding(horizontal = 0.dp)) { PreviewCard(font, livePercent) }

            SectionHeader(stringResource(R.string.feature_settings_font_size))
            GroupCard {
                PercentSlider(
                    title = stringResource(R.string.feature_settings_font_size),
                    percent = font.appPercent,
                    onLive = { livePercent = it },
                    onCommit = { p -> change { copy(appPercent = p) } },
                )
                RowDivider()
                SettingsRow(
                    stringResource(R.string.feature_settings_font_by_section),
                    onClick = { sectionsOpen = !sectionsOpen },
                )
                if (sectionsOpen) {
                    FontSection.entries.forEach { section ->
                        RowDivider()
                        PercentSlider(
                            title = stringResource(section.labelRes()),
                            percent = font.section(section),
                            onLive = {},
                            onCommit = { p -> change { copy(sectionPercent = sectionPercent + (section to p)) } },
                        )
                    }
                    Text(
                        stringResource(R.string.feature_settings_font_section_note),
                        fontSize = cappedSp(12.5f), color = SameSameTheme.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                }
                RowDivider()
                Text(
                    stringResource(R.string.feature_settings_font_reset),
                    fontSize = cappedSp(17f),
                    color = if (font.sizesChanged) SameSameTheme.colors.accent else SameSameTheme.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp)
                        .then(if (font.sizesChanged) Modifier.clickable { change { copy(appPercent = FontScaling.DEFAULT, sectionPercent = emptyMap()) } } else Modifier)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }

            SectionHeader(stringResource(R.string.feature_settings_font_family))
            GroupCard {
                val customName = font.customFontName ?: stringResource(R.string.feature_settings_font_custom_default_name)
                FontChoiceRow(stringResource(R.string.feature_settings_font_system), null, font.choice.kind == FontKind.System) {
                    change { copy(choice = FontChoice()) }
                }
                RowDivider()
                FontChoiceRow(stringResource(R.string.feature_settings_font_atkinson), null, font.choice.bundledId == BundledFonts.ATKINSON) {
                    change { copy(choice = FontChoice(FontKind.Bundled, BundledFonts.ATKINSON)) }
                }
                RowDivider()
                FontChoiceRow(stringResource(R.string.feature_settings_font_plex_serif), null, font.choice.bundledId == BundledFonts.PLEX_SERIF) {
                    change { copy(choice = FontChoice(FontKind.Bundled, BundledFonts.PLEX_SERIF)) }
                }
                if (font.customFontName != null || font.choice.kind == FontKind.Custom) {
                    RowDivider()
                    FontChoiceRow(
                        customName,
                        if (customMissing) stringResource(R.string.feature_settings_font_not_found) else null,
                        font.choice.kind == FontKind.Custom,
                    ) { if (!customMissing) change { copy(choice = FontChoice(FontKind.Custom)) } }
                    RowDivider()
                    SettingsRow(stringResource(R.string.feature_settings_font_remove), enabled = !busy, onClick = viewModel::removeFont)
                }
                RowDivider()
                SettingsRow(
                    stringResource(R.string.feature_settings_font_add),
                    summary = stringResource(R.string.feature_settings_font_add_summary),
                    enabled = !busy,
                    onClick = { pickFont.launch(FontMimeTypes) },
                )
            }
            Text(
                stringResource(R.string.feature_settings_font_note),
                fontSize = cappedSp(12.5f), color = SameSameTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Size that renders as [base] x [percent] on screen whatever scale the page itself currently has. */
@Composable
private fun previewSp(base: Float, percent: Float): TextUnit = with(LocalDensity.current) {
    val currentDp = base.sp.toDp()
    Dp(currentDp.value * percent / 100f / LocalExtraFontScale.current).toSp()
}

/** A notes-list style card and a line of editor text, drawn in the chosen font at the size being set. */
@Composable
private fun PreviewCard(font: FontSettings, livePercent: Int) {
    val colors = SameSameTheme.colors
    val notesPercent = livePercent * font.section(FontSection.Notes) / 100f
    val editorPercent = livePercent * font.section(FontSection.Editor) / 100f
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.feature_settings_font_preview_title), style = SameSameText.CardTitle, fontSize = previewSp(15f, notesPercent), color = colors.textPrimary)
        Text(stringResource(R.string.feature_settings_font_preview_body), style = SameSameText.CardBody, fontSize = previewSp(13f, notesPercent), color = colors.textSecondary)
        Text(stringResource(R.string.feature_settings_font_preview_date), style = SameSameText.CardDate, fontSize = previewSp(13f, notesPercent), color = colors.textTertiary)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.feature_settings_font_preview_editor), fontSize = previewSp(17f, editorPercent), color = colors.textPrimary)
    }
}

/** A labelled slider over [FontScaling.MIN]..[FontScaling.MAX] in [FontScaling.STEP]s. */
@Composable
private fun PercentSlider(title: String, percent: Int, onLive: (Int) -> Unit, onCommit: (Int) -> Unit) {
    var value by remember(percent) { mutableIntStateOf(percent) }
    val steps = (FontScaling.MAX - FontScaling.MIN) / FontScaling.STEP - 1
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = cappedSp(17f), color = SameSameTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.feature_settings_font_size_value, value), fontSize = cappedSp(15f), color = SameSameTheme.colors.textTertiary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { value = FontScaling.clamp(it.toInt()); onLive(value) },
            onValueChangeFinished = { onCommit(value) },
            valueRange = FontScaling.MIN.toFloat()..FontScaling.MAX.toFloat(),
            steps = steps,
        )
    }
}

@Composable
private fun FontChoiceRow(title: String, summary: String?, selected: Boolean, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 55.dp).clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = if (summary != null) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = cappedSp(17f), color = colors.textPrimary, fontWeight = if (selected) FontWeight.Bold else null)
            if (summary != null) Text(summary, fontSize = cappedSp(12.5f), color = colors.textTertiary)
        }
        if (selected) Icon(AppIcons.Check, null, tint = colors.accent, modifier = Modifier.size(24.dp))
    }
}
