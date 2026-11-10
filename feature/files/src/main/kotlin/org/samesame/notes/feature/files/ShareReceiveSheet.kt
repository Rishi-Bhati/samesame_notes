package org.samesame.notes.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.files.LibraryFile

/**
 * The "Save to SameSame" sheet of the share and Open-with receiver: editable names, Files or New note, the target
 * folder, progress with Cancel, and "Saved N files to Files" with Open.
 */
@Composable
fun ShareReceiveSheet(
    viewModel: ShareReceiveViewModel,
    state: ReceiveUiState,
    onFinish: () -> Unit,
    onOpen: (LibraryFile) -> Unit,
) {
    val colors = SameSameTheme.colors
    state.duplicates.firstOrNull()?.let { dup ->
        DuplicateDialog(dup.existing.displayName, { viewModel.resolveDuplicate(true) }, { viewModel.resolveDuplicate(false) })
        return
    }
    when (val phase = state.phase) {
        is ReceivePhase.Saving -> AppDialog(
            onDismissRequest = {},
            dismissLabel = stringResource(R.string.feature_files_cancel),
            confirmLabel = phase.progress?.let { stringResource(R.string.feature_files_importing_count, it.index, it.total) } ?: "",
            confirmEnabled = false, onConfirm = {},
            onDismissClick = { viewModel.cancel(); onFinish() },
            title = stringResource(R.string.feature_files_importing), atBottom = true,
        ) {
            Text(phase.progress?.name.orEmpty(), style = SameSameText.DialogMessage.capped(), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val p = phase.progress
            val total = p?.totalBytes
            if (p != null && total != null && total > 0) {
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).background(colors.pill, CircleShape)) {
                    Box(Modifier.fillMaxWidth((p.copied.toFloat() / total).coerceIn(0f, 1f)).height(4.dp).background(colors.accent, CircleShape))
                }
            }
        }
        is ReceivePhase.Done -> AppDialog(
            onDismissRequest = onFinish,
            dismissLabel = stringResource(R.string.feature_files_receive_done),
            confirmLabel = stringResource(R.string.feature_files_receive_saved_open),
            confirmEnabled = phase.opened != null,
            onConfirm = { phase.opened?.let(onOpen) },
            onDismissClick = onFinish,
            title = stringResource(R.string.feature_files_receive_title), atBottom = true,
        ) {
            Text(
                pluralStringResource(R.plurals.feature_files_receive_saved, phase.saved, phase.saved),
                style = SameSameText.DialogMessage.capped(), color = colors.textPrimary,
            )
            phase.failures.forEach { f ->
                Text(
                    stringResource(
                        when (f.reason) {
                            ImportFailureReason.TooLarge -> R.string.feature_files_err_too_large
                            ImportFailureReason.NoSpace -> R.string.feature_files_err_no_space
                            ImportFailureReason.Unreadable -> R.string.feature_files_err_unreadable
                        },
                        f.name,
                    ),
                    style = SameSameText.ListBody, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        ReceivePhase.Choosing -> ChoosingSheet(viewModel, state, onFinish)
    }
}

@Composable
private fun ChoosingSheet(viewModel: ShareReceiveViewModel, state: ReceiveUiState, onFinish: () -> Unit) {
    val colors = SameSameTheme.colors
    var folderMenu by remember { mutableStateOf(false) }
    AppDialog(
        onDismissRequest = onFinish,
        dismissLabel = stringResource(R.string.feature_files_cancel),
        confirmLabel = stringResource(R.string.feature_files_receive_save),
        confirmEnabled = state.items.isNotEmpty() && state.items.all { it.name.isNotBlank() },
        onConfirm = viewModel::save,
        title = stringResource(R.string.feature_files_receive_title),
        atBottom = true,
    ) {
        state.items.forEachIndexed { i, item ->
            BasicTextField(
                value = item.name, onValueChange = { viewModel.rename(i, it) }, singleLine = true,
                textStyle = SameSameText.ListBody.copy(color = colors.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                decorationBox = { inner ->
                    Column {
                        Box(Modifier.padding(bottom = 4.dp)) { inner() }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    }
                },
            )
        }
        if (state.canNewNote) {
            DestinationRow(stringResource(R.string.feature_files_receive_files), !state.toNewNote) { viewModel.setNewNote(false) }
            DestinationRow(stringResource(R.string.feature_files_receive_new_note), state.toNewNote) { viewModel.setNewNote(true) }
        }
        if (state.hasFilesOnly && !state.toNewNote) {
            Text(
                stringResource(R.string.feature_files_receive_cannot_note),
                style = SameSameText.ListBody, color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (!state.toNewNote) {
            Box(Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { folderMenu = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Folder, null, tint = colors.iconMuted, modifier = Modifier.size(22.dp))
                    Text(
                        FilesFormat.path(state.folderId, state.folders).ifEmpty { stringResource(R.string.feature_files_root_folder) },
                        style = SameSameText.ListBody, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    Icon(AppIcons.ExpandMore, null, tint = colors.iconMuted, modifier = Modifier.size(20.dp))
                }
                AppDropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    AppMenuItem(stringResource(R.string.feature_files_root_folder), { folderMenu = false; viewModel.setFolder(null) }, checked = state.folderId == null)
                    state.folders.forEach { f ->
                        AppMenuItem(
                            "  ".repeat(f.depth.coerceAtMost(5)) + f.name,
                            { folderMenu = false; viewModel.setFolder(f.id) },
                            checked = state.folderId == f.id,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (selected) AppIcons.RadioOn else AppIcons.RadioOff, null, tint = if (selected) colors.accent else colors.iconMuted, modifier = Modifier.size(22.dp))
        Text(label, style = SameSameText.DialogMessage.capped(), color = colors.textPrimary, modifier = Modifier.padding(start = 12.dp))
    }
}
