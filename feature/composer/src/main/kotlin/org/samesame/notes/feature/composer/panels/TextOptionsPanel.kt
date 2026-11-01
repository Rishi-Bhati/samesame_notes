package org.samesame.notes.feature.composer.panels

import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.toolbar.toolbarColors
import org.samesame.notes.feature.composer.R

enum class ParagraphAlign { Left, Center, Right }

data class TextOptionsModel(
    val bullets: Boolean = false,
    val numbering: Boolean = false,
    val align: ParagraphAlign = ParagraphAlign.Left,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val canOutdent: Boolean = false,
)

/**
 * Text options panel (`#28`): 300 x 170 dp; header 60 dp, then two rows of 40 dp pill groups 9.5 dp apart, 20 dp bottom.
 * Groups are inset 20.5 dp and 8.4 dp apart; row 1 is 100 + 152 dp, row 2 is 160 + 92 dp.
 */
@Composable
internal fun TextOptionsPanel(
    model: TextOptionsModel,
    onBullets: () -> Unit,
    onNumbering: () -> Unit,
    onAlign: (ParagraphAlign) -> Unit,
    onBold: () -> Unit,
    onItalic: () -> Unit,
    onUnderline: () -> Unit,
    onStrikethrough: () -> Unit,
    onIndent: () -> Unit,
    onOutdent: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier) {
        Column(Modifier.padding(bottom = 20.dp)) {
            PanelHeader(stringResource(R.string.feature_composer_text_options), onClose, Modifier.padding(top = 4.dp))
            Row(Modifier.padding(horizontal = 20.5.dp), horizontalArrangement = Arrangement.spacedBy(8.4.dp)) {
                IconGroup(100.dp) {
                    GroupButton(AppIcons.Bullets, R.string.feature_composer_bullets, model.bullets, true, onBullets)
                    GroupButton(AppIcons.Numbering, R.string.feature_composer_numbering, model.numbering, true, onNumbering)
                }
                IconGroup(152.dp) {
                    GroupButton(AppIcons.AlignLeft, R.string.feature_composer_align_left, model.align == ParagraphAlign.Left, true) { onAlign(ParagraphAlign.Left) }
                    GroupButton(AppIcons.AlignCenter, R.string.feature_composer_align_center, model.align == ParagraphAlign.Center, true) { onAlign(ParagraphAlign.Center) }
                    GroupButton(AppIcons.AlignRight, R.string.feature_composer_align_right, model.align == ParagraphAlign.Right, true) { onAlign(ParagraphAlign.Right) }
                }
            }
            Row(Modifier.padding(start = 20.5.dp, end = 20.5.dp, top = 9.5.dp), horizontalArrangement = Arrangement.spacedBy(8.4.dp)) {
                IconGroup(160.dp) {
                    GroupButton(AppIcons.Bold, R.string.feature_composer_bold, model.bold, true, onBold)
                    GroupButton(AppIcons.Italic, R.string.feature_composer_italic, model.italic, true, onItalic)
                    GroupButton(AppIcons.Underline, R.string.feature_composer_underline, model.underline, true, onUnderline)
                    GroupButton(AppIcons.Strikethrough, R.string.feature_composer_strikethrough, model.strikethrough, true, onStrikethrough)
                }
                IconGroup(92.dp) {
                    GroupButton(AppIcons.Indent, R.string.feature_composer_indent, false, true, onIndent)
                    GroupButton(AppIcons.Outdent, R.string.feature_composer_outdent, false, model.canOutdent, onOutdent)
                }
            }
        }
    }
}

/** Pill group (#28: fill #3B3B3B dark, #F4F4F4 light); glyph centres sit 25.3 dp from each end, evenly spaced between. */
@Composable
private fun IconGroup(width: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Row(
        Modifier.width(width).height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (SameSameTheme.colors.isDark) androidx.compose.ui.graphics.Color(0xFF3B3B3B) else androidx.compose.ui.graphics.Color(0xFFF4F4F4))
            .padding(horizontal = 10.3.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 30 dp circular button; the selected one gets a 20% white circle behind it (`#28` align left: #626262 on #3B3B3B). */
@Composable
private fun GroupButton(icon: ImageVector, label: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dark = SameSameTheme.colors.isDark
    Box(
        Modifier.size(30.dp).clip(CircleShape)
            .background(if (selected) (if (dark) androidx.compose.ui.graphics.Color(0x33FFFFFF) else androidx.compose.ui.graphics.Color(0x14000000)) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, stringResource(label), Modifier.size(24.dp), tint = if (enabled) toolbarColors().icon else toolbarColors().iconDisabled)
    }
}

@ComposerPreviews
@Composable
private fun TextOptionsPanelPreview() = PreviewFrame {
    TextOptionsPanel(TextOptionsModel(), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
}
