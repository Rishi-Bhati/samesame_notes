package org.samesame.notes.feature.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.transfer.NoteTransferService
import org.samesame.notes.core.data.transfer.TransferBatch
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.export.ExportFormat
import org.samesame.notes.core.model.NoteId
import javax.inject.Inject

/** What the notes list asks the host to do for the selected (unlocked) notes. */
sealed interface TransferRequest {
    val ids: List<NoteId>
    val format: ExportFormat
    /** `.ssnote` only: bundle the library files the notes link to. */
    val includeLinkedFiles: Boolean

    /** Export, then open the system share sheet. */
    data class Share(
        override val ids: List<NoteId>, override val format: ExportFormat, override val includeLinkedFiles: Boolean = true,
    ) : TransferRequest

    /** Export, then let the user pick where to put the file(s). Several notes: one zip or a folder of files. */
    data class Save(
        override val ids: List<NoteId>, override val format: ExportFormat, override val includeLinkedFiles: Boolean = true,
    ) : TransferRequest
}

@HiltViewModel
class NoteTransferViewModel @Inject constructor(val service: NoteTransferService) : ViewModel()

private data class Progress(val done: Int, val total: Int, val title: String)

/**
 * Runs one [TransferRequest] at a time: shows the export progress, then opens the share sheet or the file picker.
 * [onFinished] is called when it is over (done, failed or cancelled); [onMessage] gets the toast-like result text.
 */
@Composable
fun NoteTransferHost(
    request: TransferRequest?,
    onFinished: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: NoteTransferViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val service = viewModel.service
    var progress by remember { mutableStateOf<Progress?>(null) }
    var askMode by remember { mutableStateOf<CompletableDeferred<Boolean?>?>(null) }
    var pendingFile by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    var pendingTree by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }

    val pickFile = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        pendingFile?.complete(uri); pendingFile = null
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        pendingTree?.complete(uri); pendingTree = null
    }
    val failed = stringResource(R.string.feature_share_failed)
    val nothing = stringResource(R.string.feature_share_nothing)
    val chooserTitle = stringResource(R.string.feature_share_chooser_title)
    val zipName = stringResource(R.string.feature_share_zip_name)
    val savedFmt = stringResource(R.string.feature_share_saved)
    val savedCountFmt = stringResource(R.string.feature_share_saved_count)

    LaunchedEffect(request) {
        val req = request ?: return@LaunchedEffect
        try {
            // Several notes saved as a file: ask "File" (a folder of files) or "Zip" first.
            val asZip: Boolean = if (req is TransferRequest.Save && req.ids.size > 1) {
                val answer = CompletableDeferred<Boolean?>()
                askMode = answer
                val r = answer.await()
                askMode = null
                if (r == null) return@LaunchedEffect
                r
            } else false
            progress = Progress(0, req.ids.size, "")
            val batch = service.exportToCache(req.ids, req.format, org.samesame.notes.core.export.ExportOptions(includeLinkedFiles = req.includeLinkedFiles)) { done, total, title -> progress = Progress(done, total, title) }
            progress = null
            if (batch.items.isEmpty()) {
                onMessage(if (batch.failed > 0) failed else nothing)
                return@LaunchedEffect
            }
            when (req) {
                is TransferRequest.Share -> ShareFiles.shareItems(context, batch.items, chooserTitle)
                is TransferRequest.Save -> {
                    val saved = when {
                        asZip -> saveZip(context, service, batch, zipName, pickFile) { pendingFile = it }
                        batch.items.size == 1 -> saveSingle(context, batch, pickFile) { pendingFile = it }
                        else -> saveFolder(context, batch, pickTree) { pendingTree = it }
                    }
                    when {
                        saved == null -> Unit // cancelled in the picker
                        saved < 0 -> onMessage(failed)
                        saved == 1 && batch.items.size == 1 -> onMessage(savedFmt.format(batch.items[0].name))
                        else -> onMessage(savedCountFmt.format(saved))
                    }
                    if (saved != null && saved >= 0 && batch.failed > 0) onMessage(failed)
                    // The copies are made: the plaintext exports must not stay in the cache.
                    withContext(Dispatchers.IO) { service.discard(batch) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onMessage(failed)
        } finally {
            progress = null
            askMode = null
            onFinished()
        }
    }

    progress?.let { ProgressDialog(it) }
    askMode?.let { answer ->
        AppDialog(
            onDismissRequest = { answer.complete(null) },
            dismissLabel = stringResource(R.string.feature_share_as_zip),
            confirmLabel = stringResource(R.string.feature_share_as_file),
            onConfirm = { answer.complete(false) },
            onDismissClick = { answer.complete(true) },
            title = stringResource(R.string.feature_share_save_as_file),
        )
    }
}

/** One file through the "create document" picker. Returns 1, null when cancelled, -1 on a write error. */
private suspend fun saveSingle(
    context: Context, batch: TransferBatch, launcher: androidx.activity.result.ActivityResultLauncher<Pair<String, String>>,
    setPending: (CompletableDeferred<Uri?>) -> Unit,
): Int? {
    val item = batch.items.single()
    val result = CompletableDeferred<Uri?>().also(setPending)
    launcher.launch(item.name to item.mimeType)
    val uri = result.await() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { out -> item.file.inputStream().use { it.copyTo(out) } }
            1
        }.getOrDefault(-1)
    }
}

