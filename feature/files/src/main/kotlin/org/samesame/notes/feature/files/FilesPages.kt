package org.samesame.notes.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.component.BottomActionBar
import org.samesame.notes.core.designsystem.component.BottomBarItem
import org.samesame.notes.core.designsystem.component.EmptyState
import org.samesame.notes.core.designsystem.component.SelectionCircle
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile

private fun <T> Set<T>.toggled(item: T): Set<T> = if (item in this) this - item else this + item

/** The Files Recycle bin: tap selects, bottom bar Restore / Delete, the menu empties it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilesTrashScreen(viewModel: FilesViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val colors = SameSameTheme.colors
    val trash by viewModel.trash.collectAsState()
    var files by remember { mutableStateOf(emptySet<FileId>()) }
    var folders by remember { mutableStateOf(emptySet<FileFolderId>()) }
    var menu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf(false) }
    var usedInNotes by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()
    val selecting = files.isNotEmpty() || folders.isNotEmpty()
    val empty = trash.files.isEmpty() && trash.folders.isEmpty()

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar(
                title = {
                    Text(
                        if (selecting) stringResource(R.string.feature_files_selected_count, files.size + folders.size) else stringResource(R.string.feature_files_bin_title),
                        style = SameSameText.SearchTitle.capped(), color = colors.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) { files = emptySet(); folders = emptySet() } else onBack() }, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Back, stringResource(R.string.feature_files_back), tint = colors.textPrimary)
                    }
                },
                actions = {
                    if (!empty && !selecting) Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                            Icon(AppIcons.More, stringResource(R.string.feature_files_more_options), tint = colors.textPrimary)
                        }
                        AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset((-1.7).dp, (-46.1).dp)) {
                            AppMenuItem(stringResource(R.string.feature_files_empty_bin_action), { menu = false; confirmEmpty = true })
                        }
                    }
                },
            )
            if (empty) EmptyState(stringResource(R.string.feature_files_bin_empty))
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                items(trash.folders, key = { "d" + it.id.value }) { folder ->
                    val sel = folder.id in folders
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).background(colors.card, RoundedCornerShape(12.dp))
                            .clickable { folders = folders.toggled(folder.id) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) SelectionCircle(sel, Modifier.padding(end = 12.dp))
                        Icon(AppIcons.Folder, null, tint = colors.iconMuted, modifier = Modifier.size(28.dp))
                        Text(folder.name, style = SameSameText.ListTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
                    }
                }
                items(trash.files, key = { "f" + it.id.value }) { file ->
                    val days = file.daysLeftInTrash(now)
                    FileListItem(
                        file, { viewModel.contentUri(file) }, selecting, file.id in files,
                        onClick = { files = files.toggled(file.id) },
                        onLongClick = { files = files.toggled(file.id) },
                        secondLine = days?.let { daysText(it) } ?: FilesFormat.sizeAndDate(file),
                    )
                }
            }
            if (selecting) BottomActionBar(
                listOf(
                    BottomBarItem(AppIcons.RestoreFromTrash, stringResource(R.string.feature_files_action_restore), {
                        viewModel.restore(files, folders); files = emptySet(); folders = emptySet()
                    }),
                    BottomBarItem(AppIcons.Trash, stringResource(R.string.feature_files_action_delete), {
                        scope.launch { usedInNotes = viewModel.usageCount(files.toList()); confirmForever = true }
                    }),
                ),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
    if (confirmEmpty) SimpleConfirmDialog(
        stringResource(R.string.feature_files_empty_bin_title), stringResource(R.string.feature_files_empty_bin_message),
        stringResource(R.string.feature_files_empty_bin_action), { confirmEmpty = false }, { confirmEmpty = false; viewModel.emptyTrash() },
    )
    if (confirmForever) DeleteDialog(
        forever = true, usedInNotes = usedInNotes, onDismiss = { confirmForever = false },
        onConfirm = { confirmForever = false; viewModel.deletePermanently(files, folders); files = emptySet(); folders = emptySet() },
    )
}

/** Name search over the library; every result shows its folder path, tap opens the file. */
@Composable
internal fun FilesSearchScreen(viewModel: FilesViewModel, folders: List<FileFolder>, snackbar: SnackbarHostState, onBack: () -> Unit, initialQuery: String = "") {
    val colors = SameSameTheme.colors
    val context = LocalContext.current
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialQuery) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results by remember(query) { if (query.isBlank()) emptyFlow() else viewModel.search(query) }.collectAsState(initial = emptyList())
    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            SimpleTopBar(
                title = {
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = SameSameText.SearchTitle.copy(color = colors.textPrimary, fontSize = 20.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) Text(stringResource(R.string.feature_files_search_hint), style = SameSameText.SearchTitle.copy(fontSize = 20.sp), color = colors.textTertiary)
                                inner()
                            }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Back, stringResource(R.string.feature_files_back), tint = colors.textPrimary)
                    }
                },
            )
            if (query.isNotBlank() && results.isEmpty()) EmptyState(stringResource(R.string.feature_files_empty_search))
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.id.value }) { file ->
                    val path = FilesFormat.path(file.folderId, folders)
                    FileListItem(
                        file, { viewModel.contentUri(file) }, false, false,
                        onClick = { org.samesame.notes.feature.files.viewer.openLibraryFile(context, file.id) },
                        onLongClick = {},
                        secondLine = if (path.isEmpty()) FilesFormat.sizeAndDate(file) else stringResource(R.string.feature_files_search_in, path),
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }
}

