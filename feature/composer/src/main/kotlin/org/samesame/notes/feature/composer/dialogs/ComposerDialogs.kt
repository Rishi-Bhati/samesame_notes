package org.samesame.notes.feature.composer.dialogs

import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.panels.DialogActions
import org.samesame.notes.feature.composer.panels.PanelCard
import org.samesame.notes.feature.composer.panels.PanelHeader

/**
 * Tags sheet (`#58`): a full-width card anchored 16 dp above the bottom, 259 dp tall, #252525, 28.6 dp radius. Title "Add tags" and a
 * bold "Save" (19.5 dp text, 24.4 dp / 42 dp insets, cap tops 27 dp down), and a pill field ("Create new tag", #919191, 2 dp #EAEAEA
 * outline, 41.9 dp tall, insets 24 / 22 dp, 21 dp above the card's bottom). Note: tag persistence is a later work package.
 */
@Composable
internal fun TagsDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect { window?.setGravity(android.view.Gravity.BOTTOM) }
        androidx.compose.material3.Surface(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).height(259.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.6.dp),
            color = androidx.compose.ui.graphics.Color(0xFF252525),
        ) {
            Column {
                Row(Modifier.padding(start = 24.4.dp, end = 42.dp, top = 21.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.feature_composer_tags_hint), fontSize = fixedSp(19.5f), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.feature_composer_tags_save), fontSize = fixedSp(19.5f), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clickable(onClick = onDismiss, role = Role.Button))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.padding(start = 24.dp, end = 22.dp, bottom = 21.dp).fillMaxWidth().height(41.9.dp)
                        .border(2.dp, androidx.compose.ui.graphics.Color(0xFFEAEAEA), androidx.compose.foundation.shape.CircleShape).padding(start = 39.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text(stringResource(R.string.feature_composer_tags_create), color = androidx.compose.ui.graphics.Color(0xFF919191), fontSize = fixedSp(16.1f))
                    BasicTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = fixedSp(16.1f), color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
