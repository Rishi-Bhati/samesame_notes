package org.samesame.notes.feature.composer.panels

import org.samesame.notes.core.designsystem.icon.AppIcons
import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.feature.composer.R

enum class PickerTab { Swatches, Spectrum }

data class ColorPickerModel(
    val tab: PickerTab = PickerTab.Swatches,
    val color: Color = Color(0xFFDADADA),
    /** Up to five user-saved colour slots shown as circles above the buttons. */
    val savedColors: List<Color> = emptyList(),
)

/**
 * Colour picker dialog (`#43`, `#44`): 390 dp wide, 24 dp padding, anchored to the bottom 16 dp above the navigation bar
 * (panel y 567..2235 px). Tab pills (35 dp, 13.7 dp apart, 14 sp, top at 18.7 dp), a 13 x 12 swatch grid (342 x 312 dp, 22.5 dp
 * below the pills) or the spectrum (hue across, saturation down) with a brightness slider, then a preview chip and
 * Hex/Red/Green/Blue fields (columns 83 / 64 / 64 / 64 dp, labels 12 sp #999999, values 14 sp), a dotted rule,
 * five saved slots (42 dp, fill #3B3B3B) + eyedropper and Cancel | Done (20 sp bold, inset 19.8 dp).
 */
@Composable
internal fun ColorPickerDialog(
    model: ColorPickerModel,
    onTab: (PickerTab) -> Unit,
    onColor: (Color) -> Unit,
    onPickFromScreen: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Anchor the dialog window to the bottom of the screen (#43).
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setGravity(Gravity.BOTTOM) }
    PanelCard(modifier.padding(start = 10.7.dp, end = 10.7.dp, bottom = 16.dp), width = 390.dp) {
        Column(Modifier.padding(top = 18.7.dp)) {
            Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(35.4.dp), horizontalArrangement = Arrangement.spacedBy(13.7.dp)) {
                TabPill(stringResource(R.string.feature_composer_picker_swatches), model.tab == PickerTab.Swatches, Modifier.weight(1f)) { onTab(PickerTab.Swatches) }
                TabPill(stringResource(R.string.feature_composer_picker_spectrum), model.tab == PickerTab.Spectrum, Modifier.weight(1f)) { onTab(PickerTab.Spectrum) }
            }
            Spacer(Modifier.height(22.5.dp))
            Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(312.dp)) {
                when (model.tab) {
                    PickerTab.Swatches -> SwatchGridView(model.color, onColor)
                    PickerTab.Spectrum -> SpectrumView(model.color, onColor)
                }
            }
            Spacer(Modifier.height(28.dp))
            ColorFields(model.color, onColor, Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(18.dp))
            DottedDivider(Modifier.padding(horizontal = 22.dp)) // #43: y 1827..1831, x 83..997 px
            Spacer(Modifier.height(18.dp))
            Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(5) { i ->
                    val c = model.savedColors.getOrNull(i)
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(c ?: Color(0xFF3B3B3B))
                            .border(1.dp, Color.Black, CircleShape)
                            .then(if (c != null) Modifier.clickable { onColor(c) } else Modifier),
                    )
                }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), CircleShape)
                        .clickable(role = Role.Button, onClick = onPickFromScreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Eyedropper, stringResource(R.string.feature_composer_picker_pick), Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(21.7.dp))
            DialogActions(onCancel, onDone, Modifier.padding(start = 19.8.dp, end = 19.8.dp, bottom = 10.5.dp))
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val light = if (SameSameTheme.colors.isDark) Color(0xFFE6E6E6) else Color(0xFF2B2B2B)
    Box(
        modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(if (selected) light else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = fixedSp(14.3f), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) (if (SameSameTheme.colors.isDark) Color(0xFF252525) else Color.White)
            else Color(0xFF999999), // #43: unselected tab text #999999, selected pill #E6E6E6
        )
    }
}

@Composable
private fun SwatchGridView(selected: Color, onColor: (Color) -> Unit) {
    val rowsCols = SwatchGrid
    Canvas(
        Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).pointerInput(Unit) {
            detectTapGestures { o ->
                val c = (o.x / size.width * rowsCols.COLUMNS).toInt().coerceIn(0, rowsCols.COLUMNS - 1)
                val r = (o.y / size.height * rowsCols.ROWS).toInt().coerceIn(0, rowsCols.ROWS - 1)
                onColor(rowsCols.colorAt(c, r))
            }
        },
    ) {
        val cw = size.width / rowsCols.COLUMNS; val ch = size.height / rowsCols.ROWS
        for (c in 0 until rowsCols.COLUMNS) for (r in 0 until rowsCols.ROWS) {
            drawRect(rowsCols.colorAt(c, r), Offset(c * cw, r * ch), Size(cw + 0.5f, ch + 0.5f))
        }
    }
}

