package org.samesame.notes.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.component.AppPillButton
import org.samesame.notes.feature.lock.ChangePasswordFlow
import org.samesame.notes.feature.lock.EnableBiometricsFlow
import org.samesame.notes.feature.lock.LockAuthFlow
import org.samesame.notes.feature.lock.LockAuthMode
import org.samesame.notes.feature.lock.LockViewModel
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.NewNoteStyle
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.PdfPageSize
import org.samesame.notes.core.model.ThemeMode
import org.samesame.notes.core.designsystem.component.AppSwitch
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.designsystem.theme.cappedSp
import org.samesame.notes.core.designsystem.theme.fixed
import org.samesame.notes.core.designsystem.theme.fixedSp

@Serializable
data object SettingsKey : NavKey

private enum class Page { Main, Style, Toolbar, About, Privacy, Licences, Unlock, Export }

@Composable
fun SettingsRoute(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val update = viewModel::update
    var page by rememberSaveable { mutableStateOf(Page.Main) }
    val parent = if (page == Page.Licences) Page.About else Page.Main
    BackHandler(enabled = page != Page.Main) { page = parent }
    val bulk = hiltViewModel<BulkTransferViewModel>()
    val importNotes = rememberImportLauncher(bulk)
    when (page) {
        Page.Main -> MainPage(settings, update, onBack = onBack, onOpen = { page = it }, onImport = importNotes)
        Page.Export -> ExportNotesPage(bulk, onBack = { page = parent })
        Page.Unlock -> UnlockMethodsPage(onBack = { page = parent })
        Page.Style -> StylePage(settings, update, onBack = { page = parent })
        Page.Toolbar -> ToolbarPage(settings, update, onBack = { page = parent })
        Page.About -> AboutPage(onBack = { page = parent }, onLicences = { page = Page.Licences })
        Page.Privacy -> PrivacyPage(onBack = { page = parent })
        Page.Licences -> LicencesPage(onBack = { page = parent })
    }
    BulkTransferOverlay(bulk)
}

// ---- shared building blocks ------------------------------------------------------------------

@Composable
internal fun SettingsScaffold(
    title: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = SameSameTheme.colors
    Column(modifier.fillMaxSize().background(colors.background)) {
        SimpleTopBar(
            title = {
                if (title != null) Text(title, fontSize = cappedSp(18f), fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 1)
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_settings_back), tint = colors.textPrimary)
                }
            },
            actions = actions,
        )
        content()
    }
}

/** Ref #65: 13sp capped #999 section label; 10.5dp above and 1.5dp below (measured against the cards). */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text, fontSize = cappedSp(13f), fontWeight = FontWeight.Medium, color = SameSameTheme.colors.textTertiary,
        modifier = Modifier.padding(start = 24.dp, top = 10.5.dp, bottom = 1.5.dp),
    )
}

/** Full-width rounded group card (radius 26dp). */
@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(SameSameTheme.colors.card)) { content() }
}

@Composable
internal fun RowDivider() {
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(SameSameTheme.colors.divider))
}

/** A settings row: 55dp minimum, 17sp capped title, optional 12.5sp summary and trailing switch (end inset 27dp). */
@Composable
internal fun SettingsRow(
    title: String,
    summary: String? = null,
    checked: Boolean? = null,
    onChecked: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 55.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else if (checked != null && onChecked != null) Modifier.clickable { onChecked(!checked) } else Modifier)
            .padding(start = 24.dp, end = if (checked != null) 27.dp else 24.dp, top = if (summary != null) 15.dp else 0.dp, bottom = if (summary != null) 15.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = if (checked != null) 16.dp else 0.dp), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = cappedSp(17f), lineHeight = cappedSp(22f), color = colors.textPrimary)
            if (summary != null) {
                Text(summary, fontSize = cappedSp(12.5f), lineHeight = cappedSp(16f), color = colors.textTertiary)
            }
        }
        if (checked != null) AppSwitch(checked, null)
    }
}

// ---- main page (#65) --------------------------------------------------------------------------

private val ThemeChoices = listOf(ThemeMode.Light, ThemeMode.Dark, ThemeMode.System)

