package org.samesame.notes.feature.composer.menus

import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.fixedSp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.ScrollDirection
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.panels.PanelSwitch

/** Popup fill (#53, #54: #3A3A3A dark) and the outlines / dotted rules inside it. */
private val PopupBorder @Composable get() = if (SameSameTheme.colors.isDark) Color(0xFF5A5A5D) else Color(0xFFD9D9D9)
private val PopupRule @Composable get() = if (SameSameTheme.colors.isDark) Color(0xFF737373) else Color(0xFFBDBDBD)

/** Dropdown/menu card used by the editor menus (`#53`, `#54`, `#60`, `#56`): 24 dp radius, no stroke, #3A3A3A fill. */
@Composable
internal fun MenuSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = SameSameTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = colors.elevated,
        shadowElevation = 8.dp,
        content = content,
    )
}

// ------------------------------------------------------------------------------------------------ Insert (#53)

/**
 * Insert menu (`#53`): 300 x 304.8 dp, 16 dp padding. Two rows of outlined 128 x 47.6 dp tiles (Image, Camera / PDF) 12.4 dp
 * apart, a dotted rule, then three 48 dp rows (Voice recording, Drawing, Text box). Glyphs centre 20.2 dp into a tile
 * (36 dp into the menu for rows); labels 16 dp (`insert_menu_font_size`, fixed) start 40.8 / 56.8 dp in.
 */
@Composable
internal fun InsertMenu(
    onImage: () -> Unit,
    modifier: Modifier = Modifier,
    onCamera: (() -> Unit)? = null,
    onPdf: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    onDrawing: (() -> Unit)? = null,
    onTextBox: (() -> Unit)? = null,
    /** Insert > From Files (Files library picker); the row is shown only when the app provides the picker. */
    onFromFiles: (() -> Unit)? = null,
) {
    MenuSurface(modifier.width(300.dp)) {
        Column(Modifier.padding(top = 16.dp, bottom = 2.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.2.dp)) {
                OutlinedTile(AppIcons.Image, R.string.feature_composer_menu_image, onImage, Modifier.weight(1f), enabled = true)
                OutlinedTile(AppIcons.Camera, R.string.feature_composer_menu_camera, onCamera ?: {}, Modifier.weight(1f), enabled = onCamera != null)
            }
            Spacer(Modifier.height(12.6.dp))
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTile(AppIcons.PdfAdd, R.string.feature_composer_menu_pdf, onPdf ?: {}, Modifier.width(128.dp), enabled = onPdf != null)
            }
            Spacer(Modifier.height(21.dp))
            MenuDottedDivider(Modifier.padding(horizontal = 21.dp))
            Spacer(Modifier.height(11.dp))
            PlainMenuRow(AppIcons.Mic, R.string.feature_composer_menu_voice, enabled = onVoice != null, onClick = onVoice ?: {})
            PlainMenuRow(AppIcons.Palette, R.string.feature_composer_menu_drawing, enabled = onDrawing != null, onClick = onDrawing ?: {})
            PlainMenuRow(AppIcons.TextBox, R.string.feature_composer_menu_textbox, enabled = onTextBox != null, onClick = onTextBox ?: {})
            if (onFromFiles != null) PlainMenuRow(AppIcons.Folder, R.string.feature_composer_files_from_files, enabled = true, onClick = onFromFiles)
        }
    }
}

@Composable
private fun OutlinedTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean,
) {
    val colors = SameSameTheme.colors
    // The reference shows every source in full-strength white; the unavailable ones simply do nothing yet.
    val tint = colors.textPrimary
    Row(
        modifier.height(47.6.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, PopupBorder, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .padding(start = 8.2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(8.6.dp))
        Text(stringResource(label), color = tint, fontSize = fixedSp(16f), maxLines = 1)
    }
}

@Composable
private fun PlainMenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val tint = SameSameTheme.colors.textPrimary
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(enabled = enabled, onClick = onClick, role = Role.Button).padding(start = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.width(20.8.dp))
        Text(stringResource(label), color = tint, fontSize = fixedSp(16f))
    }
}

/** Dotted rule of the popups (#53/#54: #737373, 1.1 dp dots). */
@Composable
internal fun MenuDottedDivider(modifier: Modifier = Modifier) {
    val color = PopupRule
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()), 0f))
    }
}

