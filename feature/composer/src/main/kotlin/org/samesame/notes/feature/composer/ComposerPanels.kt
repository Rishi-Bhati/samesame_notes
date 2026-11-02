package org.samesame.notes.feature.composer

import org.samesame.notes.core.designsystem.theme.fixedSp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.data.repository.FavoriteAddResult
import org.samesame.notes.core.data.repository.FavoritePen
import org.samesame.notes.core.data.repository.FavoritePens
import org.samesame.notes.core.designsystem.motion.MotionPopup
import org.samesame.notes.core.designsystem.motion.PopupMotion
import org.samesame.notes.feature.composer.panels.EyedropperOverlay
import org.samesame.notes.feature.composer.panels.spuitCommit
import org.samesame.notes.feature.composer.panels.spuitLiveTarget
import org.samesame.notes.feature.composer.panels.FavoritePenPanel
import org.samesame.notes.feature.composer.toolbar.PenModeTool
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.ui.RichTextEditorState
import org.samesame.notes.feature.composer.panels.ChangeStyleDialog
import org.samesame.notes.feature.composer.panels.ChangeStyleModel
import org.samesame.notes.feature.composer.panels.ColorPanel
import org.samesame.notes.feature.composer.panels.ComposerColors
import org.samesame.notes.feature.composer.panels.ColorPickerDialog
import org.samesame.notes.feature.composer.panels.ColorPickerModel
import org.samesame.notes.feature.composer.panels.EraserPanel
import org.samesame.notes.feature.composer.panels.EraserPanelModel
import org.samesame.notes.feature.composer.panels.FontSizeHoldState
import org.samesame.notes.feature.composer.panels.FontSizeMenu
import org.samesame.notes.feature.composer.panels.FontSizePick
import org.samesame.notes.feature.composer.panels.TextSizeCatalog
import org.samesame.notes.feature.composer.panels.PanelCard
import org.samesame.notes.feature.composer.panels.ParagraphAlign
import org.samesame.notes.feature.composer.panels.PenPanelModel
import org.samesame.notes.feature.composer.panels.PenSettingsPanel
import org.samesame.notes.feature.composer.panels.PickerTab
import org.samesame.notes.feature.composer.panels.SelectionModePanel
import org.samesame.notes.feature.composer.panels.SelectionPanelModel
import org.samesame.notes.feature.composer.panels.TextOptionsModel
import org.samesame.notes.feature.composer.panels.TextOptionsPanel
import org.samesame.notes.feature.composer.session.EditorSession
import org.samesame.notes.feature.composer.session.ToolsState
import org.samesame.notes.feature.composer.toolbar.ColorTarget
import org.samesame.notes.feature.composer.toolbar.Panel
import org.samesame.notes.feature.composer.toolbar.ToolbarIntent
import org.samesame.notes.feature.composer.toolbar.ToolbarState

internal fun Color.toStoredArgb(): Long = toArgb().toLong() and 0xFFFFFFFFL
internal fun Long.toColor(): Color = Color(toInt())

/**
 * Floating panels (WP-I `panels/`) above the toolbar, opened through `session.dispatch(ToolbarIntent)`.
 * [activeState] is read for its [RichTextEditorState.revision] so this layer (and not the whole editor)
 * recomposes when the text selection or styles change.
 */
