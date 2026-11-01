package org.samesame.notes.feature.composer.panels

import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R

data class PenPanelModel(
    /** 0..4, order as in `#33`-`#37`. */
    val tipIndex: Int = 0,
    val size: Int = 30,
    val color: Color = Color(0xFF335FFF),
    val recentColors: List<Color> = emptyList(),
    /** Highlighter only: opacity percent (#81: 45). */
    val opacity: Int = 45,
    /** Palette set ids shown after the Recent page ("Select colour sets to show"). */
    val paletteSets: List<Int> = ColorPalettes.DEFAULT_SELECTED,
    /** The current pen is saved as a favourite (filled star). */
    val favourite: Boolean = false,
)

/**
 * Pen settings (`#33`-`#42`, highlighter `#81`): 300 x 358 dp with a 1 dp #454545 stroke. Header 60 dp (preset glyph centred 40 dp
 * from the left, star and close centred 82.9 / 40.2 dp from the right), tip row down to a rule at 154.3 dp, then the size
 * track centred at 221.5 dp (pen) or 193.3 dp + opacity bar centred at 242.5 dp (highlighter), the swatch strip top at
 * 281.5 dp and the page dots centred at 334.6 dp. Highlighter: four marker tips instead of five pen tips.
 */
@Composable
internal fun PenSettingsPanel(
    model: PenPanelModel,
    onTip: (Int) -> Unit,
    onSize: (Int) -> Unit,
    onColor: (Color) -> Unit,
    onCustomColor: () -> Unit,
    onPreset: () -> Unit,
    onFavourite: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showTips: Boolean = true,
    /** Highlighter: four marker tips instead of five pen tips, plus the opacity slider (#81). */
    highlighter: Boolean = false,
    onOpacity: (Int) -> Unit = {},
    onEyedropper: (() -> Unit)? = null,
    onPaletteSettings: (() -> Unit)? = null,
) {
    PanelCard(modifier, stroked = true) {
        Column(Modifier.padding(bottom = 21.2.dp)) {
            PanelHeader(
                title = null, onClose = onClose, modifier = Modifier.padding(top = 4.dp),
                leading = { HeaderIconStart(onPreset) },
                actions = {
                    HeaderIcon(
                        if (model.favourite) AppIcons.StarFilled else AppIcons.Star,
                        stringResource(if (model.favourite) R.string.feature_composer_pen_favourite_remove else R.string.feature_composer_pen_favourite),
                        onFavourite,
                    )
                    Spacer(Modifier.width(2.7.dp))
                },
                endPadding = 20.2.dp,
            )
            if (highlighter) {
                HighlighterTipRow(model.tipIndex, model.color, model.opacity, onTip)
            } else if (showTips) {
                PenTipRow(model.tipIndex, model.color, onTip)
            } else {
                Spacer(Modifier.height(TipRowHeight))
            }
            if (highlighter) {
                Spacer(Modifier.height(13.6.dp))
                SizeSliderRow(model.size, onSize, model.color, Modifier, rowHeight = 49.2.dp)
                OpacityRow(model.opacity, onOpacity, model.color, Modifier, rowHeight = 49.2.dp)
                Spacer(Modifier.height(12.4.dp))
            } else {
                Spacer(Modifier.height(34.4.dp))
                SizeSliderRow(model.size, onSize, model.color, Modifier)
                Spacer(Modifier.height(26.dp))
            }
            Column(Modifier.padding(horizontal = 24.dp)) {
                SwatchPager(model.color, model.recentColors, showRecentPage = true, onColor = onColor, onCustom = onCustomColor, paletteSets = model.paletteSets,
                    onEyedropper = onEyedropper, onPaletteSettings = onPaletteSettings, dotsTopPadding = 21.dp)
            }
        }
    }
}

/** Tip row: header-bottom (60 dp) to the rule at 154.3 dp. */
private val TipRowHeight = 94.3.dp
private val RuleColor @Composable get() = if (SameSameTheme.colors.isDark) Color(0xFF454545) else Color(0xFFC7C7C7)

@Composable
private fun HeaderIconStart(onClick: () -> Unit) {
    // `#33`: glyph centre 40 dp from the panel's left edge (box 40 dp starts at 20 dp; the header pads 24 dp).
    Box(Modifier.offset(x = (-4).dp)) {
        HeaderIcon(AppIcons.PenPreset, stringResource(R.string.feature_composer_pen_preset), onClick)
    }
}

/**
 * Shared tip-row layout (#33, #81): [count] cells on a [pitch] spacing centred in the panel; tips stand 1.9 dp above the rule,
 * the selected tip is 75.5 dp tall and the others 60 dp (scaled to the round-3 reference measurement). The rule is 1 dp, full width.
 */
