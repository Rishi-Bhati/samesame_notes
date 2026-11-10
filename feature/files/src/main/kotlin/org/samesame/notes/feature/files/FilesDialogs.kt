package org.samesame.notes.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.FolderColors
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileUsage
import org.samesame.notes.core.model.files.LibraryFile

/** A name dialog (rename file, rename folder, create folder). [selectBaseOnly] selects the name without its extension. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Long?) -> Unit,
    selectBaseOnly: Boolean = false,
    showColours: Boolean = false,
) {
    val colors = SameSameTheme.colors
    val end = if (selectBaseOnly) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, end))) }
    var colorIndex by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        kotlinx.coroutines.delay(150)
        name = name.copy(selection = TextRange(0, end))
    }
    AppDialog(
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.feature_files_cancel),
        confirmLabel = confirmLabel,
        confirmEnabled = name.text.isNotBlank(),
        onConfirm = { onConfirm(name.text, if (colorIndex == 0) null else FolderColors[colorIndex].toArgb().toLong() and 0xFFFFFFFFL) },
        title = title,
        atBottom = true,
    ) {
        BasicTextField(
            value = name, onValueChange = { name = it }, singleLine = true,
            textStyle = SameSameText.CardBody.copy(fontSize = 22.sp, color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { inner ->
                Column {
                    Box(Modifier.padding(bottom = 9.dp)) { inner() }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.textPrimary))
                }
            },
        )
        if (showColours) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 19.6.dp, bottom = 2.7.dp),
                maxItemsInEachRow = 6,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(15.9.dp),
            ) {
                FolderColors.forEachIndexed { i, c ->
                    Box(Modifier.size(34.dp).background(c, CircleShape).clickable { colorIndex = i }, contentAlignment = Alignment.Center) {
                        if (i == colorIndex) Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

/** Confirm for Delete (to the Recycle bin) or Delete permanently, with the "Used in N notes" warning. */
@Composable
internal fun DeleteDialog(forever: Boolean, usedInNotes: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = SameSameTheme.colors
    AppDialog(
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.feature_files_cancel),
        confirmLabel = stringResource(R.string.feature_files_action_delete),
        onConfirm = onConfirm,
        title = stringResource(if (forever) R.string.feature_files_delete_forever_title else R.string.feature_files_delete_title),
        atBottom = true,
    ) {
        Text(
            stringResource(if (forever) R.string.feature_files_delete_forever_message else R.string.feature_files_delete_message),
            style = SameSameText.DialogMessage.capped(), color = colors.textPrimary,
        )
        if (usedInNotes > 0) {
            Text(
                pluralStringResource(
                    if (forever) R.plurals.feature_files_delete_forever_used else R.plurals.feature_files_delete_used,
                    usedInNotes, usedInNotes,
                ),
                style = SameSameText.DialogMessage.capped(), color = colors.textSecondary, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
internal fun SimpleConfirmDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.feature_files_cancel),
        confirmLabel = confirmLabel, onConfirm = onConfirm, title = title, atBottom = true,
    ) { Text(message, style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary) }
}

/** "Already in this folder": Keep both / Skip. */
@Composable
internal fun DuplicateDialog(name: String, onKeepBoth: () -> Unit, onSkip: () -> Unit) {
    AppDialog(
        onDismissRequest = onSkip,
        dismissLabel = stringResource(R.string.feature_files_duplicate_skip),
        confirmLabel = stringResource(R.string.feature_files_duplicate_keep_both),
        onConfirm = onKeepBoth,
        title = stringResource(R.string.feature_files_duplicate_title),
        atBottom = true,
    ) {
        Text(stringResource(R.string.feature_files_duplicate_message, name), style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary)
    }
}

/** Copy progress with Cancel. */
@Composable
internal fun ImportProgressDialog(progress: ImportProgress, onCancel: () -> Unit) {
    val colors = SameSameTheme.colors
    AppDialog(
        onDismissRequest = {},
        dismissLabel = stringResource(R.string.feature_files_cancel),
        confirmLabel = stringResource(R.string.feature_files_importing_count, progress.index, progress.total),
        confirmEnabled = false,
        onConfirm = {},
        onDismissClick = onCancel,
        title = stringResource(R.string.feature_files_importing),
        atBottom = true,
    ) {
        Text(progress.name, style = SameSameText.DialogMessage.capped(), color = colors.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        val total = progress.totalBytes
        if (total != null && total > 0) {
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).background(colors.pill, CircleShape)) {
                Box(Modifier.fillMaxWidth((progress.copied.toFloat() / total).coerceIn(0f, 1f)).height(4.dp).background(colors.accent, CircleShape))
            }
        }
    }
}

/** The Info sheet: details, location and the notes that use the file. */
@Composable
internal fun InfoDialog(
    file: LibraryFile,
    locationPath: String,
    usage: List<FileUsage>,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
) {
    val colors = SameSameTheme.colors
    AppDialog(
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.feature_files_close),
        confirmLabel = stringResource(R.string.feature_files_action_copy_link),
        onConfirm = onCopyLink,
        title = stringResource(R.string.feature_files_info_title),
        atBottom = true,
    ) {
        InfoRow(stringResource(R.string.feature_files_info_name), file.displayName)
        InfoRow(stringResource(R.string.feature_files_info_type), file.mimeType)
        InfoRow(stringResource(R.string.feature_files_info_size), FilesFormat.size(file.sizeBytes))
        InfoRow(stringResource(R.string.feature_files_info_added), FilesFormat.date(file.createdAt))
        file.sourceApp?.let { InfoRow(stringResource(R.string.feature_files_info_source), it) }
        InfoRow(stringResource(R.string.feature_files_info_location), locationPath.ifEmpty { stringResource(R.string.feature_files_root_folder) })
        Text(
            if (usage.isEmpty()) stringResource(R.string.feature_files_info_not_used) else stringResource(R.string.feature_files_info_used_in, usage.size),
            style = SameSameText.CardTitle, color = colors.textPrimary, modifier = Modifier.padding(top = 12.dp),
        )
        usage.forEach { u ->
            val title = when {
                u.isLocked -> stringResource(R.string.feature_files_info_locked_note) + (u.noteTitle.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                u.noteTitle.isBlank() -> stringResource(R.string.feature_files_untitled_note)
                else -> u.noteTitle
            }
            Text(
                title + if (u.noteInTrash) " " + stringResource(R.string.feature_files_info_in_bin) else "",
                style = SameSameText.ListBody, color = colors.accent,
                modifier = Modifier.fillMaxWidth().clickable { onOpenNote(u.noteId) }.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val colors = SameSameTheme.colors
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, fontSize = 12.sp, color = colors.textTertiary, fontWeight = FontWeight.Bold)
        Text(value, style = SameSameText.ListBody, color = colors.textPrimary)
    }
}

@Composable
internal fun RowDivider() {
    Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(SameSameTheme.colors.pill))
}
