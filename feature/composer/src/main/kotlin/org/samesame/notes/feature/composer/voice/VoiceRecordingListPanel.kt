package org.samesame.notes.feature.composer.voice

import android.view.Gravity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.feature.composer.R
import java.util.Locale

/** The system's best pattern for the row date: `dd/MM, h:mm a`-like on a 12-hour en-GB device, 24-hour otherwise. */
private fun listDatePattern(use24Hour: Boolean): String =
    android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), VoiceFormat.dateSkeleton(use24Hour))

/**
 * "Recorded audio" list: a header (title, Edit and the more menu, or in select mode the select-all circle with its
 * "All" label, "N selected", Rename, Share, Delete), then one 41 dp row per recording (name left, date and `HH:MM:SS`
 * duration right), 125 dp high at most (332.5 dp on wide screens). A tap plays the row; a long press or Edit starts
 * select mode; in select mode a tap checks the row and Back leaves the mode. The playing row is tracked here from the
 * player, so a position tick does not touch the list.
 */
@Composable
internal fun VoiceRecordingListPanel(
    state: VoiceListState,
    player: PlaybackController,
    replayOn: Boolean,
    onReplayChange: (Boolean) -> Unit,
    onRowTap: (VoiceRecording) -> Unit,
    onRowLongPress: (VoiceRecording) -> Unit,
    onEdit: () -> Unit,
    onSelectAll: () -> Unit,
    onRename: (VoiceRecording, String) -> Unit,
    onShare: (List<VoiceRecording>) -> Unit,
    onDelete: (List<VoiceRecording>) -> Unit,
    onExitSelectMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VoiceColors(SameSameTheme.colors.isDark)
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val listMax = if (wide) 332.5.dp else 125.dp
    val use24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val datePattern = remember(use24Hour) { listDatePattern(use24Hour) }
    val playingFlow = remember(player) { player.state.map { it.objectId }.distinctUntilChanged() }
    @Suppress("StateFlowValueCalledInComposition") // seed only; later values come from the flow
    val playingId by playingFlow.collectAsStateWithLifecycle(initialValue = player.state.value.objectId)
    var renameTarget by remember { mutableStateOf<VoiceRecording?>(null) }
    var deleteAsk by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }

    fun nameOf(r: VoiceRecording): String = r.name.ifEmpty { VoiceFormat.defaultName(state.numberOf(r.id)) }

    if (state.selectMode) BackHandler(onBack = onExitSelectMode)

    Column(modifier.fillMaxWidth().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(41.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.selectMode) {
                val all = stringResource(R.string.feature_composer_voice_list_select_all)
                Column(
                    Modifier.padding(start = 10.dp).clickable(role = Role.Button, onClick = onSelectAll).semantics { contentDescription = all },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (state.allSelected) AppIcons.CheckBadge else AppIcons.RadioOff,
                        contentDescription = null, modifier = Modifier.size(24.dp),
                        tint = if (state.allSelected) c.playingItem else c.icon,
                    )
                    Text(stringResource(R.string.feature_composer_voice_list_all), color = c.icon, fontSize = fixedSp(11f), maxLines = 1)
                }
                Text(
                    stringResource(R.string.feature_composer_voice_list_selected, state.selected.size),
                    color = c.icon, fontSize = fixedSp(16f), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                if (state.selected.size == 1) {
                    IconGlyphButton(
                        AppIcons.Pencil, stringResource(R.string.feature_composer_voice_list_rename), c.icon,
                        Modifier.padding(start = 8.dp),
                        onClick = { renameTarget = state.selectedRecordings.firstOrNull() },
                    )
                }
                if (state.selected.isNotEmpty()) {
                    IconGlyphButton(
                        AppIcons.Share, stringResource(R.string.feature_composer_voice_list_share), c.icon,
                        Modifier.padding(start = 8.dp), onClick = { onShare(state.selectedRecordings) },
                    )
                    IconGlyphButton(
                        AppIcons.Trash, stringResource(R.string.feature_composer_delete), c.icon,
                        Modifier.padding(start = 8.dp, end = 6.dp), onClick = { deleteAsk = true },
                    )
                }
            } else {
                Text(
                    stringResource(R.string.feature_composer_voice_list),
                    color = c.icon, fontSize = fixedSp(18f), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                Text(
                    stringResource(R.string.feature_composer_voice_list_share),
                    color = c.icon, fontSize = fixedSp(15f), fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onEdit).padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Box(Modifier.padding(end = 6.dp)) {
                    IconGlyphButton(AppIcons.More, stringResource(R.string.feature_composer_voice_list_more), c.icon) { moreOpen = true }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }, containerColor = Color(0xFF3A3A3A)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.feature_composer_voice_list_edit)) },
                            onClick = { moreOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(if (replayOn) R.string.feature_composer_voice_replay_off else R.string.feature_composer_voice_replay_on))
                            },
                            onClick = { moreOpen = false; onReplayChange(!replayOn) },
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = listMax)) {
            items(state.recordings, key = { it.id }) { r ->
                val checked = r.id in state.selected
                val playing = r.id == playingId
                val name = nameOf(r)
                val rowDescription = "$name, ${VoiceFormat.clock(r.durationMs)}"
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (checked) c.checkedRow else Color.Transparent)
                        .combinedClickable(role = Role.Button, onClick = { onRowTap(r) }, onLongClick = { onRowLongPress(r) })
                        .semantics { contentDescription = rowDescription; selected = checked },
                ) {
                    Row(Modifier.fillMaxWidth().height(40.5.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.selectMode) {
                            Icon(
                                if (checked) AppIcons.CheckBadge else AppIcons.RadioOff,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 10.dp).size(24.dp),
                                tint = if (checked) c.playingItem else c.icon,
                            )
                        }
                        Text(
                            name,
                            color = if (playing || checked) c.playingItem else c.icon,
                            fontSize = fixedSp(16f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = if (state.selectMode) 14.dp else 12.dp, end = 8.dp),
                        )
                        if (r.createdAtMs > 0L) {
                            Text(
                                VoiceFormat.listDate(r.createdAtMs, datePattern),
                                color = c.sub, fontSize = fixedSp(13f), maxLines = 1,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        }
                        Text(
                            VoiceFormat.listDuration(r.durationMs),
                            color = c.sub, fontSize = fixedSp(13f), maxLines = 1,
                            modifier = Modifier.padding(end = if (wide) 8.dp else 6.dp),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
                }
            }
        }
    }

    renameTarget?.let { target ->
        VoiceRenameDialog(
            initial = nameOf(target),
            onConfirm = { name -> renameTarget = null; onRename(target, name.trim()); onExitSelectMode() },
            onDismiss = { renameTarget = null },
        )
    }
    if (deleteAsk) {
        VoiceDeleteDialog(
            count = state.selected.size,
            onConfirm = { deleteAsk = false; onDelete(state.selectedRecordings) },
            onDismiss = { deleteAsk = false },
        )
    }
}

