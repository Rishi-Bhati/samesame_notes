package org.samesame.notes.feature.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppFab
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.component.BottomActionBar
import org.samesame.notes.core.designsystem.component.BottomBarItem
import org.samesame.notes.core.designsystem.component.CollapsingHeaderScaffold
import org.samesame.notes.core.designsystem.component.EmptyState
import org.samesame.notes.core.designsystem.component.FastScrollBar
import org.samesame.notes.core.designsystem.component.SelectionCircle
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.files.FileSortField
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.viewer.openLibraryFile

private enum class Menu { None, Main, View }
private enum class Dialog { None, RenameFile, RenameFolder, CreateFolder, Delete, Info }

/** The Files screen: folders, sort row, files in a grid or list, select mode with the bottom bar. */
@Composable
internal fun FilesScreen(
    state: FilesUiState,
    viewModel: FilesViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenStorage: () -> Unit,
    onMove: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = SameSameTheme.colors
    var menu by remember { mutableStateOf(Menu.None) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(Dialog.None) }
    var deleteUsed by remember { mutableStateOf(0) }
    var infoFile by remember { mutableStateOf<LibraryFile?>(null) }
    val noApp = stringResource(R.string.feature_files_no_app)
    val shareLabel = stringResource(R.string.feature_files_open_failed_share)

    fun share(files: List<LibraryFile>) {
        if (files.isEmpty()) return
        val intent = FilesIntents.send(files, files.map(viewModel::contentUri))
        FilesIntents.launch(context, android.content.Intent.createChooser(intent, null))
    }
    fun open(file: LibraryFile) { openLibraryFile(context, file.id) }
    fun openWith(file: LibraryFile) {
        val uri = viewModel.contentUri(file)
        if (!FilesIntents.launch(context, FilesIntents.view(file, uri))) {
            scope.launch {
                val result = snackbar.showSnackbar(noApp, actionLabel = shareLabel)
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) share(listOf(file))
            }
        }
    }
    fun copyLink(files: List<LibraryFile>) {
        if (files.isEmpty()) return
        FilesIntents.copyText(context, "link", viewModel.linkText(files))
        viewModel.linkCopied(files.size)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> viewModel.upload(uris) }
    val upload = { picker.launch(arrayOf("*/*")) }

    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }
    BackHandler(enabled = !state.selecting) { if (!viewModel.navigateUp()) onBack() }

    val selected = state.selection
    val singleFile = selected.count == 1 && selected.files.size == 1
    val oneItem = selected.count == 1
    val filesSelected = state.selectedFiles
    val title = when {
        state.selecting && selected.count == 0 -> stringResource(R.string.feature_files_select_files)
        state.selecting -> stringResource(R.string.feature_files_selected_count, selected.count)
        else -> state.title ?: stringResource(R.string.feature_files_title)
    }
    val subtitle = if (state.selecting) null else {
        val (count, bytes) = if (state.folderId == null) state.summary.fileCount to state.summary.totalBytes
        else state.files.size to state.files.sumOf { it.sizeBytes }
        stringResource(
            R.string.feature_files_summary,
            pluralStringResource(R.plurals.feature_files_summary_files, count, count),
            FilesFormat.size(bytes),
        )
    }
    var scrollable by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        CollapsingHeaderScaffold(
            title = title,
            subtitle = subtitle,
            contentScrollable = !state.isEmpty && scrollable,
            titleKey = state.selecting,
            navigationIcon = {
                if (state.selecting) {
                    Row(
                        Modifier.clickable(onClick = viewModel::toggleSelectAll).padding(horizontal = 8.dp).size(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { SelectionCircle(state.allSelected) }
                } else {
                    IconButton(onClick = { if (!viewModel.navigateUp()) onBack() }, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Back, stringResource(R.string.feature_files_back), tint = colors.textPrimary)
                    }
                }
            },
            actions = {
                if (!state.selecting) {
                    IconButton(onClick = upload, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Upload, stringResource(R.string.feature_files_upload), tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                    }
                    IconButton(onClick = onOpenSearch, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Search, stringResource(R.string.feature_files_search), tint = colors.textPrimary)
                    }
                    Box {
                        IconButton(onClick = { menu = Menu.Main }, modifier = Modifier.padding(end = 4.8.dp).size(width = 32.dp, height = 40.dp)) {
                            Icon(AppIcons.More, stringResource(R.string.feature_files_more_options), tint = colors.textPrimary)
                        }
                        AppDropdownMenu(expanded = menu != Menu.None, onDismissRequest = { menu = Menu.None }, offset = DpOffset((-1.7).dp, (-46.1).dp)) {
                            if (menu == Menu.View) {
                                AppMenuItem(stringResource(R.string.feature_files_view_grid), { viewModel.setViewMode(FilesViewMode.Grid); menu = Menu.None }, checked = state.viewMode == FilesViewMode.Grid)
                                AppMenuItem(stringResource(R.string.feature_files_view_list), { viewModel.setViewMode(FilesViewMode.List); menu = Menu.None }, checked = state.viewMode == FilesViewMode.List)
                            } else {
                                AppMenuItem(stringResource(R.string.feature_files_menu_edit), { menu = Menu.None; viewModel.startSelecting() })
                                AppMenuItem(stringResource(R.string.feature_files_menu_view), { menu = Menu.View })
                                AppMenuItem(stringResource(R.string.feature_files_menu_create_folder), { menu = Menu.None; dialog = Dialog.CreateFolder })
                                AppMenuItem(stringResource(R.string.feature_files_menu_recycle_bin), { menu = Menu.None; onOpenTrash() })
                                AppMenuItem(stringResource(R.string.feature_files_storage_title), { menu = Menu.None; onOpenStorage() })
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!state.selecting) AppFab(AppIcons.Upload, stringResource(R.string.feature_files_upload), upload)
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = state.selecting,
                    enter = slideInVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90)) { it } +
                        expandVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90), expandFrom = Alignment.Bottom),
                    exit = slideOutVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90)) { it } +
                        shrinkVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90), shrinkTowards = Alignment.Bottom),
                ) {
                    val hasFiles = filesSelected.isNotEmpty()
                    val hasAny = selected.count > 0
                    BottomActionBar(
                        listOf(
                            BottomBarItem(AppIcons.Copy, stringResource(R.string.feature_files_action_copy_link), { copyLink(filesSelected); viewModel.clearSelection() }, hasFiles),
                            BottomBarItem(AppIcons.FolderMove, stringResource(R.string.feature_files_action_move), onMove, hasAny),
                            BottomBarItem(AppIcons.Share, stringResource(R.string.feature_files_action_share), { share(filesSelected) }, hasFiles),
                            BottomBarItem(AppIcons.Trash, stringResource(R.string.feature_files_action_delete), {
                                scope.launch { deleteUsed = viewModel.usageCount(selected.files.toList()); dialog = Dialog.Delete }
                            }, hasAny),
                            BottomBarItem(AppIcons.More, stringResource(R.string.feature_files_action_more), { moreMenu = true }, hasAny),
                        ),
                    )
                }
            },
        ) {
            when {
                !state.loaded -> Unit
                state.isEmpty -> EmptyState(
                    message = stringResource(R.string.feature_files_empty),
                    hint = stringResource(R.string.feature_files_empty_hint),
                )
                else -> FilesGrid(
                    state = state,
                    sortMenu = sortMenu,
                    onSortMenu = { sortMenu = it },
                    viewModel = viewModel,
                    onOpenFile = { open(it) },
                    onScrollable = { scrollable = it },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))

        // The More menu hangs off the bottom bar's right side.
        Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 90.3.dp, end = 20.2.dp)) {
            AppDropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                if (oneItem) AppMenuItem(stringResource(R.string.feature_files_more_rename), {
                    moreMenu = false
                    dialog = if (singleFile) Dialog.RenameFile else Dialog.RenameFolder
                })
                if (filesSelected.isNotEmpty()) {
                    val allFav = filesSelected.all { it.isFavorite }
                    AppMenuItem(
                        stringResource(if (allFav) R.string.feature_files_more_remove_favorite else R.string.feature_files_more_add_favorite),
                        { moreMenu = false; viewModel.toggleFavorite(filesSelected) },
                    )
                }
                if (singleFile) {
                    AppMenuItem(stringResource(R.string.feature_files_more_info), { moreMenu = false; infoFile = filesSelected.first(); dialog = Dialog.Info })
                    AppMenuItem(stringResource(R.string.feature_files_more_open_with), { moreMenu = false; openWith(filesSelected.first()) })
                }
            }
        }
    }

    when (dialog) {
        Dialog.RenameFile -> filesSelected.firstOrNull()?.let { f ->
            NameDialog(
                title = stringResource(R.string.feature_files_rename_title), initial = f.displayName,
                confirmLabel = stringResource(R.string.feature_files_save), selectBaseOnly = true,
                onDismiss = { dialog = Dialog.None },
                onConfirm = { name, _ -> dialog = Dialog.None; viewModel.rename(f.id, name) },
            )
        }
        Dialog.RenameFolder -> state.selectedFolders.firstOrNull()?.let { f ->
            NameDialog(
                title = stringResource(R.string.feature_files_rename_folder_title), initial = f.name,
                confirmLabel = stringResource(R.string.feature_files_save),
                onDismiss = { dialog = Dialog.None },
                onConfirm = { name, _ -> dialog = Dialog.None; viewModel.renameFolder(f.id, name) },
            )
        }
        Dialog.CreateFolder -> NameDialog(
            title = stringResource(R.string.feature_files_create_folder_title),
            initial = stringResource(R.string.feature_files_folder_default_name, state.subFolders.size + 1),
            confirmLabel = stringResource(R.string.feature_files_create), showColours = true,
            onDismiss = { dialog = Dialog.None },
            onConfirm = { name, color -> dialog = Dialog.None; viewModel.createFolder(name, color) },
        )
        Dialog.Delete -> DeleteDialog(
            forever = false, usedInNotes = deleteUsed,
            onDismiss = { dialog = Dialog.None },
            onConfirm = { dialog = Dialog.None; viewModel.trashSelection() },
        )
        Dialog.Info -> infoFile?.let { f ->
            val usage by remember(f.id) { viewModel.usage(f.id) }.collectAsState(initial = emptyList())
            InfoDialog(
                file = f,
                locationPath = FilesFormat.path(f.folderId, state.allFolders),
                usage = usage,
                onDismiss = { dialog = Dialog.None; viewModel.clearSelection() },
                onCopyLink = { copyLink(listOf(f)) },
                onOpenNote = { dialog = Dialog.None; viewModel.clearSelection(); onOpenNote(it) },
            )
        }
        Dialog.None -> Unit
    }
}