@Composable
private fun TipRow(
    count: Int,
    pitch: androidx.compose.ui.unit.Dp,
    tipWidth: androidx.compose.ui.unit.Dp,
    selected: Int,
    label: @Composable (Int) -> String,
    onTip: (Int) -> Unit,
    tip: DrawScope.(Int) -> Unit,
    sample: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(TipRowHeight), contentAlignment = Alignment.Center) {
            Row(Modifier.width(pitch * count).height(TipRowHeight)) {
                repeat(count) { i ->
                    val desc = label(i)
                    Box(
                        Modifier.width(pitch).fillMaxHeight()
                            .clickable(role = Role.RadioButton) { onTip(i) }
                            .semantics { contentDescription = desc },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        val h = if (i == selected) 75.5.dp else 60.dp
                        Canvas(Modifier.padding(bottom = 1.9.dp).size(width = tipWidth, height = h)) { tip(i) }
                        if (i == selected) Box(Modifier.align(Alignment.TopCenter).offset(y = (-2).dp)) { sample() }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).androidBg(RuleColor))
    }
}

/** Four marker tips (#81, pitch 57.6 dp): highlighter, straight-line highlighter, marker pen, straight-line marker. */
@Composable
private fun HighlighterTipRow(selected: Int, color: Color, opacity: Int, onTip: (Int) -> Unit) {
    TipRow(
        4, 57.6.dp, 22.dp, selected,
        label = { i ->
            stringResource(when (i) {
                0 -> R.string.feature_composer_highlighter_tip_1
                1 -> R.string.feature_composer_highlighter_tip_2
                2 -> R.string.feature_composer_highlighter_tip_3
                else -> R.string.feature_composer_highlighter_tip_4
            })
        },
        onTip = onTip,
        tip = { drawMarkerTip(it) },
        sample = {
            // #81: a wavy band ~40 x 19 dp starting at the tip centre, drawn at the pen opacity.
            Canvas(Modifier.size(width = 44.dp, height = 28.dp).offset(x = 20.dp, y = 0.dp)) {
                val p = Path().apply {
                    moveTo(0f, size.height * 0.7f)
                    cubicTo(size.width * 0.3f, size.height * 0.1f, size.width * 0.6f, size.height * 0.9f, size.width, size.height * 0.3f)
                }
                drawPath(p, color.copy(alpha = opacity / 100f), style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Butt))
            }
        },
    )
}

/** Own marker illustrations: chisel / round tips, with a ruler band on the straight-line variants. */
internal fun DrawScope.drawMarkerTip(index: Int) {
    val w = size.width; val h = size.height
    val barrel = Color(0xFFC9C9C9); val cap = Color(0xFF444444)
    drawRect(barrel, Offset(w * 0.1f, h * 0.55f), Size(w * 0.8f, h * 0.45f))
    drawRect(Color(0xFFE6E6E6), Offset(w * 0.1f, h * 0.55f), Size(w * 0.3f, h * 0.45f))
    if (index % 2 == 1) for (k in 0..3) drawLine(Color(0xFF777777), Offset(w * 0.15f, h * (0.68f + k * 0.07f)), Offset(w * 0.5f, h * (0.68f + k * 0.07f)), 1.5f)
    val tip = Path()
    if (index < 2) { // chisel
        tip.moveTo(w * 0.35f, h * 0.05f); tip.lineTo(w * 0.7f, h * 0.2f); tip.lineTo(w * 0.9f, h * 0.55f)
        tip.lineTo(w * 0.1f, h * 0.55f); tip.lineTo(w * 0.3f, h * 0.2f); tip.close()
    } else { // round
        tip.moveTo(w * 0.5f, h * 0.05f); tip.cubicTo(w * 0.75f, h * 0.05f, w * 0.7f, h * 0.3f, w * 0.9f, h * 0.55f)
        tip.lineTo(w * 0.1f, h * 0.55f); tip.cubicTo(w * 0.3f, h * 0.3f, w * 0.25f, h * 0.05f, w * 0.5f, h * 0.05f); tip.close()
    }
    drawPath(tip, cap)
}