@Composable
private fun filePasteLabel(mode: org.samesame.notes.core.model.FilePasteMode): String = stringResource(
    when (mode) {
        org.samesame.notes.core.model.FilePasteMode.Ask -> R.string.feature_settings_file_paste_ask
        org.samesame.notes.core.model.FilePasteMode.AlwaysLink -> R.string.feature_settings_file_paste_link
        org.samesame.notes.core.model.FilePasteMode.AlwaysShowInNote -> R.string.feature_settings_file_paste_show
    },
)

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.Light -> R.string.feature_settings_theme_light
        ThemeMode.Dark -> R.string.feature_settings_theme_dark
        ThemeMode.System -> R.string.feature_settings_theme_system
    },
)

@Composable
private fun MainPage(
    settings: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onOpen: (Page) -> Unit,
    onImport: () -> Unit,
) {
    val hasLockPassword by hiltViewModel<LockViewModel>().hasPassword.collectAsStateWithLifecycle(initialValue = false)
    var themeMenu by rememberSaveable { mutableStateOf(false) }
    var filePasteMenu by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.feature_settings_title), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader(stringResource(R.string.feature_settings_general))
            GroupCard {
                Box {
                    SettingsRow(
                        stringResource(R.string.feature_settings_theme),
                        summary = themeLabel(settings.themeMode),
                        onClick = { themeMenu = true },
                    )
                    AppDropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                        ThemeChoices.forEach { mode ->
                            AppMenuItem(
                                themeLabel(mode),
                                { themeMenu = false; update { it.copy(themeMode = mode) } },
                                checked = settings.themeMode == mode,
                            )
                        }
                    }
                }
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_new_note_style), onClick = { onOpen(Page.Style) })
                RowDivider()
                // the reference lists "Note unlock methods" right after "Style of new notes"; ref #65 was captured
                // without a lock password and shows no such row, so it appears once a password exists.
                if (hasLockPassword) {
                    SettingsRow(stringResource(R.string.feature_settings_unlock_methods), onClick = { onOpen(Page.Unlock) })
                    RowDivider()
                }
                // the reference's General card has "Import notes" right before "Auto save notes"; ours opens the system
                // file picker (bulk .sdocx / .ssnote / PDF). "Export notes" (bulk Save as file) is our addition.
                SettingsRow(stringResource(R.string.feature_settings_import_notes), onClick = onImport)
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_export_notes), onClick = { onOpen(Page.Export) })
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_auto_save), checked = settings.autoSaveNotes, onChecked = { v -> update { it.copy(autoSaveNotes = v) } })
            }
            SectionHeader(stringResource(R.string.feature_settings_advanced))
            GroupCard {
                SettingsRow(stringResource(R.string.feature_settings_show_links), checked = settings.showLinks, onChecked = { v -> update { it.copy(showLinks = v) } })
                RowDivider()
                // Files (0.2.0): what pasting a link to a picture of the Files library does.
                Box {
                    SettingsRow(
                        stringResource(R.string.feature_settings_file_paste),
                        summary = filePasteLabel(settings.filePasteMode),
                        onClick = { filePasteMenu = true },
                    )
                    AppDropdownMenu(expanded = filePasteMenu, onDismissRequest = { filePasteMenu = false }, offset = DpOffset(0.dp, 0.dp)) {
                        org.samesame.notes.core.model.FilePasteMode.entries.forEach { mode ->
                            AppMenuItem(
                                filePasteLabel(mode),
                                { filePasteMenu = false; update { it.copy(filePasteMode = mode) } },
                                checked = settings.filePasteMode == mode,
                            )
                        }
                    }
                }
                RowDivider()
                SettingsRow(
                    stringResource(R.string.feature_settings_web_previews), summary = stringResource(R.string.feature_settings_web_previews_summary),
                    checked = settings.showWebPreviews, onChecked = { v -> update { it.copy(showWebPreviews = v) } },
                )
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_hide_scroll_bar), checked = settings.hideScrollBar, onChecked = { v -> update { it.copy(hideScrollBar = v) } })
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_block_back), checked = settings.blockBackKey, onChecked = { v -> update { it.copy(blockBackKey = v) } })
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_toolbar_addons), onClick = { onOpen(Page.Toolbar) })
            }
            SectionHeader(stringResource(R.string.feature_settings_privacy))
            GroupCard { SettingsRow(stringResource(R.string.feature_settings_privacy_notice), onClick = { onOpen(Page.Privacy) }) }
            Spacer(Modifier.height(20.6.dp))
            GroupCard { SettingsRow(stringResource(R.string.feature_settings_about), onClick = { onOpen(Page.About) }) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- note unlock methods (layout settings_lock_preference) -------------------------------------

private enum class UnlockOverlay { None, Create, Change, EnableBiometrics }

@Composable
private fun UnlockMethodsPage(onBack: () -> Unit, viewModel: LockViewModel = hiltViewModel()) {
    val hasPassword by viewModel.hasPassword.collectAsStateWithLifecycle(initialValue = false)
    val biometricsOn by viewModel.biometricsEnabled.collectAsStateWithLifecycle(initialValue = false)
    var overlay by rememberSaveable { mutableStateOf(UnlockOverlay.None) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val colors = SameSameTheme.colors
    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(stringResource(R.string.feature_settings_unlock_methods), onBack) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                SectionHeader(stringResource(R.string.feature_settings_category_password))
                GroupCard {
                    SettingsRow(
                        stringResource(if (hasPassword) R.string.feature_settings_change_password else R.string.feature_settings_create_password),
                        onClick = { overlay = if (hasPassword) UnlockOverlay.Change else UnlockOverlay.Create },
                    )
                }
                // The biometrics category only exists on devices with enrolled strong biometrics, and needs a password.
                if (hasPassword && (viewModel.biometricsAvailable() || biometricsOn)) {
                    SectionHeader(stringResource(R.string.feature_settings_biometrics))
                    Text(
                        stringResource(R.string.feature_settings_biometrics_help), fontSize = cappedSp(13f), lineHeight = cappedSp(18f),
                        color = colors.textTertiary, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
                    )
                    GroupCard {
                        SettingsRow(
                            stringResource(R.string.feature_settings_fingerprints), checked = biometricsOn,
                            onChecked = { on -> if (on) overlay = UnlockOverlay.EnableBiometrics else scope.launch { viewModel.disableBiometrics() } },
                        )
                    }
                }
            }
        }
        when (overlay) {
            UnlockOverlay.None -> Unit
            UnlockOverlay.Create -> LockAuthFlow(
                mode = LockAuthMode.VerifyOrCreate,
                onCancel = { overlay = UnlockOverlay.None },
                onToken = { it.close(); overlay = UnlockOverlay.None },
            )
            UnlockOverlay.Change -> ChangePasswordFlow(onDone = { overlay = UnlockOverlay.None })
            UnlockOverlay.EnableBiometrics -> EnableBiometricsFlow(onDone = { overlay = UnlockOverlay.None })
        }
    }
}