// ------------------------------------------------------------------------------------------------ Overflow (#54, #60)

enum class OverflowAction {
    PageSorter, PageTemplate, PageView, PageSettings, FullScreen, Tags, SaveAsFile, Print, Favourite, Share, Delete,
}

/**
 * Editor overflow menu (`#54`, `#60`, `#76`): 278.9 dp wide, 16 dp top padding. Outlined 47.6 dp tiles 16.4 dp apart inset 22 dp
 * (Page sorter, Page template, Page settings; pen mode: Page sorter, Page view), a dotted rule, 48 dp text-only rows (Full
 * screen, Tags, Save as file, + Print in pen mode), a second rule and the Favourite / Share / Delete glyphs 81 dp apart.
 */
@Composable
internal fun OverflowMenu(penMode: Boolean, onAction: (OverflowAction) -> Unit, modifier: Modifier = Modifier, favorite: Boolean = false) {
    MenuSurface(modifier.width(278.9.dp)) {
        Column(Modifier.padding(top = 16.dp, bottom = 22.7.dp)) {
            PillMenuRow(AppIcons.PageSorter, R.string.feature_composer_ov_page_sorter) { onAction(OverflowAction.PageSorter) }
            Spacer(Modifier.height(16.4.dp))
            if (penMode) {
                PillMenuRow(AppIcons.PageTemplate, R.string.feature_composer_ov_page_view) { onAction(OverflowAction.PageView) }
            } else {
                PillMenuRow(AppIcons.PageTemplate, R.string.feature_composer_ov_page_template) { onAction(OverflowAction.PageTemplate) }
                Spacer(Modifier.height(16.4.dp))
                PillMenuRow(AppIcons.PageSettings, R.string.feature_composer_ov_page_settings) { onAction(OverflowAction.PageSettings) }
            }
            Spacer(Modifier.height(17.5.dp))
            MenuDottedDivider(Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(9.9.dp))
            PlainActionRow(R.string.feature_composer_ov_full_screen) { onAction(OverflowAction.FullScreen) }
            PlainActionRow(R.string.feature_composer_ov_tags) { onAction(OverflowAction.Tags) }
            PlainActionRow(R.string.feature_composer_ov_save_as_file) { onAction(OverflowAction.SaveAsFile) }
            if (penMode) PlainActionRow(R.string.feature_composer_ov_print) { onAction(OverflowAction.Print) }
            Spacer(Modifier.height(16.3.dp))
            MenuDottedDivider(Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(23.dp))
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                IconAction(if (favorite) AppIcons.StarFilled else AppIcons.Star, R.string.feature_composer_ov_favourite) { onAction(OverflowAction.Favourite) }
                IconAction(AppIcons.Share, R.string.feature_composer_ov_share) { onAction(OverflowAction.Share) }
                IconAction(AppIcons.Trash, R.string.feature_composer_ov_delete) { onAction(OverflowAction.Delete) }
            }
        }
    }
}

@Composable
internal fun PillMenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.padding(horizontal = 22.dp).fillMaxWidth().height(47.6.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, PopupBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick, role = Role.Button).padding(start = 8.2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = colors.textPrimary)
        Spacer(Modifier.width(9.3.dp))
        Text(stringResource(label), color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun PlainActionRow(label: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick, role = Role.Button).padding(start = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), color = SameSameTheme.colors.textPrimary, fontSize = 16.sp)
    }
}

@Composable
internal fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    modifier: Modifier = Modifier.width(81.dp),
    onClick: () -> Unit,
) {
    val desc = stringResource(label)
    Box(modifier.height(48.dp).clickable(onClick = onClick, role = Role.Button)
        .semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(24.dp), tint = SameSameTheme.colors.textPrimary)
    }
}

// ------------------------------------------------------------------------------------------------ Selection pill (#50, #51)

/** Fill and text of the selection context pill and its menu (#50, #51: fill #22262D, text #E2E5EF). */
private val ContextFill @Composable get() = if (SameSameTheme.colors.isDark) Color(0xFF22262D) else Color(0xFFEEF0F6)
private val ContextText @Composable get() = if (SameSameTheme.colors.isDark) Color(0xFFE2E5EF) else Color(0xFF1A1C20)

