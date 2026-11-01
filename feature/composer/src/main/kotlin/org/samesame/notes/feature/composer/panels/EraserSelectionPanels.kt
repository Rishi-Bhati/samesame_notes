package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.feature.composer.R
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.feature.composer.toolbar.RectSelectIcon

enum class EraserMode { Stroke, Area }
enum class SelectionShape { Lasso, Rectangle }

data class EraserPanelModel(val mode: EraserMode = EraserMode.Stroke, val highlighterOnly: Boolean = false, val size: Int = 1)
data class SelectionPanelModel(val shape: SelectionShape = SelectionShape.Lasso, val includePartial: Boolean = true)

/** Radio mark (`#47`): 20.6 dp ring, 1.1 dp stroke (accent selected with an 11.4 dp dot, #696969 otherwise). */
@Composable
private fun RadioMark(selected: Boolean) {
    val accent = SameSameTheme.colors.accent
    Box(
        Modifier.size(20.6.dp).clip(CircleShape)
            .border(1.1.dp, if (selected) accent else Color(0xFF696969), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (selected) Box(Modifier.size(11.4.dp).clip(CircleShape).background(accent)) }
}

/** 42 dp row: ring centred 30 dp from the panel edge, label 16 sp from 56.4 dp (85.3 dp after a 24 dp leading glyph). */
@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, leading: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 19.7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(selected)
        Spacer(Modifier.width(if (leading != null) 13.7.dp else 16.1.dp))
        if (leading != null) { leading(); Spacer(Modifier.width(7.3.dp)) }
        Text(label, fontSize = fixedSp(16.1f), color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 56 dp row: label 16 sp from 25 dp, switch right edge 27 dp from the panel edge. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(56.dp).padding(start = 25.dp, end = 27.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = fixedSp(16.1f), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        PanelSwitch(checked, onChecked)
    }
}

/**
 * Eraser panel (`#47`, `#83`): 300 x 296 dp (354 dp in area mode: a 54.5 dp slider row), 1 dp #454545 stroke. Header 60 dp
 * (18 sp title), radios centred 87.2 / 129.1 dp, switch row centred 182.1 dp, dotted rule at 226.4 dp (inset 21 dp),
 * "Erase all handwriting" 18 sp bold centred 266.4 dp.
 */
@Composable
internal fun EraserPanel(
    model: EraserPanelModel,
    onMode: (EraserMode) -> Unit,
    onHighlighterOnly: (Boolean) -> Unit,
    onSize: (Int) -> Unit,
    onEraseAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    PanelCard(modifier, stroked = true, dimmed = dimmed) {
        Column(Modifier.padding(bottom = 6.dp)) {
            PanelHeader(stringResource(R.string.feature_composer_eraser_title), onClose, Modifier.padding(top = 4.dp), endPadding = 20.2.dp, titleSize = 17.sp) // setting_pen_title_text_size 17sp
            Spacer(Modifier.height(6.2.dp))
            RadioRow(stringResource(R.string.feature_composer_eraser_stroke), model.mode == EraserMode.Stroke, { onMode(EraserMode.Stroke) })
            RadioRow(stringResource(R.string.feature_composer_eraser_area), model.mode == EraserMode.Area, { onMode(EraserMode.Area) })
            if (model.mode == EraserMode.Area) {
                // #83: 1..10 slider under the radios (track centre 179.2 dp), white thumb.
                Spacer(Modifier.height(3.7.dp))
                SizeSliderRow(model.size, onSize, Color(0xFFFCFCFC), max = 10, description = "eraser size", rowHeight = 50.8.dp)
                Spacer(Modifier.height(4.dp))
            } else {
                Spacer(Modifier.height(4.dp))
                Spacer(Modifier.height(0.dp))
            }
            SwitchRow(stringResource(R.string.feature_composer_eraser_highlighter_only), model.highlighterOnly, onHighlighterOnly)
            Spacer(Modifier.height(15.3.dp))
            DottedDivider(Modifier.padding(horizontal = 21.dp))
            Spacer(Modifier.height(15.dp))
            Box(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = onEraseAll), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.feature_composer_eraser_all), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Selection mode panel (`#49`): 300 x 233 dp, same rows as the eraser; lasso / rectangle glyphs centred 66 dp. */
@Composable
internal fun SelectionModePanel(
    model: SelectionPanelModel,
    onShape: (SelectionShape) -> Unit,
    onIncludePartial: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    PanelCard(modifier, stroked = true) {
        Column(Modifier.padding(bottom = 22.7.dp)) {
            PanelHeader(stringResource(R.string.feature_composer_selection_title), onClose, Modifier.padding(top = 4.dp), endPadding = 20.2.dp, titleSize = 17.sp) // setting_pen_title_text_size 17sp
            Spacer(Modifier.height(6.2.dp))
            RadioRow(stringResource(R.string.feature_composer_selection_lasso), model.shape == SelectionShape.Lasso,
                { onShape(SelectionShape.Lasso) }) { androidx.compose.material3.Icon(AppIcons.Lasso, null, Modifier.size(24.dp), tint = faint) }
            RadioRow(stringResource(R.string.feature_composer_selection_rectangle), model.shape == SelectionShape.Rectangle,
                { onShape(SelectionShape.Rectangle) }) { RectSelectIcon(faint, size = 24.dp) }
            Spacer(Modifier.height(4.dp))
            SwitchRow(stringResource(R.string.feature_composer_selection_partial), model.includePartial, onIncludePartial)
        }
    }
}

@ComposerPreviews
@Composable
private fun EraserPanelPreview() = PreviewFrame { EraserPanel(EraserPanelModel(EraserMode.Area), {}, {}, {}, {}, {}) }

@ComposerPreviews
@Composable
private fun SelectionModePanelPreview() = PreviewFrame { SelectionModePanel(SelectionPanelModel(), {}, {}, {}) }