// ---- toolbar add-ons (#68) ------------------------------------------------------------------

@Composable
private fun ToolbarPage(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.feature_settings_toolbar_addons), onBack) {
        Column(Modifier.fillMaxSize()) {
            GroupCard {
                SettingsRow(stringResource(R.string.feature_settings_change_style), stringResource(R.string.feature_settings_change_style_summary), settings.toolbarChangeStyle, { v -> update { it.copy(toolbarChangeStyle = v) } })
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_easy_pad), stringResource(R.string.feature_settings_easy_pad_summary), settings.toolbarEasyWritingPad, { v -> update { it.copy(toolbarEasyWritingPad = v) } })
                RowDivider()
                SettingsRow(stringResource(R.string.feature_settings_zoom_lock), stringResource(R.string.feature_settings_zoom_lock_summary), settings.toolbarZoomLock, { v -> update { it.copy(toolbarZoomLock = v) } })
            }
        }
    }
}

// ---- about (#69) -----------------------------------------------------------------------------

@Composable
private fun AboutPage(onBack: () -> Unit, onLicences: () -> Unit) {
    val colors = SameSameTheme.colors
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    SettingsScaffold(
        title = null, onBack = onBack,
        actions = {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.Info, stringResource(R.string.feature_settings_about_info),
                tint = colors.textPrimary, modifier = Modifier.padding(end = 8.dp).size(24.dp),
            )
        },
    ) {
        Box(Modifier.fillMaxSize().navigationBarsPadding()) {
            Column(Modifier.align(Alignment.TopCenter).padding(top = 58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.feature_settings_app_name), style = SameSameText.LargeTitle.fixed(), color = colors.textPrimary)
                Text(
                    stringResource(R.string.feature_settings_version, version), style = SameSameText.LargeSubtitle.fixed(),
                    fontSize = fixedSp(16f), lineHeight = fixedSp(21f),
                    color = colors.textSecondary, modifier = Modifier.padding(top = 9.dp),
                )
            }
            AppPillButton(
                stringResource(R.string.feature_settings_licences), onLicences,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 37.dp).width(222.dp).height(44.dp),
            )
        }
    }
}

// ---- style of new notes (#67, #70, #71) --------------------------------------------------------