/**
 * The pill above the ink selection box (`#50`): text actions Cut, Copy, Paste, Delete and a more button. 47.6 dp tall,
 * 16 sp text with 11.6 dp padding either side of each label, 6 dp end padding; 46 dp above the selection box.
 */
@Composable
internal fun SelectionPill(
    canPaste: Boolean,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    moreOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), color = ContextFill, shadowElevation = 6.dp) {
        Row(Modifier.height(47.6.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PillText(R.string.feature_composer_sel_cut, true, onCut)
            PillText(R.string.feature_composer_sel_copy, true, onCopy)
            PillText(R.string.feature_composer_sel_paste, canPaste, onPaste)
            PillText(R.string.feature_composer_sel_delete, true, onDelete)
            val desc = stringResource(R.string.feature_composer_sel_more)
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onMore, role = Role.Button).semantics { contentDescription = desc },
                contentAlignment = Alignment.Center) {
                Icon(AppIcons.More, null, Modifier.size(24.dp), tint = ContextText)
            }
        }
    }
}

@Composable
private fun PillText(label: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.height(47.6.dp).clickable(enabled = enabled, onClick = onClick, role = Role.Button).padding(horizontal = 11.6.dp),
        contentAlignment = Alignment.Center) {
        Text(stringResource(label), fontSize = fixedSp(16.4f), color = if (enabled) ContextText else ContextText.copy(alpha = 0.4f), maxLines = 1)
    }
}

/**
 * Selection ⋮ menu (`#51`): replaces the pill, 160 dp wide, four 47.5 dp rows (Change style, Move forwards, Move backwards,
 * back arrow), text 16 sp 17 dp in, right edge and bottom aligned with the pill.
 */
@Composable
internal fun SelectionMoreMenu(
    onChangeStyle: () -> Unit,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Off with the "Change style" toolbar add-on: the row is left out. */
    showChangeStyle: Boolean = true,
    onStraighten: (() -> Unit)? = null,
) {
    Surface(modifier.width(160.dp), shape = RoundedCornerShape(24.dp), color = ContextFill, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (showChangeStyle) MenuTextRow(R.string.feature_composer_change_style, onChangeStyle)
            onStraighten?.let { MenuTextRow(R.string.feature_composer_straighten, it) }
            MenuTextRow(R.string.feature_composer_sel_move_forward, onForward)
            MenuTextRow(R.string.feature_composer_sel_move_backward, onBackward)
            Box(Modifier.fillMaxWidth().height(47.5.dp).clickable(onClick = onBack, role = Role.Button).padding(start = 11.dp),
                contentAlignment = Alignment.CenterStart) {
                Icon(AppIcons.Back, stringResource(R.string.feature_composer_back), Modifier.size(24.dp), tint = ContextText)
            }
        }
    }
}

@Composable
private fun MenuTextRow(label: Int, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(47.5.dp).clickable(onClick = onClick, role = Role.Button).padding(start = 17.dp), contentAlignment = Alignment.CenterStart) {
        Text(stringResource(label), fontSize = fixedSp(16.4f), color = ContextText, maxLines = 1)
    }
}

/**
 * More menu of a selected object (image, drawing, text box, voice, or objects mixed with ink). Rows follow the reference's
 * context-menu order (its item ids): Crop, Drawing, Change style, Wrap text, Fit to page width / Resize to default, Move
 * forwards / backwards (plus our Bring to front / Send to back), Share, Save image, Play, then the back arrow. Same look
 * as [SelectionMoreMenu].
 */
