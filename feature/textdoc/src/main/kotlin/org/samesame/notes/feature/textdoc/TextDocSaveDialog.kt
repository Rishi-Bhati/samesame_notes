package org.samesame.notes.feature.textdoc

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.model.saveas.SaveAsOptions
import org.samesame.notes.core.model.saveas.SaveOption
import org.samesame.notes.core.model.textdoc.TextDocumentCodec
import org.samesame.notes.feature.share.CreateTypedDocument

/**
 * [TextDocSaveHost] for the editor's state: the document is copied from [uri] until it is edited for the first time, so a
 * file over the editor's size cap is still copied whole.
 */
@Composable
internal fun TextDocSaveSupport(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: TextDocUiState,
    displayName: String?,
    mimeType: String?,
    uri: android.net.Uri?,
    onSaveInPlace: () -> Unit,
    onOpenNote: ((noteId: String, markdown: Boolean) -> Unit)?,
) {
    var edited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isModified) { if (state.isModified) edited = true }
    val current by rememberUpdatedState(state)
    TextDocSaveHost(
        visible = visible,
        onDismiss = onDismiss,
        writable = !state.isReadOnly,
        inLibrary = false,
        markdown = state.isMarkdown,
        source = {
            val s = current
            TextDocSaveSource(
                text = s.text,
                displayName = s.title ?: displayName,
                mimeType = mimeType,
                markdown = s.isMarkdown,
                modified = edited || s.isModified,
                sourceUri = uri,
                bytes = { TextDocumentCodec.roundTripBytes(s.text, s.encoding, s.lineEnding, s.hasBom) },
            )
        },
        onSaveInPlace = onSaveInPlace,
        onOpenNote = onOpenNote,
    )
}

/**
 * The Save options of a document opened from another app ([TextDocRoute]'s Save button): Save changes (when the file may
 * be written), Save as note, Save to Files, Save a copy, and PDF / HTML / Word. [source] is read when an option runs.
 * [onOpenNote] opens the note "Save as note" made (markdown = it is a Markdown note).
 */
@Composable
internal fun TextDocSaveHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    writable: Boolean,
    inLibrary: Boolean,
    markdown: Boolean,
    source: () -> TextDocSaveSource,
    onSaveInPlace: () -> Unit,
    onOpenNote: ((noteId: String, markdown: Boolean) -> Unit)?,
    viewModel: TextDocSaveViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val working by viewModel.working.collectAsStateWithLifecycle()
    val currentSource by rememberUpdatedState(source)
    val openNote by rememberUpdatedState(onOpenNote)
    // The option waiting for the file picker (survives the picker recreating this screen).
    var pending by rememberSaveable { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(CreateTypedDocument()) { target ->
        val option = pending?.let { runCatching { SaveOption.valueOf(it) }.getOrNull() }
        pending = null
        if (target == null || option == null) return@rememberLauncherForActivityResult
        val doc = currentSource()
        when (option) {
            SaveOption.SaveCopy -> viewModel.saveCopy(doc, target)
            else -> formatOf(option)?.let { viewModel.export(doc, it, target) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is TextDocSaveEvent.NoteSaved -> resources.getString(R.string.feature_textdoc_saved_note)
                is TextDocSaveEvent.SavedToFiles -> resources.getString(R.string.feature_textdoc_saved_files, event.name)
                TextDocSaveEvent.Saved -> resources.getString(R.string.feature_textdoc_toast_saved)
                is TextDocSaveEvent.Failed -> resources.getString(
                    when (event.reason) {
                        TextDocSaveFailure.TooLarge -> R.string.feature_textdoc_save_too_large
                        TextDocSaveFailure.NoSpace -> R.string.feature_textdoc_save_no_space
                        TextDocSaveFailure.Unreadable -> R.string.feature_textdoc_save_unreadable
                        TextDocSaveFailure.Failed -> R.string.feature_textdoc_toast_save_failed
                    },
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (event is TextDocSaveEvent.NoteSaved) openNote?.invoke(event.noteId, event.markdown)
        }
    }

    fun launchPicker(option: SaveOption, name: String, mime: String) {
        pending = option.name
        try {
            picker.launch(name to mime)
        } catch (_: ActivityNotFoundException) {
            pending = null
            Toast.makeText(context, R.string.feature_textdoc_save_no_picker, Toast.LENGTH_LONG).show()
        }
    }

    if (visible) {
        TextDocSaveDialog(
            options = SaveAsOptions.forTextDocument(writable = writable, inLibrary = inLibrary),
            markdown = markdown,
            onDismiss = onDismiss,
            onPick = { option ->
                onDismiss()
                val doc = currentSource()
                when (option) {
                    SaveOption.SaveInPlace -> onSaveInPlace()
                    SaveOption.SaveAsNote -> viewModel.saveAsNote(doc)
                    SaveOption.SaveToFiles -> viewModel.saveToFiles(doc)
                    SaveOption.SaveCopy -> launchPicker(option, viewModel.copyName(doc), copyMime(doc))
                    SaveOption.ExportPdf, SaveOption.ExportHtml, SaveOption.ExportDocx -> formatOf(option)?.let { format ->
                        launchPicker(option, viewModel.exportName(doc, format), TextDocSaveViewModel.mimeOf(format))
                    }
                }
            },
        )
    }
    if (working) SavingDialog()
}

private fun formatOf(option: SaveOption): ExportFormat? = when (option) {
    SaveOption.ExportPdf -> ExportFormat.PDF
    SaveOption.ExportHtml -> ExportFormat.HTML
    SaveOption.ExportDocx -> ExportFormat.DOCX
    else -> null
}

private fun copyMime(doc: TextDocSaveSource): String =
    doc.mimeType?.substringBefore(';')?.trim()?.takeIf { it.contains('/') && it != "*/*" }
        ?: if (doc.markdown) "text/markdown" else "text/plain"

/** The list of save options, drawn like the editor's other centred choice dialogs. */
@Composable
private fun TextDocSaveDialog(
    options: List<SaveOption>,
    markdown: Boolean,
    onDismiss: () -> Unit,
    onPick: (SaveOption) -> Unit,
) {
    val colors = SameSameTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(colors.dialog, RoundedCornerShape(28.dp)).padding(top = 22.dp, bottom = 10.dp),
        ) {
            Text(
                stringResource(R.string.feature_textdoc_save_title),
                style = SameSameText.Button,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            options.forEach { option -> AppMenuItem(stringResource(labelOf(option, markdown)), { onPick(option) }) }
        }
    }
}

private fun labelOf(option: SaveOption, markdown: Boolean): Int = when (option) {
    SaveOption.SaveInPlace -> R.string.feature_textdoc_save_in_place
    SaveOption.SaveAsNote -> if (markdown) R.string.feature_textdoc_save_as_markdown_note else R.string.feature_textdoc_save_as_note
    SaveOption.SaveToFiles -> R.string.feature_textdoc_save_to_files
    SaveOption.SaveCopy -> R.string.feature_textdoc_save_copy
    SaveOption.ExportPdf -> R.string.feature_textdoc_save_pdf
    SaveOption.ExportHtml -> R.string.feature_textdoc_save_html
    SaveOption.ExportDocx -> R.string.feature_textdoc_save_docx
}

/** "Saving..." while an option runs; it can't be dismissed, the work is short and leaves nothing half done. */
@Composable
private fun SavingDialog() {
    val colors = SameSameTheme.colors
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Row(
            Modifier.fillMaxWidth().background(colors.dialog, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Text(
                stringResource(R.string.feature_textdoc_saving),
                style = SameSameText.DialogMessage.capped(),
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 18.dp),
            )
        }
    }
}