@Composable
private fun SpectrumView(color: Color, onColor: (Color) -> Unit) {
    var hsv by remember { mutableStateOf(color.toHsv()) }
    LaunchedEffect(color) {
        if (hsvColor(hsv.h, hsv.s, hsv.v) != color) hsv = color.toHsv()
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            val hueBrush = Brush.horizontalGradient(
                listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) },
            )
            Canvas(
                Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).pointerInput(Unit) {
                    fun update(o: Offset) {
                        val h = (o.x / size.width).coerceIn(0f, 1f) * 360f
                        val s = (o.y / size.height).coerceIn(0f, 1f)
                        hsv = hsv.copy(h = h, s = s); onColor(hsvColor(hsv.h, hsv.s, hsv.v))
                    }
                    detectDragGestures(onDragStart = { update(it) }) { change, _ -> update(change.position) }
                }.pointerInput(Unit) {
                    detectTapGestures { o ->
                        val h = (o.x / size.width).coerceIn(0f, 1f) * 360f
                        val s = (o.y / size.height).coerceIn(0f, 1f)
                        hsv = hsv.copy(h = h, s = s); onColor(hsvColor(hsv.h, hsv.s, hsv.v))
                    }
                },
            ) {
                // Top is white (no saturation), bottom is the pure hue.
                drawRect(hueBrush)
                drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent)))
                drawRect(Color.Black.copy(alpha = 1f - hsv.v))
                val x = hsv.h / 360f * size.width; val y = hsv.s * size.height
                drawCircle(Color.White, 12.dp.toPx(), Offset(x, y))
                drawCircle(Color.Black.copy(alpha = 0.25f), 12.dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.feature_composer_picker_saturation), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(
                Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(10.dp)).pointerInput(Unit) {
                    detectDragGestures(onDragStart = {}) { change, _ ->
                        hsv = hsv.copy(v = (change.position.x / size.width).coerceIn(0f, 1f)); onColor(hsvColor(hsv.h, hsv.s, hsv.v))
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { o ->
                        hsv = hsv.copy(v = (o.x / size.width).coerceIn(0f, 1f)); onColor(hsvColor(hsv.h, hsv.s, hsv.v))
                    }
                },
            ) {
                drawRoundRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)), cornerRadius = CornerRadius(10.dp.toPx()))
                drawCircle(Color.White, 7.dp.toPx(), Offset(hsv.v * (size.width - 14.dp.toPx()) + 7.dp.toPx(), size.height / 2))
                drawCircle(Color.Black.copy(alpha = 0.3f), 7.dp.toPx(), Offset(hsv.v * (size.width - 14.dp.toPx()) + 7.dp.toPx(), size.height / 2),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            Text("${(hsv.v * 100).toInt()}%", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(56.dp), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun ColorFields(color: Color, onColor: (Color) -> Unit, modifier: Modifier = Modifier) {
    val (r, g, b) = color.toRgb255()
    // #43: chip 56 x 35.4 dp, 9 dp gap, then columns 83 / 64 / 64 / 64 dp (centres 371, 564, 735, 904 px).
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp, 35.4.dp).clip(RoundedCornerShape(12.dp)).background(color))
        Spacer(Modifier.width(9.dp))
        Field(stringResource(R.string.feature_composer_picker_hex), color.toHex6(), Modifier.width(83.dp), KeyboardType.Ascii) { t -> parseHex6(t)?.let(onColor) }
        Field(stringResource(R.string.feature_composer_picker_red), r.toString(), Modifier.width(64.dp), KeyboardType.Number) { t ->
            t.toIntOrNull()?.takeIf { it in 0..255 }?.let { onColor(Color(it, g, b)) } }
        Field(stringResource(R.string.feature_composer_picker_green), g.toString(), Modifier.width(64.dp), KeyboardType.Number) { t ->
            t.toIntOrNull()?.takeIf { it in 0..255 }?.let { onColor(Color(r, it, b)) } }
        Field(stringResource(R.string.feature_composer_picker_blue), b.toString(), Modifier.width(64.dp), KeyboardType.Number) { t ->
            t.toIntOrNull()?.takeIf { it in 0..255 }?.let { onColor(Color(r, g, it)) } }
    }
}

/** Label 12 sp #999999 over the value 14 sp #FCFCFC; the cap tops are 21.7 dp apart (#43: y 1664 and 1721). */
@Composable
private fun Field(label: String, value: String, modifier: Modifier, keyboard: KeyboardType, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = fixedSp(11.8f), lineHeight = fixedSp(14f), color = Color(0xFF999999))
        BasicTextField(
            value = text, onValueChange = { text = it; onCommit(it) }, singleLine = true,
            textStyle = TextStyle(fontSize = fixedSp(13.8f), lineHeight = fixedSp(16f), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
            cursorBrush = Brush.verticalGradient(listOf(SameSameTheme.colors.accent, SameSameTheme.colors.accent)),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.padding(top = 7.dp).fillMaxWidth(),
        )
    }
}

@ComposerPreviews
@Composable
private fun ColorPickerSwatchesPreview() = PreviewFrame { ColorPickerDialog(ColorPickerModel(), {}, {}, {}, {}, {}) }

@ComposerPreviews
@Composable
private fun ColorPickerSpectrumPreview() = PreviewFrame {
    ColorPickerDialog(ColorPickerModel(tab = PickerTab.Spectrum, color = Color(0xFF3D9BFF)), {}, {}, {}, {}, {})
}