@Composable
internal fun ComposerPanelLayer(
    session: EditorSession,
    body: BodyText,
    activeState: RichTextEditorState,
    tb: ToolbarState,
    tools: ToolsState,
    eraseAllOpen: Boolean,
    onEraseAll: () -> Unit,
    onEraseAllPage: (all: Boolean) -> Unit,
    currentPage: Int,
    pageCount: Int,
    fontSizeHold: FontSizeHoldState = remember { FontSizeHoldState() },
    onFontSizePick: (FontSizePick) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val prefs = tools.prefs
    var setsOpen by rememberSaveable { mutableStateOf(false) }
    var spuit by remember { mutableStateOf<SpuitRequest?>(null) }
    var favouriteMode by remember { mutableStateOf(false) }
    // Set while a favourite of the other tool kind is applied: the tool switch passes through "no panel" and must not leave favourite mode.
    var keepFavouriteMode by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(tb.panel) {
        val pen = tb.panel == Panel.PenSettings || tb.panel == Panel.HighlighterSettings
        if (pen) keepFavouriteMode = false
        else if (!keepFavouriteMode) favouriteMode = false
    }
    // No panel open: reading the text revision here would recompose this layer (and query the text styles) on every
    // keystroke for nothing. The dialog and eyedropper states can outlive the panel itself.
    // The panel that was open last stays composed while its exit animation plays (pen-style panels only).
    var heldPanel by remember { mutableStateOf<Panel?>(null) }
    if (tb.panel != null) {
        val animated = tb.panel == Panel.PenSettings || tb.panel == Panel.HighlighterSettings ||
            tb.panel == Panel.Eraser || tb.panel == Panel.SelectionMode
        val next = if (animated) tb.panel else null
        if (heldPanel != next) heldPanel = next
    }
    val shownPanel = tb.panel ?: heldPanel
    val panelOpen = tb.panel != null
    if (shownPanel == null && !setsOpen && spuit == null) return
    @Suppress("UNUSED_VARIABLE") val bodyRevision = activeState.revision
    fun close() { session.dispatch(ToolbarIntent.ClosePanel) }
    val cs = body.currentCharStyle()
    val ps = body.currentParagraphStyle()
    val recent = prefs.recentColors.map { it.toColor() }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun toast(res: Int) = android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
    fun currentPen(hl: Boolean) = if (hl) FavoritePen(true, tip = prefs.highlighterTip.coerceIn(0, 3), level = prefs.highlighterLevel,
        color = prefs.highlighterColor, opacity = prefs.highlighterOpacity)
    else FavoritePen(false, penType = prefs.penType, level = prefs.penLevel, color = prefs.penColor)
    fun addFavourite(pen: FavoritePen) {
        val (next, result) = FavoritePens.add(prefs.favoritePens, pen)
        when (result) {
            FavoriteAddResult.Added -> session.toolController.updatePrefs { it.copy(favoritePens = next) }
            FavoriteAddResult.AlreadySaved -> toast(R.string.feature_composer_favourite_already_saved)
            FavoriteAddResult.ListFull -> toast(R.string.feature_composer_favourite_full)
        }
    }

    fun style(transform: (org.samesame.notes.core.model.richtext.CharStyle) -> org.samesame.notes.core.model.richtext.CharStyle) {
        session.breakTypingBurst()
        body.applyCharStyle(transform)
    }

    Box(modifier.fillMaxSize()) {
        val bottom = Modifier.align(Alignment.BottomCenter).padding(start = 55.6.dp, end = 55.6.dp, bottom = 8.dp) // #28: panel x 146..935 px, bottom 21 px (8 dp) above the toolbar
        // #33, #47, #49: pen, eraser and selection panels end 13.5 dp above the toolbar (text panels 8 dp).
        val penBottom = Modifier.align(Alignment.BottomCenter).padding(start = 55.6.dp, end = 55.6.dp, bottom = 13.5.dp)
        if (spuit == null) when (val p = shownPanel) {
            Panel.TextOptions -> Box(bottom) {
                TextOptionsPanel(
                    model = TextOptionsModel(
                        bullets = ps.list == ListKind.Bullet,
                        numbering = ps.list == ListKind.Numbered,
                        align = when (ps.align) {
                            TextAlign.Left -> ParagraphAlign.Left
                            TextAlign.Center -> ParagraphAlign.Center
                            TextAlign.Right -> ParagraphAlign.Right
                        },
                        bold = cs.bold, italic = cs.italic, underline = cs.underline, strikethrough = cs.strikethrough,
                        canOutdent = ps.indent > 0,
                    ),
                    onBullets = { session.breakTypingBurst(); body.toggleList(ListKind.Bullet) },
                    onNumbering = { session.breakTypingBurst(); body.toggleList(ListKind.Numbered) },
                    onAlign = { a ->
                        session.breakTypingBurst()
                        body.setAlign(when (a) {
                            ParagraphAlign.Left -> TextAlign.Left
                            ParagraphAlign.Center -> TextAlign.Center
                            ParagraphAlign.Right -> TextAlign.Right
                        })
                    },
                    onBold = { style { it.copy(bold = !cs.bold) } },
                    onItalic = { style { it.copy(italic = !cs.italic) } },
                    onUnderline = { style { it.copy(underline = !cs.underline) } },
                    onStrikethrough = { style { it.copy(strikethrough = !cs.strikethrough) } },
                    onIndent = { session.breakTypingBurst(); body.indent() },
                    onOutdent = { session.breakTypingBurst(); body.outdent() },
                    onClose = ::close,
                )
            }
            Panel.FontColor -> Box(bottom) {
                ColorPanel(
                    title = stringResource(R.string.feature_composer_font_colour),
                    selected = cs.color?.toColor(),
                    onColor = { c -> style { it.copy(color = c.toStoredArgb()) }; session.toolController.addRecentColor(c.toStoredArgb()) },
                    onCustomColor = { session.dispatch(ToolbarIntent.OpenColorPicker(ColorTarget.FontColor)) },
                    onClose = ::close,
                )
            }
            Panel.Highlight -> Box(bottom) {
                ColorPanel(
                    title = stringResource(R.string.feature_composer_font_background),
                    background = true,
                    selected = cs.highlight?.toColor() ?: ComposerColors.highlightStrip[0],
                    onColor = { c ->
                        if (c == ComposerColors.highlightStrip[0]) style { it.copy(highlight = null) }
                        else { style { it.copy(highlight = c.toStoredArgb()) }; session.toolController.addRecentColor(c.toStoredArgb()) }
                    },
                    onCustomColor = { session.dispatch(ToolbarIntent.OpenColorPicker(ColorTarget.Highlight)) },
                    onClose = ::close,
                )
            }
            Panel.FontSize -> {
                // #31: right edge 56.4 dp from the screen edge, bottom 4.6 dp below the toolbar top (it overlaps the toolbar).
                FontSizeMenu(
                    sizes = TextSizeCatalog.menuSizes(cs.fontSize),
                    selectedSize = TextSizeCatalog.selectedSize(cs.fontSize),
                    selectedHeading = TextSizeCatalog.headingFor(cs.fontSize, cs.bold),
                    hold = fontSizeHold,
                    onPick = onFontSizePick,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.4.dp).offset(y = 4.6.dp),
                )
            }
            // #33: the pen panel ends 13.5 dp above the toolbar (the text panels 8 dp).
            Panel.PenSettings, Panel.HighlighterSettings -> MotionPopup(panelOpen, PopupMotion.PenPanel, penBottom, onExited = { heldPanel = null }) {
                val hl = p == Panel.HighlighterSettings
                val now = currentPen(hl)
                if (favouriteMode) {
                    FavoritePenPanel(
                        pens = prefs.favoritePens,
                        selectedIndex = prefs.favoritePens.indexOf(now),
                        canAdd = prefs.favoritePens.size < FavoritePens.MAX,
                        onApply = { i ->
                            val fav = prefs.favoritePens[i]
                            if (prefs.favoritePens.indexOf(now) == i) { favouriteMode = false; return@FavoritePenPanel } // selected again: back to the pen panel
                            session.toolController.updatePrefs {
                                if (fav.highlighter) it.copy(highlighterTip = fav.tip, highlighterLevel = fav.level, highlighterColor = fav.color, highlighterOpacity = fav.opacity)
                                else it.copy(penType = fav.penType, penLevel = fav.level, penColor = fav.color)
                            }
                            if (fav.highlighter != hl) {
                                // The tool changes with the favourite; keep the favourites panel open on the other tool.
                                keepFavouriteMode = true
                                session.dispatch(ToolbarIntent.TapTool(if (fav.highlighter) PenModeTool.Highlighter else PenModeTool.Pen))
                                session.dispatch(ToolbarIntent.TapTool(if (fav.highlighter) PenModeTool.Highlighter else PenModeTool.Pen))
                            }
                        },
                        onAdd = { addFavourite(now) },
                        onRemoveMany = { gone -> session.toolController.updatePrefs { it.copy(favoritePens = it.favoritePens.filterIndexed { i, _ -> i !in gone }) } },
                        onBackToPen = { favouriteMode = false },
                        onClose = ::close,
                    )
                    return@MotionPopup
                }
                val tipIndex = if (hl) prefs.highlighterTip.coerceIn(0, 3)
                else PenCatalog.pens.indexOfFirst { it.type == prefs.penType }.coerceAtLeast(0)
                PenSettingsPanel(
                    model = PenPanelModel(
                        tipIndex = tipIndex,
                        size = if (hl) prefs.highlighterLevel else prefs.penLevel,
                        color = (if (hl) prefs.highlighterColor else prefs.penColor).toColor(),
                        recentColors = recent,
                        opacity = prefs.highlighterOpacity,
                        paletteSets = prefs.paletteSets,
                        favourite = now in prefs.favoritePens,
                    ),
                    onTip = { i ->
                        session.toolController.updatePrefs { if (hl) it.copy(highlighterTip = i) else it.copy(penType = PenCatalog.pens[i].type) }
                    },
                    onOpacity = { o -> session.toolController.updatePrefs { it.copy(highlighterOpacity = o.coerceIn(1, 100)) } },
                    onSize = { s ->
                        session.toolController.updatePrefs {
                            if (hl) it.copy(highlighterLevel = s.coerceIn(1, 100)) else it.copy(penLevel = s.coerceIn(1, 100))
                        }
                    },
                    onColor = { c ->
                        val argb = c.toStoredArgb()
                        session.toolController.updatePrefs { if (hl) it.copy(highlighterColor = argb) else it.copy(penColor = argb) }
                        session.toolController.addRecentColor(argb)
                    },
                    onCustomColor = {
                        session.dispatch(ToolbarIntent.OpenColorPicker(if (hl) ColorTarget.Highlighter else ColorTarget.Pen))
                    },
                    onPaletteSettings = { setsOpen = true },
                    onEyedropper = { spuit = SpuitRequest(if (hl) ColorTarget.Highlighter else ColorTarget.Pen, (if (hl) prefs.highlighterColor else prefs.penColor).toColor()) },
                    onPreset = { favouriteMode = true },
                    onFavourite = {
                        if (now in prefs.favoritePens) session.toolController.updatePrefs { it.copy(favoritePens = it.favoritePens - now) }
                        else addFavourite(now)
                    },
                    onClose = ::close,
                    highlighter = hl,
                )
            }
            Panel.Eraser -> MotionPopup(panelOpen, PopupMotion.PenPanel, penBottom, onExited = { heldPanel = null }) {
                EraserPanel(
                    dimmed = eraseAllOpen,
                    model = EraserPanelModel(tools.eraserMode, prefs.eraserHighlighterOnly, prefs.eraserSize),
                    onMode = session.toolController::setEraserMode,
                    onHighlighterOnly = { v -> session.toolController.updatePrefs { it.copy(eraserHighlighterOnly = v) } },
                    onSize = { v -> session.toolController.updatePrefs { it.copy(eraserSize = v.coerceIn(1, 10)) } },
                    onEraseAll = onEraseAll,
                    onClose = ::close,
                )
                if (eraseAllOpen) {
                    // #110: popup 193 x 102 dp, 53.3 dp in from the panel's left edge and 8 dp above its bottom.
                    EraseAllPopup(
                        currentPage + 1, pageCount, onEraseAllPage,
                        Modifier.align(Alignment.BottomStart).padding(start = 53.3.dp, bottom = 8.dp),
                    )
                }
            }
            Panel.SelectionMode -> MotionPopup(panelOpen, PopupMotion.PenPanel, penBottom, onExited = { heldPanel = null }) {
                SelectionModePanel(
                    model = SelectionPanelModel(tools.selectionShape, prefs.selectionIncludePartial),
                    onShape = session.toolController::setSelectionShape,
                    onIncludePartial = { v -> session.toolController.updatePrefs { it.copy(selectionIncludePartial = v) } },
                    onClose = ::close,
                )
            }
            Panel.ChangeStyle -> ChangeStylePanelHost(session, tools, penBottom, ::close)
            is Panel.ColorPicker, null -> Unit
        }

    }

    if (setsOpen) {
        Dialog(onDismissRequest = { setsOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            org.samesame.notes.feature.composer.panels.PaletteSetsDialog(
                selected = prefs.paletteSets,
                onCancel = { setsOpen = false },
                onDone = { ids -> session.toolController.updatePrefs { it.copy(paletteSets = ids) }; setsOpen = false },
            )
        }
    }

    if (spuit == null) (tb.panel as? Panel.ColorPicker)?.let { picker ->
        ColorPickerHost(session, body, picker, cs.color, cs.highlight, prefs.penColor, prefs.highlighterColor, recent,
            onEyedropper = { from -> spuit = SpuitRequest(picker.target, from) })
    }

    spuit?.let { req ->
        EyedropperOverlay(
            initial = req.initial,
            onSample = { c -> if (spuitLiveTarget(req.target)) setPickedColor(session, body, req.target, c.toStoredArgb()) },
            onApply = { c -> finishSpuit(session, body, req, c); spuit = null },
            onClose = { c -> finishSpuit(session, body, req, c); spuit = null },
        )
    }
}

