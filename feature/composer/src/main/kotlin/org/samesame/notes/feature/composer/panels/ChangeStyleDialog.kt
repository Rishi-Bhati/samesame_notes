package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.samesame.notes.feature.composer.R

data class ChangeStyleModel(
    val size: Int = 30,
    val color: Color = Color(0xFF3D9BFF),
    val recentColors: List<Color> = emptyList(),
    val paletteSets: List<Int> = ColorPalettes.DEFAULT_SELECTED,
)

/**
 * Change style dialog (`#52`): 300 x 250.7 dp, 1 dp #454545 stroke, title only (20 sp bold, no close). Slider track centre 82.1 dp,
 * strip top 126.5 dp, dots centre 179.6 dp, Cancel | Done centred 217 dp (row inset 22.1 dp).
 */
@Composable
internal fun ChangeStyleDialog(
    model: ChangeStyleModel,
    onSize: (Int) -> Unit,
    onColor: (Color) -> Unit,
    onCustomColor: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onEyedropper: (() -> Unit)? = null,
    onPaletteSettings: (() -> Unit)? = null,
) {
    PanelCard(modifier, stroked = true) {
        Column(Modifier.padding(bottom = 5.7.dp)) {
            PanelHeader(stringResource(R.string.feature_composer_change_style), onClose = null, modifier = Modifier.padding(top = 4.dp))
            SizeSliderRow(model.size, onSize, model.color, rowHeight = 44.4.dp)
            Spacer(Modifier.height(20.1.dp))
            Column(Modifier.padding(horizontal = 24.dp)) {
                SwatchPager(model.color, model.recentColors, showRecentPage = true, onColor = onColor, onCustom = onCustomColor, paletteSets = model.paletteSets,
                    onEyedropper = onEyedropper, onPaletteSettings = onPaletteSettings, dotsTopPadding = 21.dp)
            }
            Spacer(Modifier.height(6.9.dp))
            DialogActions(onCancel, onDone, Modifier.padding(horizontal = 22.1.dp))
        }
    }
}

@ComposerPreviews
@Composable
private fun ChangeStyleDialogPreview() = PreviewFrame { ChangeStyleDialog(ChangeStyleModel(), {}, {}, {}, {}, {}) }