private enum class StyleTab { Style, Template, Colour }

@Composable
private fun StylePage(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(StyleTab.Style) }
    SettingsScaffold(stringResource(R.string.feature_settings_new_note_style), onBack) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp))
            GroupCard(Modifier.padding(top = 0.dp)) {
                SettingsRow(stringResource(R.string.feature_settings_dark_colour), checked = settings.adjustColourInDarkMode, onChecked = { v -> update { it.copy(adjustColourInDarkMode = v) } })
            }
            StyleTabs(tab) { tab = it }
            when (tab) {
                StyleTab.Style -> StyleTabContent(settings.newNote) { f -> update { it.copy(newNote = f(it.newNote)) } }
                StyleTab.Template -> TemplateTabContent(settings.newNote) { f -> update { it.copy(newNote = f(it.newNote)) } }
                StyleTab.Colour -> ColourTabContent(settings.newNote) { f -> update { it.copy(newNote = f(it.newNote)) } }
            }
        }
    }
}

/** Three equal tabs; the selected one is a bold label on a dark 35dp pill. */
@Composable
private fun StyleTabs(selected: StyleTab, onSelect: (StyleTab) -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 31.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(
            StyleTab.Style to R.string.feature_settings_tab_style,
            StyleTab.Template to R.string.feature_settings_tab_template,
            StyleTab.Colour to R.string.feature_settings_tab_colour,
        ).forEach { (t, label) ->
            val on = t == selected
            Box(
                Modifier.weight(1f).height(35.dp).clip(CircleShape)
                    .background(if (on) (if (colors.isDark) Color(0xFF282828) else colors.pill) else Color.Transparent)
                    .clickable { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(label), fontSize = cappedSp(16f), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) colors.textPrimary else colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun StyleTabContent(style: NewNoteStyle, change: ((NewNoteStyle) -> NewNoteStyle) -> Unit) {
    val colors = SameSameTheme.colors
    val infinite = style.pageMode == PageMode.Infinite
    val long = style.ratio == PageRatio.Long
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        SectionHeader(stringResource(R.string.feature_settings_page_style))
        GroupCard {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 39.dp, bottom = 26.dp)) {
                ChoiceColumn(stringResource(R.string.feature_settings_individual_pages), !infinite, { change { it.copy(pageMode = PageMode.Pages) } }, Modifier.weight(1f)) { PageSketch(separated = true) }
                ChoiceColumn(stringResource(R.string.feature_settings_infinite_page), infinite, { change { it.copy(pageMode = PageMode.Infinite) } }, Modifier.weight(1f)) { PageSketch(separated = false) }
            }
        }
        SectionHeader(stringResource(R.string.feature_settings_page_settings))
        GroupCard {
            Text(
                stringResource(R.string.feature_settings_layout), fontSize = cappedSp(17f), color = colors.textPrimary,
                modifier = Modifier.padding(start = 24.dp, top = 21.dp),
            )
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 37.dp, bottom = 26.dp)) {
                ChoiceColumn(
                    stringResource(R.string.feature_settings_layout_normal), !long, { change { it.copy(ratio = PageRatio.A4Portrait) } },
                    Modifier.weight(1f), artSlotHeight = 40.dp, labelGap = 21.dp,
                ) { LayoutSketch(tall = false, active = !long) }
                ChoiceColumn(
                    stringResource(R.string.feature_settings_layout_long), long, { change { it.copy(ratio = PageRatio.Long) } },
                    Modifier.weight(1f), artSlotHeight = 40.dp, labelGap = 21.dp,
                ) { LayoutSketch(tall = true, active = long) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChoiceColumn(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artSlotHeight: Dp = 146.dp,
    labelGap: Dp = 24.dp,
    art: @Composable () -> Unit,
) {
    val colors = SameSameTheme.colors
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(artSlotHeight).fillMaxWidth(), contentAlignment = Alignment.Center) { art() }
        Text(label, fontSize = cappedSp(14f), color = colors.textSecondary, modifier = Modifier.padding(top = labelGap))
        Box(
            Modifier.padding(top = 12.dp).size(21.dp)
                .border(1.5.dp, if (selected) colors.accent else colors.checkOutline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).background(colors.accent, CircleShape))
        }
    }
}

