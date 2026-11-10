package org.samesame.notes.feature.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.files.FileId

private enum class FilesPage { Files, Trash, Search, Move, Storage }

/** Where the Files screen starts when another screen sends the user to it. */
sealed interface FilesTarget {
    /** The folder of this file, with the file selected ("Open in Files"). */
    data class File(val id: FileId) : FilesTarget
    /** The search page with [query] filled in ("Search Files for ..."). */
    data class Search(val query: String) : FilesTarget
}

@Composable
fun FilesRoute(
    onBack: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    initialTarget: FilesTarget? = null,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importUi by viewModel.importUi.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(if (initialTarget is FilesTarget.Search) FilesPage.Search else FilesPage.Files) }
    // The target is applied once; a recreated screen keeps where the user went from there.
    var targetApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialTarget) {
        if (!targetApplied) {
            targetApplied = true
            if (initialTarget is FilesTarget.File) viewModel.reveal(initialTarget.id)
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = eventText(resources, event)
            snackbar.currentSnackbarData?.dismiss()
            if (text != null) snackbar.showSnackbar(text)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (page) {
            FilesPage.Files -> FilesScreen(
                state = state, viewModel = viewModel, snackbar = snackbar, onBack = onBack,
                onOpenSearch = { page = FilesPage.Search },
                onOpenTrash = { page = FilesPage.Trash },
                onOpenStorage = { page = FilesPage.Storage },
                onMove = { page = FilesPage.Move },
                onOpenNote = onOpenNote,
            )
            FilesPage.Trash -> {
                BackHandler { page = FilesPage.Files }
                FilesTrashScreen(viewModel = viewModel, snackbar = snackbar, onBack = { page = FilesPage.Files })
            }
            FilesPage.Search -> {
                BackHandler { page = FilesPage.Files }
                FilesSearchScreen(
                    viewModel = viewModel, folders = state.allFolders, snackbar = snackbar,
                    initialQuery = (initialTarget as? FilesTarget.Search)?.query.orEmpty(),
                    onBack = { page = FilesPage.Files },
                )
            }
            FilesPage.Storage -> {
                BackHandler { page = FilesPage.Files }
                StorageScreen(contentUriOf = viewModel::contentUri, onBack = { page = FilesPage.Files })
            }
            FilesPage.Move -> {
                BackHandler { page = FilesPage.Files }
                MoveFilesScreen(
                    folders = state.allFolders,
                    moving = state.selection.folders,
                    onBack = { page = FilesPage.Files },
                    onPick = { target -> viewModel.moveSelection(target); page = FilesPage.Files },
                    onCreateFolder = { name, color -> viewModel.createFolder(name, color, null) },
                )
            }
        }
        importUi?.progress?.let { ImportProgressDialog(it, viewModel::cancelImport) }
        importUi?.duplicates?.firstOrNull()?.let { dup ->
            DuplicateDialog(
                name = dup.existing.displayName,
                onKeepBoth = { viewModel.resolveDuplicate(true) },
                onSkip = { viewModel.resolveDuplicate(false) },
            )
        }
    }
}

internal fun eventText(resources: android.content.res.Resources, event: FilesEvent): String? = when (event) {
    is FilesEvent.Imported -> buildString {
        if (event.count > 0) append(resources.getQuantityString(R.plurals.feature_files_toast_imported, event.count, event.count))
        if (event.skipped > 0) {
            if (isNotEmpty()) append(". ")
            append(resources.getQuantityString(R.plurals.feature_files_toast_skipped, event.skipped, event.skipped))
        }
    }.ifEmpty { null }
    is FilesEvent.ImportFailed -> {
        val first = event.failures.firstOrNull()
        val base = when (first?.reason) {
            ImportFailureReason.TooLarge -> resources.getString(R.string.feature_files_err_too_large, first.name)
            ImportFailureReason.NoSpace -> resources.getString(R.string.feature_files_err_no_space, first.name)
            ImportFailureReason.Unreadable -> resources.getString(R.string.feature_files_err_unreadable, first.name)
            null -> null
        }
        listOfNotNull(base, resources.getString(R.string.feature_files_err_ignored).takeIf { event.ignored > 0 }).joinToString(". ")
    }
    is FilesEvent.Trashed -> resources.getQuantityString(R.plurals.feature_files_toast_trashed, event.count, event.count)
    is FilesEvent.Restored -> resources.getQuantityString(R.plurals.feature_files_toast_restored, event.count, event.count)
    is FilesEvent.Moved -> resources.getQuantityString(R.plurals.feature_files_toast_moved, event.count, event.count)
    is FilesEvent.LinkCopied -> resources.getQuantityString(R.plurals.feature_files_toast_link_copied, event.count, event.count)
    is FilesEvent.Failure -> resources.getString(
        when (event.kind) {
            FailureKind.FolderName -> R.string.feature_files_err_folder_name
            FailureKind.TooDeep -> R.string.feature_files_err_too_deep
            FailureKind.Rename -> R.string.feature_files_err_rename
            FailureKind.Generic -> R.string.feature_files_err_generic
            FailureKind.NotFound -> R.string.feature_files_viewer_not_found
        },
    )
}