private suspend fun saveZip(
    context: Context, service: NoteTransferService, batch: TransferBatch, zipName: String,
    launcher: androidx.activity.result.ActivityResultLauncher<Pair<String, String>>, setPending: (CompletableDeferred<Uri?>) -> Unit,
): Int? {
    val result = CompletableDeferred<Uri?>().also(setPending)
    launcher.launch(zipName to "application/zip")
    val uri = result.await() ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { service.writeZip(batch, it) }
            1
        }.getOrDefault(-1)
    }
}

/** Every file into a folder the user picks; the provider renames clashes itself ("name (1).pdf"). Returns the count. */
private suspend fun saveFolder(
    context: Context, batch: TransferBatch, launcher: androidx.activity.result.ActivityResultLauncher<Uri?>,
    setPending: (CompletableDeferred<Uri?>) -> Unit,
): Int? {
    val result = CompletableDeferred<Uri?>().also(setPending)
    launcher.launch(null)
    val tree = result.await() ?: return null
    return withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var saved = 0
        for (item in batch.items) {
            runCatching {
                // Providers append the MIME type's extension when the name's own extension maps to another type
                // (`Plan.ssnote` as application/zip becomes `Plan.ssnote.zip`), so only pass a type the extension agrees with.
                val mime = CreateTypedDocument.documentMime(item.name, item.mimeType)
                val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, item.name)!!
                context.contentResolver.openOutputStream(doc)!!.use { out -> item.file.inputStream().use { it.copyTo(out) } }
                saved++
            }
        }
        if (saved == 0) -1 else saved
    }
}

@Composable
private fun ProgressDialog(progress: Progress) = TransferProgressDialog(progress.title, progress.done, progress.total)

/**
 * The export/import progress dialog: current item name (or "Exporting…"), a determinate bar and "done/total".
 * Not cancellable. Shared with Settings' bulk Import / Export.
 */
@Composable
fun TransferProgressDialog(title: String, done: Int, total: Int, defaultTitle: String = stringResource(R.string.feature_share_exporting)) {
    val progress = Progress(done, total, title)
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = SameSameTheme.colors
        Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(horizontal = 24.dp), shape = RoundedCornerShape(28.dp), color = colors.dialog) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp)) {
                Text(
                    progress.title.ifEmpty { defaultTitle },
                    fontSize = 12.sp, color = colors.textPrimary, maxLines = 1, modifier = Modifier.padding(bottom = 20.dp),
                )
                LinearProgressIndicator(
                    progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.pill,
                )
                Row(Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    Text(stringResource(R.string.feature_share_progress, progress.done.coerceAtMost(progress.total), progress.total), fontSize = 14.sp, color = colors.textPrimary)
                }
            }
        }
    }
}

/** A non-cancellable "please wait" dialog with an indeterminate bar (PDF import). */
@Composable
fun BusyDialog(title: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        val colors = SameSameTheme.colors
        Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(horizontal = 24.dp), shape = RoundedCornerShape(28.dp), color = colors.dialog) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp)) {
                Text(title, fontSize = 12.sp, color = colors.textPrimary, maxLines = 1, modifier = Modifier.padding(bottom = 20.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.pill)
            }
        }
    }
}