/** Phone-shaped page illustration: outlined page with text lines (and a page-break band when [separated]). */
@Composable
private fun PageSketch(separated: Boolean) {
    val line = SameSameTheme.colors.textPrimary.copy(alpha = 0.9f)
    val outline = SameSameTheme.colors.textSecondary
    Canvas(Modifier.size(width = 80.dp, height = 146.dp)) {
        val r = 8.dp.toPx()
        drawRoundRect(outline, cornerRadius = CornerRadius(r), style = Stroke(1.5.dp.toPx()))
        fun bar(y: Float, w: Float) = drawRoundRect(line, Offset(11.dp.toPx(), y.dp.toPx()), Size(w.dp.toPx(), 2.5.dp.toPx()), CornerRadius(1.dp.toPx()))
        bar(16f, 58f); bar(24f, 58f); bar(32f, 40f); bar(40f, 50f)
        if (separated) {
            drawRect(outline.copy(alpha = 0.8f), Offset(2.dp.toPx(), 62.dp.toPx()), Size(size.width - 4.dp.toPx(), 4.dp.toPx()))
            bar(78f, 58f); bar(86f, 40f)
        } else {
            bar(58f, 58f); bar(72f, 38f)
        }
    }
}

@Composable
private fun LayoutSketch(tall: Boolean, active: Boolean) {
    val c = if (active) SameSameTheme.colors.accent else SameSameTheme.colors.textSecondary
    Box(
        Modifier.size(width = 18.dp, height = if (tall) 36.dp else 28.dp)
            .background(c.copy(alpha = 0.25f), RoundedCornerShape(2.dp)).border(1.5.dp, c, RoundedCornerShape(2.dp)),
    )
}

// ---- template tab (#70) ------------------------------------------------------------------------

@Composable
private fun TemplateTabContent(style: NewNoteStyle, change: ((NewNoteStyle) -> NewNoteStyle) -> Unit) {
    val colors = SameSameTheme.colors
    val selected = TemplateKinds.indexOfFirst { it.template == style.template }
    var images by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize()) {
        GroupCard(Modifier.weight(1f, fill = false)) {
            Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (images) R.string.feature_settings_template_default else R.string.feature_settings_template_pdf_heading),
                    fontSize = cappedSp(17f), color = colors.textPrimary, modifier = Modifier.weight(1f),
                )
                Icon(AppIcons.ExpandLess, null, tint = colors.textPrimary)
            }
            if (images) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 37.dp, vertical = 8.dp)) {
                    (0 until TemplateKinds.size).chunked(3).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(34.7.dp)) {
                            rowItems.forEach { i ->
                                TemplateThumb(TemplateKinds[i], i == selected, { change { it.copy(template = TemplateKinds[i].template) } }, Modifier.weight(1f))
                            }
                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 6.dp)) {
                    PdfPageSizeChoice(
                        stringResource(R.string.feature_settings_template_pdf_original),
                        stringResource(R.string.feature_settings_template_pdf_original_summary),
                        style.pdfPageSize == PdfPageSize.Original,
                    ) { change { it.copy(pdfPageSize = PdfPageSize.Original) } }
                    PdfPageSizeChoice(
                        stringResource(R.string.feature_settings_template_pdf_fit),
                        stringResource(R.string.feature_settings_template_pdf_fit_summary),
                        style.pdfPageSize == PdfPageSize.FitToNote,
                    ) { change { it.copy(pdfPageSize = PdfPageSize.FitToNote) } }
                    Text(
                        stringResource(R.string.feature_settings_template_pdf_note),
                        fontSize = cappedSp(13f), color = colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(true to R.string.feature_settings_template_images, false to R.string.feature_settings_template_pdfs).forEach { (isImages, label) ->
                val on = images == isImages
                Column(Modifier.clickable { images = isImages }.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(label), fontSize = cappedSp(17f), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) colors.textPrimary else colors.textSecondary,
                    )
                    Box(Modifier.padding(top = 2.dp).width(60.dp).height(2.dp).background(if (on) colors.textPrimary else Color.Transparent))
                }
            }
        }
    }
}

/** The thumbnails shown in the Default template list, each mapped to the stored built-in template. */
private enum class TemplateKind(val template: PageTemplate) {
    Blank(PageTemplate.Blank),
    Ruled(PageTemplate.LinesMedium),
    RuledWide(PageTemplate.LinesWide),
    RuledDense(PageTemplate.LinesNarrow),
    GridFine(PageTemplate.GridNarrow),
    Grid(PageTemplate.GridMedium),
    Squares(PageTemplate.GridWide),
    Dots(PageTemplate.DotsMedium),
    DotsFine(PageTemplate.DotsNarrow),
}

