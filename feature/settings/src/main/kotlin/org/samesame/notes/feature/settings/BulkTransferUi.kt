package org.samesame.notes.feature.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.component.BottomActionBar
import org.samesame.notes.core.designsystem.component.BottomBarItem
import org.samesame.notes.core.designsystem.component.SelectionCircle
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.cappedSp
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.model.Note
import org.samesame.notes.feature.share.SaveAsFileDialog
import org.samesame.notes.feature.share.TransferProgressDialog

/** Settings > Import notes: the system multi-file picker; every picked file is imported (see [BulkTransferViewModel]). */
@Composable
internal fun rememberImportLauncher(viewModel: BulkTransferViewModel): () -> Unit {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }
    // ".sdocx" and ".ssnote" have no registered MIME type (providers report octet-stream or zip), so every file is
    // offered; what isn't a note file is reported as "not supported" in the summary.
    return { picker.launch(arrayOf("*/*")) }
}

/** Progress and result dialogs of a bulk run, shown over whichever Settings page is open. */
@Composable
internal fun BulkTransferOverlay(viewModel: BulkTransferViewModel) {
    when (val s = viewModel.state.collectAsStateWithLifecycle().value) {
        BulkState.Idle -> Unit
        is BulkState.Running -> TransferProgressDialog(
            s.name, s.done, s.total,
            defaultTitle = stringResource(if (s.importing) R.string.feature_settings_importing else R.string.feature_settings_exporting),
        )
        is BulkState.Finished -> ResultDialog(
            stringResource(if (s.importing) R.string.feature_settings_import_notes else R.string.feature_settings_export_notes),
            s.lines, viewModel::dismissResult,
        )
    }
}

@Composable
private fun ResultDialog(title: String, lines: List<SummaryLine>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = SameSameTheme.colors
        Surface(
            Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(horizontal = 10.dp),
            shape = RoundedCornerShape(28.dp), color = colors.dialog,
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 26.5.dp, bottom = 13.5.dp)) {
                Text(title, style = SameSameText.Button, color = colors.textPrimary, modifier = Modifier.padding(bottom = 16.dp))
                lines.forEach { line ->
                    Text(
                        line.text(), style = SameSameText.DialogMessage, color = colors.textPrimary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Box(
                    Modifier.fillMaxWidth().padding(top = 9.dp).height(48.dp).clickable(role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.feature_settings_ok), style = SameSameText.Button, color = colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun SummaryLine.text(): String {
    val res = when (kind) {
        SummaryLine.Kind.Imported -> R.plurals.feature_settings_result_imported
        SummaryLine.Kind.Exported -> R.plurals.feature_settings_result_exported
        SummaryLine.Kind.SkippedLocked -> R.plurals.feature_settings_result_locked
        SummaryLine.Kind.Unsupported -> R.plurals.feature_settings_result_unsupported
        SummaryLine.Kind.Legacy -> R.plurals.feature_settings_result_legacy
        SummaryLine.Kind.Protected -> R.plurals.feature_settings_result_protected
        SummaryLine.Kind.Failed -> R.plurals.feature_settings_result_failed
    }
    return pluralStringResource(res, count, count)
}

/**
 * Settings > Export notes: tick notes (or "All"), then Export asks for the format (the Save as file list) and a
 * folder (system folder picker), and writes one file per note there. Locked notes are listed but can't be ticked.
 */
@Composable
internal fun ExportNotesPage(viewModel: BulkTransferViewModel, onBack: () -> Unit) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectable = remember(notes) { notes.filter { !it.isLocked }.map { it.id } }
    LaunchedEffect(selectable) { viewModel.retain(selectable) }
    var chooseFormat by rememberSaveable { mutableStateOf(false) }
    var format by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val f = format
        format = null
        if (tree != null && f != null) viewModel.export(selection.ordered(selectable), f, tree)
    }
    val count = selection.selected.size
    val colors = SameSameTheme.colors
    SettingsScaffold(
        if (count == 0) stringResource(R.string.feature_settings_export_select) else pluralStringResource(R.plurals.feature_settings_export_selected, count, count),
        onBack,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (notes.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.feature_settings_export_empty), fontSize = cappedSp(17f), color = colors.textTertiary)
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                    GroupCard {
                        ExportRow(
                            title = stringResource(R.string.feature_settings_export_all), checked = selection.allSelected(selectable),
                            enabled = selectable.isNotEmpty(), bold = true,
                        ) { viewModel.toggleAll(selectable) }
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        Modifier.weight(1f, fill = false).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.card),
                    ) {
                        itemsIndexed(notes, key = { _, n -> n.id.value }) { index, note ->
                            if (index > 0) RowDivider()
                            NoteExportRow(note, note.id in selection.selected) { viewModel.toggle(note.id) }
                        }
                    }
                }
            }
            BottomActionBar(
                listOf(BottomBarItem(AppIcons.SaveAlt, stringResource(R.string.feature_settings_export_action), { chooseFormat = true }, count > 0)),
                Modifier.navigationBarsPadding(),
            )
        }
    }
    if (chooseFormat) {
        SaveAsFileDialog(
            onDismiss = { chooseFormat = false },
            onConfirm = { f -> chooseFormat = false; format = f; folderPicker.launch(null) },
        )
    }
}

@Composable
private fun NoteExportRow(note: Note, checked: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val date = remember(note.modifiedAt) {
        DateUtils.formatDateTime(context, note.modifiedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
    }
    ExportRow(
        title = note.title.ifBlank { note.previewText.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
            .ifBlank { stringResource(R.string.feature_settings_export_untitled) },
        summary = if (note.isLocked) stringResource(R.string.feature_settings_export_locked) else date,
        checked = checked, enabled = !note.isLocked, locked = note.isLocked, onClick = onClick,
    )
}

/** A tickable row: selection circle, 17 sp title, optional 12.5 sp summary (the Settings row metrics). */
@Composable
private fun ExportRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    summary: String? = null,
    bold: Boolean = false,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 55.dp)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = if (summary != null) 12.dp else 0.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (locked) Icon(AppIcons.Lock, null, Modifier.size(21.dp), tint = colors.textSecondary) else SelectionCircle(checked)
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = cappedSp(17f), lineHeight = cappedSp(22f), color = colors.textPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
            )
            if (summary != null) Text(summary, fontSize = cappedSp(12.5f), lineHeight = cappedSp(16f), color = colors.textTertiary, maxLines = 1)
        }
    }
}