@Composable
internal fun ObjectMoreMenu(
    showFitWidth: Boolean,
    showWrap: Boolean,
    behind: Boolean,
    onResizeDefault: () -> Unit,
    onFitWidth: () -> Unit,
    onToggleWrap: () -> Unit,
    onForward: () -> Unit,
    onBackward: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onBringToFront: () -> Unit = {},
    onSendToBack: () -> Unit = {},
    showResize: Boolean = true,
    showDrawing: Boolean = false,
    onDrawing: () -> Unit = {},
    actions: ObjectMenuExtras = ObjectMenuExtras(),
) {
    Surface(modifier.width(220.dp), shape = RoundedCornerShape(24.dp), color = ContextFill, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            actions.onCrop?.let { MenuTextRow(R.string.feature_composer_obj_crop, it) }
            actions.onLassoCrop?.let { MenuTextRow(R.string.feature_composer_obj_lasso_crop, it) }
            if (showDrawing) MenuTextRow(R.string.feature_composer_menu_drawing, onDrawing)
            actions.onChangeStyle?.let { MenuTextRow(R.string.feature_composer_change_style, it) }
            actions.onStraighten?.let { MenuTextRow(R.string.feature_composer_straighten, it) }
            actions.onAnchor?.let { MenuTextRow(R.string.feature_composer_obj_anchor_to_text, it) }
            if (showWrap) MenuTextRow(if (behind) R.string.feature_composer_obj_wrap_around else R.string.feature_composer_obj_wrap_behind, onToggleWrap)
            if (showFitWidth) MenuTextRow(R.string.feature_composer_obj_fit_width, onFitWidth)
            if (showResize) MenuTextRow(R.string.feature_composer_obj_resize_default, onResizeDefault)
            MenuTextRow(R.string.feature_composer_sel_move_forward, onForward)
            MenuTextRow(R.string.feature_composer_sel_move_backward, onBackward)
            MenuTextRow(R.string.feature_composer_sel_bring_front, onBringToFront)
            MenuTextRow(R.string.feature_composer_sel_send_back, onSendToBack)
            actions.onEditImage?.let { MenuTextRow(R.string.feature_composer_obj_edit_image, it) }
            actions.onShare?.let { MenuTextRow(R.string.feature_composer_obj_share, it) }
            actions.onSaveImage?.let { MenuTextRow(R.string.feature_composer_obj_save_image, it) }
            actions.onPlay?.let { MenuTextRow(R.string.feature_composer_obj_play, it) }
            Box(Modifier.fillMaxWidth().height(47.5.dp).clickable(onClick = onBack, role = Role.Button).padding(start = 11.dp),
                contentAlignment = Alignment.CenterStart) {
                Icon(AppIcons.Back, stringResource(R.string.feature_composer_back), Modifier.size(24.dp), tint = ContextText)
            }
        }
    }
}

/** The optional rows of [ObjectMoreMenu]; null hides a row. */
internal data class ObjectMenuExtras(
    val onCrop: (() -> Unit)? = null,
    val onLassoCrop: (() -> Unit)? = null,
    val onEditImage: (() -> Unit)? = null,
    val onAnchor: (() -> Unit)? = null,
    val onStraighten: (() -> Unit)? = null,
    val onChangeStyle: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onSaveImage: (() -> Unit)? = null,
    val onPlay: (() -> Unit)? = null,
)

// ------------------------------------------------------------------------------------------------ Page settings (#56)

/** Background strip of the page settings popup (#56 page 1): the default checker, then pastel greys and black. */
private val PageBackgrounds: List<Long?> = listOf(null, 0xFFE6E6E6, 0xFFF4E4D7, 0xFFF5DDDD, 0xFFCBDADD, 0xFF010101)

/**
 * Page settings popup (`#56`): 300 x 311.6 dp. "Scroll direction" (16 sp, spec page_setting_menu_item_title_text_size) over the
 * current direction (13 sp grey), two 32 dp tiles (#494949; the active glyph is accent) 14.9 dp apart, a dotted rule at 90 dp,
 * "Background colour", a strip of six 41.9 x 57.9 dp cells (the selected one raised and checked), three page dots, and the
 * dark-mode switch row.
 */
