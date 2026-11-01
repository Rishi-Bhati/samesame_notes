package org.samesame.notes.feature.composer.toolbar

import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.icon.TracedIcons
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.panels.ComposerPreviews
import org.samesame.notes.feature.composer.panels.PreviewFrame
import org.samesame.notes.feature.composer.panels.SelectionShape

data class PenToolbarModel(
    val tool: PenModeTool = PenModeTool.Pen,
    /** default fountain-pen colour (`DefaultPens.FountainPen.color`). */
    val penColor: Color = Color(0xFF335FFF),
    /** default highlighter colour (`DefaultHighlighters.Marker4.color`, alpha kept in [highlighterOpacity]). */
    val highlighterColor: Color = Color(0xFFFFB426),
    /** Highlighter ink opacity percent. */
    val highlighterOpacity: Int = 45,
    val selectionShape: SelectionShape = SelectionShape.Lasso,
    val canUndo: Boolean = true,
    val canRedo: Boolean = false,
)

/** Pen toolbar cells are 122 px = 46.55 dp apart (#32: centres 201.5, 323, 444.5, 566, 690.5, 813, 935.5 px). */
private val CellWidth = 46.55.dp

/** First cell starts 53.5 dp from the left (#32: pen centre 201.5 px less half a cell). */
private val CellsStart = 53.5.dp

/**
 * Pen toolbar (`#32`): scrolls horizontally; keyboard toggle then 46.55 dp cells in the order pen, highlighter,
 * eraser, selection, undo, redo, style, easy writing pad. The active tool sits on a 32 dp `#33FFFFFF` circle;
 * pen and highlighter glyphs take their current colour.
 */
@Composable
fun PenModeToolbar(
    model: PenToolbarModel,
    onKeyboard: () -> Unit,
    onTool: (PenModeTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onStyle: () -> Unit,
    onEasyPad: () -> Unit,
    modifier: Modifier = Modifier,
    /** Toolbar add-ons settings: the Change style and Easy writing pad buttons can be switched off. */
    showStyle: Boolean = true,
    showEasyPad: Boolean = true,
    /** The keyboard (text mode) toggle at the left end; the drawing sheet has no text mode. */
    showKeyboard: Boolean = true,
) {
    val c = toolbarColors()
    ToolbarSurface(modifier) {
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            if (showKeyboard) {
                Spacer(Modifier.width(ToggleStart))
                ModeToggle(stringResource(R.string.feature_composer_mode_text), onKeyboard) {
                    Icon(AppIcons.Keyboard, null, Modifier.size(24.dp), tint = c.icon)
                }
                Spacer(Modifier.width(CellsStart - ToggleStart - ToggleSize))
            } else Spacer(Modifier.width(8.dp))
            Cell(Modifier.width(CellWidth)) {
                CustomToolButton(model.tool == PenModeTool.Pen, R.string.feature_composer_tool_pen, { onTool(PenModeTool.Pen) }) {
                    TwoLayerGlyph(TracedIcons.PenBody, TracedIcons.PenFill, it, model.penColor)
                }
            }
            Cell(Modifier.width(CellWidth)) {
                val on = model.tool == PenModeTool.Highlighter
                CustomToolButton(on, R.string.feature_composer_tool_highlighter, { onTool(PenModeTool.Highlighter) }) {
                    // #81/#32: the tip preview is the tool's colour at its ink alpha (highlighter 45%),
                    // unchanged by selection (HwToolbarPen.updateSettingMenu: setColorFilter(colour, SRC_IN)).
                    TwoLayerGlyph(TracedIcons.HighlighterBody, TracedIcons.HighlighterFill, it,
                        model.highlighterColor.copy(alpha = model.highlighterOpacity / 100f))
                }
            }
            Cell(Modifier.width(CellWidth)) {
                CustomToolButton(model.tool == PenModeTool.Eraser, R.string.feature_composer_tool_eraser, { onTool(PenModeTool.Eraser) }) { TwoLayerGlyph(TracedIcons.EraserBody, TracedIcons.EraserFill, it, EraserFillColor) }
            }
            Cell(Modifier.width(CellWidth)) {
                CustomToolButton(model.tool == PenModeTool.Selection, R.string.feature_composer_tool_selection, { onTool(PenModeTool.Selection) }) {
                    if (model.selectionShape == SelectionShape.Lasso) Icon(AppIcons.Lasso, null, Modifier.size(24.dp), tint = it) else RectSelectIcon(it)
                }
            }
            Cell(Modifier.width(CellWidth)) { ToolButton(AppIcons.Undo, R.string.feature_composer_undo, enabled = model.canUndo, onClick = onUndo) }
            Cell(Modifier.width(CellWidth)) { ToolButton(AppIcons.Redo, R.string.feature_composer_redo, enabled = model.canRedo, onClick = onRedo) }
            if (showStyle) Cell(Modifier.width(CellWidth)) {
                CustomToolButton(false, R.string.feature_composer_change_style, onStyle) {
                    TwoLayerGlyph(TracedIcons.ChangeStyleOutline, TracedIcons.ChangeStyleFill, it, StyleFillColor)
                }
            }
            if (showEasyPad) Cell(Modifier.width(CellWidth)) { ToolButton(AppIcons.EasyWritingPad, R.string.feature_composer_easy_pad, onClick = onEasyPad) }
        }
    }
}

private val EraserFillColor = Color(0xFFD9D9D9)
private val StyleFillColor = Color(0xFF3391FF)

/** Two-layer pen-mode glyph: [fill] in the tool colour under the [outline] in the toolbar icon colour. */
@Composable
private fun TwoLayerGlyph(outline: ImageVector, fill: ImageVector, outlineTint: Color, fillTint: Color) {
    Box(Modifier.size(24.dp)) {
        Icon(fill, null, Modifier.size(24.dp), tint = fillTint)
        Icon(outline, null, Modifier.size(24.dp), tint = outlineTint)
    }
}

@Composable
private fun CustomToolButton(active: Boolean, label: Int, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    val desc = stringResource(label)
    val c = toolbarColors()
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (active) c.selected else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) { glyph(c.icon) }
}

@ComposerPreviews
@Composable
private fun PenToolbarPreview() = PreviewFrame { PenModeToolbar(PenToolbarModel(), {}, {}, {}, {}, {}, {}) }

@ComposerPreviews
@Composable
private fun PenToolbarEraserPreview() = PreviewFrame {
    PenModeToolbar(PenToolbarModel(tool = PenModeTool.Eraser, canRedo = true), {}, {}, {}, {}, {}, {})
}
