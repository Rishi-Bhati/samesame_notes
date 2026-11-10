package org.samesame.notes.feature.files.viewer

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.DeleteDialog
import org.samesame.notes.feature.files.FilesFormat
import org.samesame.notes.feature.files.FilesIntents
import org.samesame.notes.feature.files.InfoDialog
import org.samesame.notes.feature.files.NameDialog
import org.samesame.notes.feature.files.R

/** What a viewer adds to the shared top bar: a second title line, its own icons, and its own menu rows (on top). */
internal class BarSlots(
    val subtitle: String? = null,
    val actions: @Composable RowScope.() -> Unit = {},
    val menu: @Composable (dismiss: () -> Unit) -> Unit = {},
)

private enum class ViewerDialog { None, Rename, Delete, Info }

/**
 * The viewer for one library file, hosted by the viewer activity (see [openLibraryFile]). It draws nothing until the
 * file is known, so a file that goes to the system intent, or an audio file's sheet, never flashes a blank page.
 *
 * [onDarkChrome] tells the host that the content is dark whatever the theme (the image viewer), for the system bar icons.
 */
@Composable
fun FileViewerRoute(
    fileId: FileId,
    onClose: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onDarkChrome: (Boolean) -> Unit = {},
    viewModel: FileViewerViewModel = hiltViewModel(),
) {
    LaunchedEffect(fileId) { viewModel.start(fileId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf(ViewerDialog.None) }
    var deleteUsed by remember { mutableStateOf(0) }
    var handedOff by rememberSaveable { mutableStateOf(false) }

    fun toast(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    fun share(file: LibraryFile) {
        FilesIntents.launch(context, Intent.createChooser(FilesIntents.send(listOf(file), listOf(viewModel.contentUri(file))), null))
    }
    fun openWith(file: LibraryFile): Boolean {
        val ok = FilesIntents.launch(context, Intent.createChooser(FilesIntents.view(file, viewModel.contentUri(file)), null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        if (!ok) toast(R.string.feature_files_no_app)
        return ok
    }
    fun copyLink(file: LibraryFile) {
        FilesIntents.copyText(context, "link", viewModel.linkText(file))
        Toast.makeText(context, context.resources.getQuantityString(R.plurals.feature_files_toast_link_copied, 1, 1), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ViewerEvent.Deleted -> {
                    Toast.makeText(context, context.resources.getQuantityString(R.plurals.feature_files_toast_trashed, 1, 1), Toast.LENGTH_SHORT).show()
                    onClose()
                }
                ViewerEvent.RenameFailed -> toast(R.string.feature_files_err_rename)
                is ViewerEvent.NoteCreated -> { onOpenNote(event.noteId); onClose() }
                ViewerEvent.NoteFailed -> toast(R.string.feature_files_receive_note_failed)
            }
        }
    }

    when (val s = state) {
        ViewerState.Loading -> Unit
        ViewerState.Missing -> LaunchedEffect(Unit) {
            // A file deleted from inside the viewer closes it through the Deleted event instead.
            if (!handedOff) { handedOff = true; toast(R.string.feature_files_viewer_not_found) }
            onClose()
        }
        is ViewerState.Trashed -> if (!handedOff) AppDialog(
            onDismissRequest = onClose,
            dismissLabel = stringResource(R.string.feature_files_cancel),
            confirmLabel = stringResource(R.string.feature_files_action_restore),
            onConfirm = { viewModel.restore(s.file.id) },
            title = stringResource(R.string.feature_files_viewer_in_bin),
            atBottom = true,
        ) {
            androidx.compose.material3.Text(s.file.displayName, style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary)
        }
        is ViewerState.Ready -> {
            val file = s.file
            val image = s.kind == ViewerKind.Image
            DisposableEffect(image) { onDarkChrome(image); onDispose { onDarkChrome(false) } }

            val uri = remember(file.id, file.displayName) { viewModel.contentUri(file) }
            // Share and the menu every viewer has; a viewer's own rows go on top.
            val actions: @Composable (BarSlots, ChromeColors) -> Unit = { slots, chrome ->
                var menu by remember { mutableStateOf(false) }
                ChromeIconButton(AppIcons.Share, stringResource(R.string.feature_files_action_share), chrome, { share(file) })
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.padding(end = 4.8.dp).size(width = 32.dp, height = 40.dp)) {
                        Icon(AppIcons.More, stringResource(R.string.feature_files_more_options), tint = chrome.content)
                    }
                    AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset((-1.7).dp, (-46.1).dp)) {
                        slots.menu { menu = false }
                        AppMenuItem(stringResource(R.string.feature_files_more_open_with), { menu = false; openWith(file) })
                        AppMenuItem(stringResource(R.string.feature_files_action_copy_link), { menu = false; copyLink(file) })
                        AppMenuItem(stringResource(R.string.feature_files_more_rename), { menu = false; dialog = ViewerDialog.Rename })
                        AppMenuItem(stringResource(R.string.feature_files_action_delete), {
                            menu = false
                            scope.launch { deleteUsed = viewModel.usageCount(file.id); dialog = ViewerDialog.Delete }
                        })
                        AppMenuItem(stringResource(R.string.feature_files_more_info), { menu = false; dialog = ViewerDialog.Info })
                    }
                }
            }
            val bar: @Composable (BarSlots, ChromeColors) -> Unit = { slots, chrome ->
                ViewerTopBar(file.displayName, slots.subtitle, chrome, onBack = onClose) {
                    slots.actions(this)
                    actions(slots, chrome)
                }
            }

            when (s.kind) {
                ViewerKind.Image -> {
                    BackHandler(onBack = onClose)
                    var bars by rememberSaveable { mutableStateOf(true) }
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        ImageViewer(
                            images = s.images, index = s.imageIndex,
                            uriOf = viewModel::contentUri, onPage = viewModel::showImage, onTap = { bars = !bars },
                        )
                        AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                            val position = if (s.images.size > 1 && s.imageIndex >= 0) {
                                stringResource(R.string.feature_files_viewer_position, s.imageIndex + 1, s.images.size)
                            } else null
                            bar(BarSlots(subtitle = listOfNotNull(position, FilesFormat.size(file.sizeBytes)).joinToString(" · ")), OverImageChrome)
                        }
                    }
                }
                ViewerKind.External -> LaunchedEffect(file.id) {
                    if (!handedOff) {
                        handedOff = true
                        if (!FilesIntents.launchView(context, file, viewModel.contentUri(file))) toast(R.string.feature_files_no_app)
                    }
                    onClose()
                }
                ViewerKind.Pdf -> {
                    BackHandler(onBack = onClose)
                    PdfViewer(
                        file = file, uri = uri, bar = bar,
                        onPageCount = { viewModel.learned(file, pageCount = it) },
                        onOpenWith = { openWith(file) },
                        onAnnotate = { viewModel.annotateAsNote(file) { context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException() } },
                    )
                }
                ViewerKind.Text -> {
                    BackHandler(onBack = onClose)
                    TextViewer(file = file, uri = uri, bar = bar, onOpenWith = { openWith(file) })
                }
                ViewerKind.Audio -> AudioSheet(
                    file = file, uri = uri,
                    actions = { chrome -> actions(BarSlots(), chrome) },
                    onDuration = { viewModel.learned(file, durationMs = it) },
                    onOpenWith = { openWith(file) },
                    onClose = onClose,
                )
            }

            when (dialog) {
                ViewerDialog.Rename -> NameDialog(
                    title = stringResource(R.string.feature_files_rename_title), initial = file.displayName,
                    confirmLabel = stringResource(R.string.feature_files_save), selectBaseOnly = true,
                    onDismiss = { dialog = ViewerDialog.None },
                    onConfirm = { name, _ -> dialog = ViewerDialog.None; viewModel.rename(file.id, name) },
                )
                ViewerDialog.Delete -> DeleteDialog(
                    forever = false, usedInNotes = deleteUsed,
                    onDismiss = { dialog = ViewerDialog.None },
                    onConfirm = { dialog = ViewerDialog.None; handedOff = true; viewModel.delete(file.id) },
                )
                ViewerDialog.Info -> {
                    val usage by remember(file.id) { viewModel.usage(file.id) }.collectAsState(initial = emptyList())
                    InfoDialog(
                        file = file,
                        locationPath = FilesFormat.path(file.folderId, s.folders),
                        usage = usage,
                        onDismiss = { dialog = ViewerDialog.None },
                        onCopyLink = { copyLink(file) },
                        onOpenNote = { dialog = ViewerDialog.None; onOpenNote(it) },
                    )
                }
                ViewerDialog.None -> Unit
            }
            if (busy) BusyOverlay()
        }
    }
}

@Composable
private fun BusyOverlay() {
    Box(
        Modifier.fillMaxSize().background(SameSameTheme.colors.scrim).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(color = SameSameTheme.colors.accent)
    }
}