private val TemplateKinds = TemplateKind.entries

/** A radio row for the "PDF templates" tab. */
@Composable
private fun PdfPageSizeChoice(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(21.dp)
                .border(1.5.dp, if (selected) colors.accent else colors.checkOutline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).background(colors.accent, CircleShape))
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, fontSize = cappedSp(17f), color = colors.textPrimary)
            Text(summary, fontSize = cappedSp(14f), color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TemplateThumb(kind: TemplateKind, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = SameSameTheme.colors
    val accent = colors.accent
    // The previews are pages: they follow the app theme instead of always being dark pages.
    val page = if (colors.isDark) Color.Black else Color.White
    Canvas(
        modifier.aspectRatio(0.7f).clickable(onClick = onClick)
            .then(if (selected) Modifier.border(2.dp, accent) else Modifier)
            .background(page),
    ) {
        val c = if (colors.isDark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.45f)
        val w = size.width; val h = size.height
        fun hLines(step: Float) { var y = step; while (y < h) { drawLine(c, Offset(0f, y), Offset(w, y), 1f); y += step } }
        fun vLines(step: Float) { var x = step; while (x < w) { drawLine(c, Offset(x, 0f), Offset(x, h), 1f); x += step } }
        fun dots(step: Float) { var y = step; while (y < h) { var x = step; while (x < w) { drawCircle(c, 1.5f, Offset(x, y)); x += step }; y += step } }
        val u = 1.dp.toPx()
        when (kind) {
            TemplateKind.Blank -> Unit
            TemplateKind.Ruled -> hLines(12 * u)
            TemplateKind.RuledWide -> hLines(18 * u)
            TemplateKind.RuledDense -> hLines(8 * u)
            TemplateKind.GridFine -> { hLines(6 * u); vLines(6 * u) }
            TemplateKind.Grid -> { hLines(9 * u); vLines(9 * u) }
            TemplateKind.Squares -> { hLines(18 * u); vLines(18 * u) }
            TemplateKind.Dots -> dots(10 * u)
            TemplateKind.DotsFine -> dots(7 * u)
        }
    }
}

// ---- colour tab (#71) --------------------------------------------------------------------------

private val ColourRows: List<List<Long>> = listOf(
    listOf(0x00000000, 0xFFE6E6E6, 0xFFF4E4D7, 0xFFF5DDDD, 0xFFCBDADD, 0xFF010101),
    listOf(0xFFFFC69E, 0xFFFFEC94, 0xFFC8F0E7, 0xFFC9E0F9, 0xFFE5DCF9, 0xFFFFCACD),
    listOf(0xFFEFC9BC, 0xFFA7C9CC, 0xFFB0B2C8, 0xFF77B68E, 0xFF6491AF, 0xFFCE9192),
)

@Composable
private fun ColourTabContent(style: NewNoteStyle, change: ((NewNoteStyle) -> NewNoteStyle) -> Unit) {
    val current = style.backgroundColor ?: 0L
    val dark = SameSameTheme.colors.isDark
    // The empty swatch is a transparency checkerboard; it follows the app theme like the other previews.
    val checkerA = if (dark) Color(0xFF2A2A2A) else Color(0xFFE6E6E6)
    val checkerB = if (dark) Color(0xFF4A4A4A) else Color(0xFFC8C8C8)
    GroupCard(Modifier.padding(top = 4.dp)) {
        Column(Modifier.padding(horizontal = 49.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(29.7.dp)) {
            ColourRows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { c, argb ->
                        Box(
                            Modifier.weight(1f).height(72.dp).clickable { change { it.copy(backgroundColor = argb.takeIf { c -> c != 0L }) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (argb == 0x00000000L) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val cell = 12.dp.toPx()
                                    var y = 0f; var j = 0
                                    while (y < size.height) {
                                        var x = 0f; var i = 0
                                        while (x < size.width) {
                                            drawRect(if ((i + j) % 2 == 0) checkerA else checkerB, Offset(x, y), Size(cell.coerceAtMost(size.width - x), cell.coerceAtMost(size.height - y)))
                                            x += cell; i++
                                        }
                                        y += cell; j++
                                    }
                                }
                            } else {
                                Box(Modifier.fillMaxSize().background(Color(argb)))
                            }
                            if (argb == current) Icon(AppIcons.Check, null, tint = if (argb == 0x00000000L || argb == 0xFF010101L) Color.White else Color.Black, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}
