package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.feature.composer.R

/** Result of toggling [id] in the chosen palette sets (at most [ColorPalettes.MAX_SELECTED], never empty); order is kept. */
internal fun togglePaletteSet(current: List<Int>, id: Int): List<Int> = when {
    id in current -> if (current.size > 1) current - id else current
    current.size < ColorPalettes.MAX_SELECTED -> current + id
    else -> current
}

/**
 * "Select colour sets to show": every swatch set as a row of its 8 colours with a check; up to four may be chosen, the choice
 * becomes the palette pages after the Recent page. Cancel | Done at the foot.
 */
@Composable
internal fun PaletteSetsDialog(
    selected: List<Int>,
    onCancel: () -> Unit,
    onDone: (List<Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
    androidx.compose.runtime.SideEffect { window?.setGravity(android.view.Gravity.BOTTOM) }
    var chosen by remember { mutableStateOf(ColorPalettes.normalize(selected)) }
    val dark = SameSameTheme.colors.isDark
    PanelCard(modifier.padding(horizontal = 10.7.dp), width = 390.dp, stroked = true) {
        Column {
            PanelHeader(stringResource(R.string.feature_composer_colour_sets_to_show), onClose = null, titleSize = fixedSp(17f))
            LazyColumn(Modifier.fillMaxWidth().height(330.dp).padding(horizontal = 24.dp)) {
                items(ColorPalettes.SWATCH_ORDER) { id ->
                    val on = id in chosen
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Checkbox) { chosen = togglePaletteSet(chosen, id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(6.dp))) {
                            ColorPalettes.colors(id, dark).forEach { Box(Modifier.weight(1f).height(26.dp).background(it)) }
                        }
                        Box(
                            Modifier.padding(start = 12.dp).size(22.dp).clip(CircleShape)
                                .background(if (on) SameSameTheme.colors.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Icon(AppIcons.Check, null, Modifier.size(16.dp), tint = androidx.compose.ui.graphics.Color.White) }
                    }
                }
            }
            DialogActions(onCancel, { onDone(chosen) }, Modifier.padding(horizontal = 19.8.dp))
        }
    }
}