@Composable
internal fun PageSettingsPopup(
    layout: PageLayout,
    style: NoteStyle,
    onScroll: (ScrollDirection) -> Unit,
    onBackground: (Long?) -> Unit,
    onAdjustDark: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SameSameTheme.colors
    MenuSurface(modifier.width(300.dp)) {
        Column(Modifier.padding(bottom = 13.2.dp)) {
            Row(Modifier.padding(start = 24.8.dp, end = 23.6.dp, top = 21.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.feature_composer_scroll_direction), color = colors.textPrimary, fontSize = 16.sp)
                    Text(
                        stringResource(if (layout.scroll == ScrollDirection.Vertical) R.string.feature_composer_scroll_vertical else R.string.feature_composer_scroll_horizontal),
                        color = Color(0xFF8C8C8C), fontSize = 13.sp,
                    )
                }
                DirectionButton(AppIcons.PageVertical, R.string.feature_composer_scroll_vertical, layout.scroll == ScrollDirection.Vertical) { onScroll(ScrollDirection.Vertical) }
                Spacer(Modifier.width(14.9.dp))
                DirectionButton(AppIcons.PageHorizontal, R.string.feature_composer_scroll_horizontal, layout.scroll == ScrollDirection.Horizontal) { onScroll(ScrollDirection.Horizontal) }
            }
            Spacer(Modifier.height(20.dp))
            MenuDottedDivider(Modifier.padding(horizontal = 21.dp))
            Spacer(Modifier.height(21.8.dp))
            Text(stringResource(R.string.feature_composer_background_colour), color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.padding(start = 24.8.dp))
            Spacer(Modifier.height(22.1.dp))
            BackgroundSwatches(style.backgroundColor, onBackground, Modifier.padding(horizontal = 24.4.dp))
            Spacer(Modifier.height(16.1.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.5.dp, Alignment.CenterHorizontally)) {
                repeat(3) { i ->
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == 0) Color(0xFFFCFCFC) else Color(0xFF6D6D6D)))
                }
            }
            Spacer(Modifier.height(11.2.dp))
            Row(Modifier.fillMaxWidth().padding(start = 24.8.dp, end = 22.5.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.feature_composer_dark_adjust), color = colors.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                PanelSwitch(style.adjustForDarkMode, onAdjustDark)
            }
        }
    }
}

@Composable
private fun DirectionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, active: Boolean, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    val desc = stringResource(label)
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF494949))
        .clickable(onClick = onClick, role = Role.Button).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(24.dp), tint = if (active) colors.accent else Color(0xFFE6E6E6))
    }
}

/** Six 41.9 x 57.9 dp cells; the selected one grows to 49.9 x 69.7 dp with a white check (#56). */
@Composable
private fun BackgroundSwatches(selected: Long?, onPick: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val cellW = 41.9.dp; val cellH = 57.9.dp
    Box(modifier.fillMaxWidth().height(cellH), contentAlignment = Alignment.CenterStart) {
        Row {
            PageBackgrounds.forEach { c ->
                val argb = c
                val sel = (selected == null && c == null) || (c != null && selected != null && (selected and 0xFFFFFFFFL) == c)
                SwatchCell(argb, sel, Modifier.size(cellW, cellH)) { onPick(argb) }
            }
        }
        val idx = PageBackgrounds.indexOfFirst { c -> (selected == null && c == null) || (c != null && selected != null && (selected and 0xFFFFFFFFL) == c) }
        if (idx >= 0) {
            SwatchCell(PageBackgrounds[idx], true, Modifier.offset(x = cellW * idx - 4.dp).size(cellW + 8.dp, cellH + 11.8.dp).shadow(2.dp, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp)), raised = true) { onPick(PageBackgrounds[idx]) }
        }
    }
}

@Composable
private fun SwatchCell(argb: Long?, selected: Boolean, modifier: Modifier, raised: Boolean = false, onClick: () -> Unit) {
    val fill = argb?.let { Color(it) }
    Box(modifier.clickable(onClick = onClick, role = Role.Button).semantics { contentDescription = argb?.let { "#%06X".format(it and 0xFFFFFF) } ?: "Default" },
        contentAlignment = Alignment.Center) {
        if (fill != null) Box(Modifier.fillMaxSize().background(fill))
        else Canvas(Modifier.fillMaxSize()) {
            // #56: black and #4A4A4A checker of 6.9 dp squares
            val cs = 6.9.dp.toPx()
            drawRect(Color(0xFF010101))
            var y = 0f; var r = 0
            while (y < size.height) { var x = 0f; var c = 0
                while (x < size.width) { if ((r + c) % 2 == 0) drawRect(Color(0xFF4A4A4A), Offset(x, y), androidx.compose.ui.geometry.Size(cs, cs)); x += cs; c++ }
                y += cs; r++ }
        }
        if (selected && raised) Icon(AppIcons.Check, null, Modifier.size(24.dp), tint = if (fill != null && fill.luminance() > 0.6f) Color(0xFF333333) else Color.White)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