@Composable
private fun FilesGrid(
    state: FilesUiState,
    sortMenu: Boolean,
    onSortMenu: (Boolean) -> Unit,
    viewModel: FilesViewModel,
    onOpenFile: (LibraryFile) -> Unit,
    onScrollable: (Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val list = state.viewMode == FilesViewMode.List
    LaunchedEffect(gridState) {
        androidx.compose.runtime.snapshotFlow { gridState.canScrollForward || gridState.canScrollBackward }.collect(onScrollable)
    }
    // "Open in Files": bring the revealed file into view once (the folder row and the sort row come before the files).
    LaunchedEffect(state.reveal, state.files) {
        val id = state.reveal ?: return@LaunchedEffect
        val index = state.files.indexOfFirst { it.id == id }
        if (index >= 0) {
            gridState.scrollToItem(index + (if (state.subFolders.isNotEmpty()) 1 else 0) + 1)
            viewModel.revealShown()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 14.dp
        val side = 16.dp
        val columns = if (list) 1 else (maxWidth / 160.dp).toInt().coerceAtLeast(2)
        val folderWidth = (maxWidth - side * 2 - gap * 3) / 4
        FastScrollBar(
            scrollFraction = {
                val info = gridState.layoutInfo
                val total = info.totalItemsCount
                val visible = info.visibleItemsInfo.size
                if (total <= visible || total == 0) 0f
                else (gridState.firstVisibleItemIndex.toFloat() / (total - visible)).coerceIn(0f, 1f)
            },
            onScrub = { f ->
                val total = gridState.layoutInfo.totalItemsCount
                scope.launch { gridState.scrollToItem((f * (total - 1)).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))) }
            },
            needed = { gridState.canScrollForward || gridState.canScrollBackward },
            edgeStrip = true,
            showOnStart = false,
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(if (list) 8.dp else 16.dp),
            ) {
                if (state.subFolders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "folders") {
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            state.subFolders.forEach { folder ->
                                val isSelected = folder.id in state.selection.folders
                                FileFolderCard(
                                    folder, folderWidth, state.selecting, isSelected,
                                    onClick = { if (state.selecting) viewModel.toggle(folder) else viewModel.openFolder(folder.id) },
                                    onLongClick = { if (state.selecting) viewModel.toggle(folder) else viewModel.startSelecting(folder = folder) },
                                )
                            }
                        }
                    }
                }
                if (state.files.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "sort") {
                        val field = state.sort.field
                        Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                            FilesSortRow(
                                label = stringResource(sortLabel(field)),
                                ascending = state.sort.ascending,
                                onLabelClick = { onSortMenu(true) },
                                onToggleDirection = viewModel::toggleSortDirection,
                            )
                            Box(Modifier.align(Alignment.TopEnd)) {
                                AppDropdownMenu(expanded = sortMenu, onDismissRequest = { onSortMenu(false) }, offset = DpOffset((-8.4).dp, 9.9.dp)) {
                                    FileSortField.entries.forEach { f ->
                                        AppMenuItem(stringResource(sortLabel(f)), { viewModel.setSortField(f); onSortMenu(false) }, checked = f == field)
                                    }
                                }
                            }
                        }
                    }
                }
                items(state.files, key = { it.id.value }) { file ->
                    val isSelected = file.id in state.selection.files
                    val click = { if (state.selecting) viewModel.toggle(file) else onOpenFile(file) }
                    val long = { if (state.selecting) viewModel.toggle(file) else viewModel.startSelecting(file = file) }
                    val uriOf = { viewModel.contentUri(file) }
                    if (list) FileListItem(file, uriOf, state.selecting, isSelected, click, long)
                    else FileGridItem(file, uriOf, state.selecting, isSelected, click, long)
                }
            }
        }
    }
}

internal fun sortLabel(field: FileSortField): Int = when (field) {
    FileSortField.Name -> R.string.feature_files_sort_name
    FileSortField.DateAdded -> R.string.feature_files_sort_date
    FileSortField.Size -> R.string.feature_files_sort_size
    FileSortField.Type -> R.string.feature_files_sort_type
}