/** The rename dialog: the name prefilled and fully selected, Cancel | Rename (Rename only for a changed, non-blank name). */
@Composable
private fun VoiceRenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val c = VoiceColors(SameSameTheme.colors.isDark)
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(26.dp),
        title = { Text(stringResource(R.string.feature_composer_voice_rename_title)) },
        text = {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = TextStyle(color = MaterialColor.onSurface(), fontSize = fixedSp(16f)),
                cursorBrush = SolidColor(c.playingItem),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).drawBehind {
                    drawLine(c.playingItem, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height), 1.5.dp.toPx())
                }.padding(bottom = 6.dp),
            )
        },
        confirmButton = {
            TextButton(enabled = VoiceFormat.canRename(initial, field.text), onClick = { onConfirm(field.text) }) {
                Text(stringResource(R.string.feature_composer_voice_list_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.feature_composer_voice_cancel)) }
        },
    )
}

/** Bottom dialog "Delete N item(s)?" with Cancel | Delete (Delete in red). */
@Composable
private fun VoiceDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        (androidx.compose.ui.platform.LocalView.current.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.BOTTOM)
        Surface(shape = RoundedCornerShape(26.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                Text(
                    if (count == 1) stringResource(R.string.feature_composer_voice_delete_one)
                    else stringResource(R.string.feature_composer_voice_delete_many, count),
                    fontSize = fixedSp(17f), fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.feature_composer_voice_cancel)) }
                    TextButton(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.feature_composer_voice_list_delete_confirm), color = Color(0xFFE5484D))
                    }
                }
            }
        }
    }
}

private object MaterialColor {
    @Composable fun onSurface(): Color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
}