/** "All / Current page(n/N)" popup of "Erase all handwriting" (#110, #111): 18 sp rows of 51 dp, text 27.4 dp in. */
@Composable
private fun EraseAllPopup(page: Int, pages: Int, onChoose: (all: Boolean) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Surface(
        modifier = modifier.width(193.2.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        color = org.samesame.notes.core.designsystem.theme.SameSameTheme.colors.dialog,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF454545)),
        shadowElevation = 6.dp,
    ) {
        Column {
            EraseRow(stringResource(R.string.feature_composer_erase_all)) { onChoose(true) }
            EraseRow(stringResource(R.string.feature_composer_erase_current_page, page, pages)) { onChoose(false) }
        }
    }
}

@Composable
private fun EraseRow(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(51.dp).clickable(onClick = onClick).padding(start = 27.4.dp), contentAlignment = Alignment.CenterStart) {
        Text(label, fontSize = fixedSp(18.3f), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * Change style (`#52`): size level + colour applied to the selected strokes (family kept). The custom
 * swatch opens a colour picker dialog scoped to this panel; Done bakes one history command.
 */
@Composable
private fun ChangeStylePanelHost(session: EditorSession, tools: ToolsState, modifier: Modifier, onClose: () -> Unit) {
    val prefs = tools.prefs
    val selection by session.selectionState.collectAsStateWithLifecycle()
    var size by remember { mutableStateOf(30) }
    var color by remember { mutableStateOf(prefs.penColor.toColor()) }
    var customOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        ChangeStyleDialog(
            model = ChangeStyleModel(size, color, prefs.recentColors.map { it.toColor() }, prefs.paletteSets),
            onSize = { size = it },
            onColor = { color = it },
            onCustomColor = { customOpen = true },
            onCancel = onClose,
            onDone = {
                if (!selection.isEmpty) {
                    session.restyleSelection(colorArgb = color.toStoredArgb().toInt(), sizePu = PenCatalog.specFor(prefs.penType).widthPu(size))
                    session.toolController.addRecentColor(color.toStoredArgb())
                }
                onClose()
            },
        )
        if (customOpen) {
            Dialog(onDismissRequest = { customOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                ColorPickerDialog(
                    model = ColorPickerModel(PickerTab.Swatches, color, prefs.recentColors.map { it.toColor() }.take(5)),
                    onTab = {},
                    onColor = { color = it },
                    onPickFromScreen = {},
                    onCancel = { customOpen = false },
                    onDone = { customOpen = false },
                )
            }
        }
    }
}

@Composable
private fun ColorPickerHost(
    session: EditorSession,
    body: BodyText,
    picker: Panel.ColorPicker,
    fontColor: Long?,
    highlight: Long?,
    penColor: Long,
    highlighterColor: Long,
    recent: List<Color>,
    onEyedropper: (Color) -> Unit,
) {
    val initial = when (picker.target) {
        ColorTarget.FontColor -> fontColor?.toColor() ?: Color(0xFFDADADA)
        ColorTarget.Highlight -> highlight?.toColor() ?: Color(0xFFFFE600)
        ColorTarget.Pen -> Color(0xFFDADADA) // #43: the picker opens on #DADADA
        ColorTarget.Highlighter -> Color(0xFFDADADA)
        ColorTarget.ChangeStyle -> Color(0xFF3D9BFF)
    }
    var tab by remember(picker.target) { mutableStateOf(PickerTab.Swatches) }
    var color by remember(picker.target) { mutableStateOf(initial) }
    fun cancel() { session.dispatch(ToolbarIntent.CloseColorPicker) }
    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ColorPickerDialog(
            model = ColorPickerModel(tab, color, recent.take(5)),
            onTab = { tab = it },
            onColor = { color = it },
            onPickFromScreen = { onEyedropper(color); cancel() },
            onCancel = ::cancel,
            onDone = {
                applyPickedColor(session, body, picker.target, color.toStoredArgb())
                cancel()
            },
        )
    }
}


/** Eyedropper mode request: which colour it edits and the colour its widget starts on. */
private data class SpuitRequest(val target: ColorTarget, val initial: Color)

/** Applies a colour chosen in the picker to [target] and records it in the recent colours. */
private fun applyPickedColor(session: EditorSession, body: BodyText, target: ColorTarget, argb: Long) {
    setPickedColor(session, body, target, argb)
    session.toolController.addRecentColor(argb)
}

/** Sets [argb] on [target] without touching the recent colours. */
private fun setPickedColor(session: EditorSession, body: BodyText, target: ColorTarget, argb: Long) {
    when (target) {
        ColorTarget.FontColor -> { session.breakTypingBurst(); body.applyCharStyle { it.copy(color = argb) } }
        ColorTarget.Highlight -> { session.breakTypingBurst(); body.applyCharStyle { it.copy(highlight = argb) } }
        ColorTarget.Pen -> session.toolController.updatePrefs { it.copy(penColor = argb) }
        ColorTarget.Highlighter -> session.toolController.updatePrefs { it.copy(highlighterColor = argb) }
        ColorTarget.ChangeStyle -> Unit // change style uses its own picker (ChangeStylePanelHost)
    }
}

/** Leaving the eyedropper with [c]: applies it and records it as recent when it changed (see [spuitCommit]). */
private fun finishSpuit(session: EditorSession, body: BodyText, req: SpuitRequest, c: Color) {
    val argb = c.toStoredArgb()
    val commit = spuitCommit(req.target, req.initial.toStoredArgb(), argb)
    if (commit.setTarget) setPickedColor(session, body, req.target, argb)
    if (commit.addRecent) session.toolController.addRecentColor(argb)
}