/** Five tips (#33, pitch 49.3 dp); the selected tip is raised and shows a stroke sample 41 x 22 dp in the pen colour. */
@Composable
private fun PenTipRow(selected: Int, color: Color, onTip: (Int) -> Unit) {
    TipRow(
        5, 49.3.dp, 18.dp, selected,
        label = { i -> stringResource(R.string.feature_composer_pen_tip, i + 1) },
        onTip = onTip,
        tip = { drawPenTip(it) },
        sample = {
            Canvas(Modifier.size(width = 44.dp, height = 24.dp).offset(x = 22.dp, y = 0.dp)) {
                val p = Path().apply {
                    moveTo(0f, size.height * 0.95f)
                    cubicTo(size.width * 0.2f, size.height * 0.1f, size.width * 0.7f, 0f, size.width * 0.75f, size.height * 0.3f)
                    cubicTo(size.width * 0.8f, size.height * 0.6f, size.width * 0.4f, size.height * 0.55f, size.width * 0.9f, size.height * 0.75f)
                }
                drawPath(p, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
            }
        },
    )
}

private fun Modifier.androidBg(c: Color): Modifier = this.then(Modifier.background(c))

/** Own simplified illustrations of five nib types: fountain, flat nib, calligraphy, pencil, brush. */
internal fun DrawScope.drawPenTip(index: Int) {
    val w = size.width; val h = size.height
    val body = Color(0xFF565656); val bodyDark = Color(0xFF3C3C3C)
    val metal = Color(0xFFD9D9D9); val metalLight = Color(0xFFF2F2F2)
    // Barrel
    drawRect(body, Offset(w * 0.1f, h * 0.62f), Size(w * 0.8f, h * 0.38f))
    drawRect(bodyDark, Offset(w * 0.55f, h * 0.62f), Size(w * 0.35f, h * 0.38f))
    drawRect(Color(0xFF8A8A8A), Offset(w * 0.05f, h * 0.58f), Size(w * 0.9f, h * 0.05f))
    val nib = Path()
    when (index) {
        0 -> { // fountain: long pointed nib with eye
            nib.moveTo(w * 0.5f, h * 0.05f); nib.lineTo(w * 0.95f, h * 0.45f); nib.lineTo(w * 0.85f, h * 0.58f)
            nib.lineTo(w * 0.15f, h * 0.58f); nib.lineTo(w * 0.05f, h * 0.45f); nib.close()
            drawPath(nib, metal); drawCircle(Color(0xFF333333), w * 0.05f, Offset(w * 0.5f, h * 0.32f))
        }
        1 -> { // flat nib with slit
            nib.moveTo(w * 0.35f, h * 0.1f); nib.lineTo(w * 0.65f, h * 0.1f); nib.lineTo(w * 0.95f, h * 0.45f)
            nib.lineTo(w * 0.85f, h * 0.58f); nib.lineTo(w * 0.15f, h * 0.58f); nib.lineTo(w * 0.05f, h * 0.45f); nib.close()
            drawPath(nib, metal); drawLine(Color(0xFF333333), Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.4f), 1.5f)
        }
        2 -> { // calligraphy: rounded wedge
            nib.moveTo(w * 0.5f, h * 0.02f); nib.cubicTo(w * 0.9f, h * 0.3f, w * 0.95f, h * 0.45f, w * 0.9f, h * 0.58f)
            nib.lineTo(w * 0.1f, h * 0.58f); nib.cubicTo(w * 0.05f, h * 0.45f, w * 0.1f, h * 0.3f, w * 0.5f, h * 0.02f); nib.close()
            drawPath(nib, metal)
        }
        3 -> { // pencil: wood cone and lead
            nib.moveTo(w * 0.5f, h * 0.02f); nib.lineTo(w * 0.9f, h * 0.58f); nib.lineTo(w * 0.1f, h * 0.58f); nib.close()
            drawPath(nib, Color(0xFFF1D3B0))
            val lead = Path().apply { moveTo(w * 0.5f, h * 0.02f); lineTo(w * 0.62f, h * 0.2f); lineTo(w * 0.38f, h * 0.2f); close() }
            drawPath(lead, Color(0xFF4A4A4A))
        }
        else -> { // brush: soft tapered tuft
            nib.moveTo(w * 0.5f, h * 0.02f); nib.cubicTo(w * 0.85f, h * 0.25f, w * 0.95f, h * 0.4f, w * 0.9f, h * 0.58f)
            nib.lineTo(w * 0.1f, h * 0.58f); nib.cubicTo(w * 0.1f, h * 0.35f, w * 0.3f, h * 0.2f, w * 0.5f, h * 0.02f); nib.close()
            drawPath(nib, metalLight)
        }
    }
}

@ComposerPreviews
@Composable
private fun PenSettingsPanelPreview() = PreviewFrame {
    PenSettingsPanel(PenPanelModel(), {}, {}, {}, {}, {}, {}, {})
}

@ComposerPreviews
@Composable
private fun PenSettingsPanelTip5Preview() = PreviewFrame {
    PenSettingsPanel(PenPanelModel(tipIndex = 4, size = 28, color = Color(0xFFD01C1C)), {}, {}, {}, {}, {}, {}, {})
}

@ComposerPreviews
@Composable
private fun HighlighterSettingsPanelPreview() = PreviewFrame {
    PenSettingsPanel(PenPanelModel(color = Color(0xFFFFE600), size = 50), {}, {}, {}, {}, {}, {}, {}, highlighter = true)
}