/** "Select folder": the Files folder tree. Folders being moved (and everything below them) are dimmed and ignore taps. */
@Composable
internal fun MoveFilesScreen(
    folders: List<FileFolder>,
    moving: Set<FileFolderId>,
    onBack: () -> Unit,
    onPick: (FileFolderId?) -> Unit,
    onCreateFolder: (String, Long?) -> Unit,
) {
    val colors = SameSameTheme.colors
    var creating by remember { mutableStateOf(false) }
    val blocked = remember(folders, moving) { FilesViewModel.blockedTargets(moving, folders) }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        SimpleTopBar(
            title = { Text(stringResource(R.string.feature_files_select_folder), style = SameSameText.SearchTitle.capped(), color = colors.textPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_files_back), tint = colors.textPrimary)
                }
            },
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(colors.card, RoundedCornerShape(26.dp))) {
            PickRow(AppIcons.Folder, stringResource(R.string.feature_files_root_folder), 0, true, colors.iconMuted) { onPick(null) }
            folders.forEach { f ->
                RowDivider()
                PickRow(
                    AppIcons.Folder, f.name, f.depth + 1, f.id !in blocked,
                    f.color?.let { Color(it.toInt()) } ?: colors.iconMuted,
                ) { onPick(f.id) }
            }
            RowDivider()
            PickRow(AppIcons.Add, stringResource(R.string.feature_files_menu_create_folder), 0, true, colors.positive) { creating = true }
        }
    }
    if (creating) NameDialog(
        title = stringResource(R.string.feature_files_create_folder_title),
        initial = stringResource(R.string.feature_files_folder_default_name, folders.size + 1),
        confirmLabel = stringResource(R.string.feature_files_create), showColours = true,
        onDismiss = { creating = false },
        onConfirm = { name, color -> creating = false; onCreateFolder(name, color) },
    )
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, depth: Int, enabled: Boolean, tint: Color, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().height(55.dp).clickable(enabled = enabled, onClick = onClick)
            .padding(start = 34.dp + (18 * depth.coerceAtMost(5)).dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(24.dp))
        Text(
            label, fontSize = 17.sp, color = if (enabled) colors.textPrimary else colors.textTertiary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 14.6.dp),
        )
    }
}
