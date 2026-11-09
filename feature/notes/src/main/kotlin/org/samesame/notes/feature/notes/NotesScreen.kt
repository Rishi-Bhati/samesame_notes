package org.samesame.notes.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.feature.share.BusyDialog
import org.samesame.notes.feature.share.NoteTransferHost
import org.samesame.notes.feature.share.SaveAsFileDialog
import org.samesame.notes.feature.share.ShareFormatMenu
import org.samesame.notes.feature.share.TransferRequest
import org.samesame.notes.core.designsystem.component.BottomActionBar
import org.samesame.notes.core.designsystem.component.BottomBarItem
import org.samesame.notes.core.designsystem.component.CollapsingHeaderScaffold
import org.samesame.notes.core.designsystem.component.EmptyState
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppPushDrawer
import org.samesame.notes.core.designsystem.component.rememberPushDrawerState
import org.samesame.notes.core.designsystem.component.FastScrollBar
import org.samesame.notes.core.designsystem.motion.MotionPopup
import org.samesame.notes.core.designsystem.motion.PopupMotion
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import org.samesame.notes.core.designsystem.component.AppFab
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.component.SelectionCircle
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.designsystem.theme.cappedSp
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.SortDirection
import org.samesame.notes.core.model.SortField
import org.samesame.notes.core.model.ViewMode
import org.samesame.notes.feature.lock.LockAuthFlow
import org.samesame.notes.feature.lock.LockFlowTransition
import org.samesame.notes.feature.lock.LockAuthMode
import org.samesame.notes.feature.lock.UnlockConfirmDialog

private enum class Page { Notes, ManageFolders, Search, SearchSettings, Move }

@Composable
fun NotesRoute(
    onOpenNote: (NoteId) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.Notes) }
    when (page) {
        Page.Notes -> NotesScreen(
            state = state,
            viewModel = viewModel,
            onOpenNote = onOpenNote,
            onOpenSettings = onOpenSettings,
            onOpenSearch = { page = Page.Search },
            onManageFolders = { page = Page.ManageFolders },
            onMove = { page = Page.Move },
        )
        Page.Move -> {
            BackHandler { page = Page.Notes }
            val scope = rememberCoroutineScope()
            MoveFolderScreen(
                folders = state.folders,
                rootNoteCount = state.rootNotesCount,
                onBack = { page = Page.Notes },
                onPick = { folder -> viewModel.moveSelectionTo(folder); page = Page.Notes },
                // A folder made here is top level; the user then taps it to move the notes in.
                onCreateFolder = { name, color -> scope.launch { viewModel.createFolderIn(name, color, null) } },
            )
        }
        Page.ManageFolders -> {
            BackHandler { page = Page.Notes }
            // Folders holding locked notes are trashed only after the identity check (L1); the ids wait here meanwhile.
            var verifyTrash by rememberSaveable { mutableStateOf<List<String>?>(null) }
            Box(Modifier.fillMaxSize()) {
                ManageFoldersScreen(
                    folders = state.folders,
                    rootNoteCount = state.rootNotesCount,
                    onBack = { page = Page.Notes },
                    onOpenFolder = { viewModel.selectFilter(NoteFilter.InFolder(it)); page = Page.Notes },
                    onOpenRoot = { viewModel.selectFilter(NoteFilter.Uncategorized); page = Page.Notes },
                    onCreateFolder = viewModel::createFolder,
                    onRenameFolder = { id, name -> viewModel.renameFolder(id, name) },
                    onTrashFolders = { ids, lockedCount ->
                        if (lockedCount > 0) verifyTrash = ids.map { it.value } else viewModel.trashFolders(ids)
                    },
                    lockedNotesIn = viewModel::lockedCountInFolders,
                )
                LockFlowTransition(verifyTrash) { ids ->
                    LockAuthFlow(
                        mode = LockAuthMode.VerifyOnly,
                        onCancel = { verifyTrash = null },
                        onToken = { token -> verifyTrash = null; viewModel.trashFolders(ids.map { org.samesame.notes.core.model.FolderId(it) }, token) },
                    )
                }
            }
        }
        Page.Search -> {
            BackHandler { page = Page.Notes }
            SearchScreen(onBack = { page = Page.Notes }, onOpenNote = onOpenNote, onSearchSettings = { page = Page.SearchSettings })
        }
        Page.SearchSettings -> {
            BackHandler { page = Page.Search }
            SearchSettingsScreen(onBack = { page = Page.Search })
        }
    }
}

private enum class MenuState { None, Main, View }

private enum class TransferKind { Share, Save }

private sealed interface LockAction {
    data object Lock : LockAction
    data object Unlock : LockAction
    data class Open(val id: NoteId) : LockAction

    /** Deletes that include locked notes (L1): the selection to the Trash, permanently, or the whole Recycle bin. */
    data object Trash : LockAction
    data object DeleteForever : LockAction
    data object EmptyTrash : LockAction
}

/** Keeps the open lock flow (and the note it acts on) across activity recreation. */
private val LockActionSaver = listSaver<LockAction?, String>(
    save = { action ->
        when (action) {
            null -> emptyList()
            LockAction.Lock -> listOf("lock")
            LockAction.Unlock -> listOf("unlock")
            is LockAction.Open -> listOf("open", action.id.value)
            LockAction.Trash -> listOf("trash")
            LockAction.DeleteForever -> listOf("deleteForever")
            LockAction.EmptyTrash -> listOf("emptyTrash")
        }
    },
    restore = { parts ->
        when (parts.firstOrNull()) {
            "lock" -> LockAction.Lock
            "unlock" -> LockAction.Unlock
            "open" -> parts.getOrNull(1)?.let { LockAction.Open(NoteId(it)) }
            "trash" -> LockAction.Trash
            "deleteForever" -> LockAction.DeleteForever
            "emptyTrash" -> LockAction.EmptyTrash
            else -> null
        }
    },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotesScreen(
    state: NotesUiState,
    viewModel: NotesViewModel,
    onOpenNote: (NoteId) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onManageFolders: () -> Unit,
    onMove: () -> Unit,
) {
    val drawerState = rememberPushDrawerState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun message(text: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current

    // Whether the notes list can scroll; when it cannot, the collapsing title stays/returns expanded.
    var contentScrollable by remember { mutableStateOf(true) }
    var menu by rememberSaveable { mutableStateOf(MenuState.None) }
    var sortMenu by rememberSaveable { mutableStateOf(false) }
    var moreMenu by rememberSaveable { mutableStateOf(false) }
    var confirmTrash by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteForever by rememberSaveable { mutableStateOf(false) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    // Lock flows: Lock / Unlock act on the selection; Open acts on the tapped locked note.
    var lockAction by rememberSaveable(stateSaver = LockActionSaver) { mutableStateOf<LockAction?>(null) }
    var confirmUnlock by rememberSaveable { mutableStateOf(false) }
    // Share / Save as file: the format popup, the save dialog, the running request and the "unlock first" notice.
    var shareMenu by rememberSaveable { mutableStateOf(false) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<TransferRequest?>(null) }
    var lockedNotice by rememberSaveable { mutableStateOf<TransferKind?>(null) }
    // PDF+: the PDFs the user picked, waiting for "one note or separate notes", and the import in progress.
    var pickedPdfs by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var importingPdf by remember { mutableStateOf(false) }
    val trashFolderTap = stringResource(R.string.feature_notes_toast_trash_folder_tap)
    val pdfFailed = stringResource(R.string.feature_notes_pdf_failed)
    val pdfSecured = stringResource(R.string.feature_notes_pdf_secured)
    val pdfTooMany = stringResource(R.string.feature_notes_pdf_too_many_pages)
    fun importPdfs(uris: List<android.net.Uri>, oneNote: Boolean) {
        importingPdf = true
        scope.launch {
            val outcome = try {
                val sources = withContext(Dispatchers.IO) { copyPdfsToCache(context, uris) }
                try {
                    viewModel.importPdfs(planPdfImports(sources, oneNote))
                } finally {
                    withContext(Dispatchers.IO) { sources.forEach { it.file.delete() } }
                }
            } finally {
                importingPdf = false
            }
            outcome.error?.let {
                message(when (it) {
                    org.samesame.notes.core.data.pdf.PdfImportError.PasswordProtected -> pdfSecured
                    org.samesame.notes.core.data.pdf.PdfImportError.TooManyPages -> pdfTooMany
                    else -> pdfFailed
                })
            }
            if (outcome.created.isEmpty() && outcome.error == null) message(pdfFailed)
            // A single new note opens straight away, like a freshly created one.
            if (outcome.created.size == 1) onOpenNote(outcome.created.single())
        }
    }
    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> importPdfs(uris, oneNote = true)
            else -> pickedPdfs = uris
        }
    }
    val selectedNotes = state.notes.filter { it.id in state.selection }
    val selectedFolders = (state.visibleFolders + state.trashFolders).filter { it.id in state.folderSelection }
    val selectedFolderHasChild = selectedFolders.any { hasContents(it, state.folders + state.trashedFolders) }
    val unlockedIds = selectedNotes.filter { !it.isLocked }.map { it.id }
    val lockedCount = selectedNotes.size - unlockedIds.size
    val moreItems = SelectionCounts(
        notes = selectedNotes.size, locked = lockedCount, favorites = selectedNotes.count(Note::isFavorite), folders = selectedFolders.size,
    ).moreMenuItems()
    // L1: locked notes the open delete dialog would remove (null while counting); such a delete verifies identity first.
    var deleteLockedCount by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(confirmTrash, confirmDeleteForever, confirmEmpty) {
        deleteLockedCount = null
        deleteLockedCount = when {
            confirmEmpty -> viewModel.lockedCountInTrash()
            confirmTrash || confirmDeleteForever -> viewModel.lockedCountInSelection()
            else -> null
        }
    }
    /** Share / Save as file start here: all-locked selections get the "unlock first" notice instead. */
    fun beginTransfer(kind: TransferKind) {
        if (unlockedIds.isEmpty()) lockedNotice = kind
        else if (kind == TransferKind.Share) shareMenu = true else saveDialog = true
    }

    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }
    BackHandler(enabled = !state.selecting && state.filter is NoteFilter.InFolder && drawerState.isClosed) {
        viewModel.navigateUpFromFolder()
    }

    val isTrash = state.isTrash
    val darkTheme = SameSameTheme.colors.isDark
    val noteCount = pluralStringResource(R.plurals.feature_notes_note_count, state.notes.size, state.notes.size)
    val subtitle = when (val s = listSubtitle(state)) {
        ListSubtitle.None -> null
        is ListSubtitle.Notes -> noteCount
        is ListSubtitle.FoldersAndNotes -> stringResource(
            R.string.feature_notes_folders_and_notes,
            pluralStringResource(R.plurals.feature_notes_folder_count, s.folders, s.folders),
            noteCount,
        )
    }
    val title = when {
        state.selecting && state.selectedCount == 0 -> stringResource(R.string.feature_notes_select_notes)
        state.selecting -> stringResource(R.string.feature_notes_selected_count, state.selectedCount)
        else -> when (val f = state.filter) {
            NoteFilter.All -> stringResource(R.string.feature_notes_all)
            NoteFilter.Uncategorized -> stringResource(R.string.feature_notes_folders)
            NoteFilter.Trash -> stringResource(R.string.feature_notes_trash)
            is NoteFilter.InFolder -> state.folders.firstOrNull { it.id == f.folderId }?.name.orEmpty()
            else -> stringResource(R.string.feature_notes_all)
        }
    }
    val isEmpty = state.notes.isEmpty() && state.visibleFolders.isEmpty() && state.trashFolders.isEmpty()

    Box(Modifier.fillMaxSize()) {
        AppPushDrawer(
            state = drawerState,
            drawerWidth = DrawerWidth,
            gesturesEnabled = !state.selecting,
            // The scroll bar's 24 dp touch strip keeps its own vertical drags.
            openSwipeEndExclusion = 24.dp,
            drawerContent = {
                fun close(action: () -> Unit) { action(); scope.launch { drawerState.close() } }
                NotesDrawer(
                    selected = state.filter,
                    allCount = state.allNotesCount,
                    foldersCount = state.rootNotesCount,
                    onSelectAll = { close { viewModel.selectFilter(NoteFilter.All) } },
                    onSelectTrash = { close { viewModel.selectFilter(NoteFilter.Trash) } },
                    onSelectFolders = { close { viewModel.selectFilter(NoteFilter.Uncategorized) } },
                    showFolderCards = state.preferences.showFolderCards,
                    onShowFolderCards = viewModel::setShowFolderCards,
                    onManageFolders = onManageFolders,
                    onOpenSettings = onOpenSettings,
                )
            },
        ) {
            val drawerButton: @Composable () -> Unit = {
                IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Menu, stringResource(R.string.feature_notes_open_drawer), tint = SameSameTheme.colors.textPrimary, modifier = Modifier.size(24.dp))
                }
            }
            val bottomBar: @Composable () -> Unit = {
                AnimatedVisibility(
                    visible = state.selecting,
                    enter = slideInVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90)) { it } +
                        expandVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90), expandFrom = Alignment.Bottom),
                    exit = slideOutVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90)) { it } +
                        shrinkVertically(tween(SameSameMotion.BottomBarMs, easing = SameSameMotion.SineInOut90), shrinkTowards = Alignment.Bottom),
                ) {
                    // The bar keeps its last content while it slides away (the selection is already cleared by then).
                    val hasSelection = state.selectedCount > 0
                    val hasNotes = state.selection.isNotEmpty()
                    if (isTrash) {
                        BottomActionBar(
                            listOf(
                                BottomBarItem(AppIcons.RestoreFromTrash, stringResource(R.string.feature_notes_action_restore), {
                                    val toast = restoreToast(state.folderSelection.size, state.selection.size)
                                    viewModel.restoreSelection()
                                    message(
                                        resources.getString(
                                            when (toast) {
                                                RestoreToast.Folder -> R.string.feature_notes_toast_folder_restored
                                                RestoreToast.Folders -> R.string.feature_notes_toast_folders_restored
                                                RestoreToast.Items -> R.string.feature_notes_toast_items_restored
                                                RestoreToast.Note -> R.string.feature_notes_toast_note_restored
                                                RestoreToast.Notes -> R.string.feature_notes_toast_notes_restored
                                            },
                                        ),
                                    )
                                }, hasSelection),
                                BottomBarItem(AppIcons.Trash, stringResource(R.string.feature_notes_action_delete), { confirmDeleteForever = true }, hasSelection),
                            ),
                        )
                    } else {
                        BottomActionBar(
                            listOf(
                                BottomBarItem(AppIcons.FolderMove, stringResource(R.string.feature_notes_action_move), onMove, hasNotes),
                                BottomBarItem(AppIcons.Share, stringResource(R.string.feature_notes_action_share), { beginTransfer(TransferKind.Share) }, hasNotes),
                                BottomBarItem(AppIcons.Trash, stringResource(R.string.feature_notes_action_delete), { confirmTrash = true }, hasSelection),
                                BottomBarItem(AppIcons.More, stringResource(R.string.feature_notes_action_more), { moreMenu = true }, hasNotes && moreItems.isNotEmpty()),
                            ),
                        )
                    }
                }
            }
            val fab: @Composable () -> Unit = {
                MotionPopup(visible = !state.selecting && !isTrash, motion = PopupMotion.Fab) {
                    AppFab(AppIcons.ComposeFab, stringResource(R.string.feature_notes_new_note), {
                        scope.launch { onOpenNote(viewModel.createNote()) }
                    })
                }
            }
            val body: @Composable () -> Unit = {
                when {
                    !state.loaded -> Unit
                    isEmpty -> EmptyState(
                        message = stringResource(R.string.feature_notes_empty_title),
                        hint = if (isTrash) null else stringResource(R.string.feature_notes_empty_hint),
                    )
                    else -> NotesGrid(
                        state = state,
                        onClickNote = { note ->
                            when {
                                state.selecting -> viewModel.toggleSelected(note)
                                isTrash -> viewModel.startSelecting(note)
                                note.isLocked -> lockAction = LockAction.Open(note.id)
                                else -> onOpenNote(note.id)
                            }
                        },
                        onLongClickNote = { note -> if (state.selecting) viewModel.toggleSelected(note) else viewModel.startSelecting(note) },
                        onOpenFolder = { id -> if (isTrash) message(trashFolderTap) else viewModel.selectFilter(NoteFilter.InFolder(id)) },
                        onToggleFolder = viewModel::toggleSelected,
                        onLongClickFolder = { folder -> if (state.selecting) viewModel.toggleSelected(folder) else viewModel.startSelecting(firstFolder = folder) },
                        sortMenu = sortMenu,
                        onSortMenu = { sortMenu = it },
                        onSortField = viewModel::setSortField,
                        onToggleDirection = viewModel::toggleSortDirection,
                        showSortRow = !isTrash,
                        onScrollableChange = { contentScrollable = it },
                        darkTheme = darkTheme,
                        previewFor = { viewModel.previewFor(it, darkTheme) },
                        requestPreview = { viewModel.requestPreview(it, darkTheme) },
                    )
                }
            }

            // Overflow menu content (shared by both header styles).
            // The menu's content while it plays its exit animation (the state is already back to None by then).
            var shownMenu by remember { mutableStateOf(MenuState.Main) }
            if (menu != MenuState.None) shownMenu = menu
            val overflow: @Composable () -> Unit = {
                Box {
                    // Narrower than the other toolbar buttons and inset 4.8dp more (measured on ref #02).
                    IconButton(onClick = { menu = MenuState.Main }, modifier = Modifier.padding(end = 4.8.dp).size(width = 32.dp, height = 40.dp)) {
                        Icon(AppIcons.More, stringResource(R.string.feature_notes_more_options), tint = SameSameTheme.colors.textPrimary)
                    }
                    AppDropdownMenu(expanded = menu != MenuState.None, onDismissRequest = { menu = MenuState.None }, offset = androidx.compose.ui.unit.DpOffset((-1.7).dp, (-46.1).dp)) {
                        if (isTrash) {
                            AppMenuItem(stringResource(R.string.feature_notes_trash_empty_action), { menu = MenuState.None; confirmEmpty = true })
                        } else if (shownMenu == MenuState.View) {
                            val mode = state.viewMode
                            listOf(
                                ViewMode.GridSmall to R.string.feature_notes_view_grid_small,
                                ViewMode.GridMedium to R.string.feature_notes_view_grid_medium,
                                ViewMode.GridLarge to R.string.feature_notes_view_grid_large,
                                ViewMode.List to R.string.feature_notes_view_list,
                                ViewMode.SimpleList to R.string.feature_notes_view_simple_list,
                            ).forEach { (m, label) ->
                                AppMenuItem(stringResource(label), { viewModel.setViewMode(m); menu = MenuState.None }, checked = m == mode)
                            }
                        } else {
                            AppMenuItem(stringResource(R.string.feature_notes_menu_edit), { menu = MenuState.None; viewModel.startSelecting() })
                            AppMenuItem(stringResource(R.string.feature_notes_menu_view), { menu = MenuState.View })
                            AppMenuItem(
                                stringResource(R.string.feature_notes_menu_pin_favorites),
                                { viewModel.setPinFavorites(!state.preferences.pinFavoritesToTop); menu = MenuState.None },
                                checked = if (state.preferences.pinFavoritesToTop) true else null,
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                if (isTrash) {
                    TrashLayout(
                        title = title, subtitle = subtitle, state = state, drawerButton = drawerButton,
                        overflow = overflow, bottomBar = bottomBar, body = body,
                        onEdit = { viewModel.startSelecting() },
                        selectAll = viewModel::toggleSelectAll,
                    )
                } else {
                    CollapsingHeaderScaffold(
                        title = title,
                        contentScrollable = !isEmpty && contentScrollable,
                        titleKey = state.selecting,
                        subtitle = if (state.selecting) null else subtitle,
                        navigationIcon = {
                            if (state.selecting) SelectAllControl(state, viewModel::toggleSelectAll) else drawerButton()
                        },
                        actions = {
                            if (!state.selecting) {
                                IconButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, modifier = Modifier.size(40.dp)) {
                                    Icon(AppIcons.PdfAdd, stringResource(R.string.feature_notes_import_pdf), tint = SameSameTheme.colors.textPrimary, modifier = Modifier.size(24.dp))
                                }
                                if (!isEmpty) {
                                    IconButton(onClick = onOpenSearch, modifier = Modifier.size(40.dp)) {
                                        Icon(AppIcons.Search, stringResource(R.string.feature_notes_search), tint = SameSameTheme.colors.textPrimary)
                                    }
                                    overflow()
                                }
                            }
                        },
                        floatingActionButton = fab,
                        bottomBar = bottomBar,
                        content = body,
                    )
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))

                // The Share format popup hangs off the bottom bar (Share is its second button).
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = 90.3.dp, start = 20.2.dp)) {
                    ShareFormatMenu(
                        expanded = shareMenu,
                        onDismiss = { shareMenu = false },
                        onPick = { format ->
                            shareMenu = false
                            if (lockedCount > 0) message(resources.getString(R.string.feature_notes_share_except_locked))
                            transfer = TransferRequest.Share(unlockedIds, format)
                        },
                    )
                }

                // The "More" menu hangs off the bottom bar's right side.
                // Ref #19: menu bottom 90.3dp above the screen bottom, right edge 20.2dp from the screen edge.
                Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 90.3.dp, end = 20.2.dp)) {
                    AppDropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        // Which items show follows the reference rules (SelectionMenu.kt): no Duplicate with a locked note, ...
                        moreItems.forEach { item ->
                            when (item) {
                                SelectionMenuItem.Lock -> AppMenuItem(stringResource(R.string.feature_notes_more_lock), { moreMenu = false; lockAction = LockAction.Lock })
                                SelectionMenuItem.Unlock -> AppMenuItem(stringResource(R.string.feature_notes_more_unlock), { moreMenu = false; confirmUnlock = true })
                                SelectionMenuItem.SaveAsFile -> AppMenuItem(stringResource(R.string.feature_notes_more_save_as_file), { moreMenu = false; beginTransfer(TransferKind.Save) })
                                SelectionMenuItem.Duplicate -> AppMenuItem(stringResource(R.string.feature_notes_more_duplicate), { moreMenu = false; viewModel.duplicateSelection() })
                                SelectionMenuItem.RemoveFavorite -> AppMenuItem(stringResource(R.string.feature_notes_more_remove_favorite), { moreMenu = false; viewModel.toggleSelectionFavorite() })
                                SelectionMenuItem.AddFavorite -> AppMenuItem(stringResource(R.string.feature_notes_more_add_favorite), { moreMenu = false; viewModel.toggleSelectionFavorite() })
                            }
                        }
                    }
                }
            }
        }
        // The password page slides in and out like the reference's lock activity.
        LockFlowTransition(lockAction) { action ->
            LockAuthFlow(
                mode = if (action is LockAction.Lock) LockAuthMode.VerifyOrCreate else LockAuthMode.VerifyOnly,
                onCancel = { lockAction = null },
                onToken = { token ->
                    lockAction = null
                    when (action) {
                        LockAction.Lock -> viewModel.lockSelection(token)
                        LockAction.Unlock -> viewModel.unlockSelection(token)
                        is LockAction.Open -> viewModel.openLocked(action.id, token) { onOpenNote(action.id) }
                        LockAction.Trash -> viewModel.moveSelectionToTrash(token)
                        LockAction.DeleteForever -> viewModel.deleteSelectionForever(token)
                        LockAction.EmptyTrash -> viewModel.emptyTrash(token)
                    }
                },
            )
        }
    }
    if (saveDialog) {
        SaveAsFileDialog(
            onDismiss = { saveDialog = false },
            onConfirm = { format ->
                saveDialog = false
                if (lockedCount > 0) message(resources.getString(R.string.feature_notes_save_except_locked))
                transfer = TransferRequest.Save(unlockedIds, format)
            },
        )
    }
    NoteTransferHost(
        request = transfer,
        onFinished = { if (transfer != null) { transfer = null; viewModel.clearSelection() } },
        onMessage = ::message,
    )
    lockedNotice?.let { kind ->
        AppDialog(
            onDismissRequest = { lockedNotice = null },
            dismissLabel = stringResource(R.string.feature_notes_cancel),
            confirmLabel = stringResource(R.string.feature_notes_remove_lock),
            onConfirm = { lockedNotice = null; lockAction = LockAction.Unlock },
            atBottom = true,
        ) {
            val plural = selectedNotes.size > 1
            Text(
                stringResource(
                    when {
                        kind == TransferKind.Share && plural -> R.string.feature_notes_share_locked_notes
                        kind == TransferKind.Share -> R.string.feature_notes_share_locked_note
                        plural -> R.string.feature_notes_save_locked_notes
                        else -> R.string.feature_notes_save_locked_note
                    },
                ),
                style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary,
            )
        }
    }
    if (pickedPdfs.size > 1) {
        AppDialog(
            onDismissRequest = { pickedPdfs = emptyList() },
            dismissLabel = stringResource(R.string.feature_notes_import_multi_pdf_separate),
            confirmLabel = stringResource(R.string.feature_notes_import_multi_pdf_one),
            onConfirm = { val uris = pickedPdfs; pickedPdfs = emptyList(); importPdfs(uris, oneNote = true) },
            onDismissClick = { val uris = pickedPdfs; pickedPdfs = emptyList(); importPdfs(uris, oneNote = false) },
            atBottom = true,
        ) {
            Text(stringResource(R.string.feature_notes_import_multi_pdf), style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary)
        }
    }
    if (importingPdf) BusyDialog(stringResource(R.string.feature_notes_importing_pdf))
    if (confirmUnlock) {
        UnlockConfirmDialog(
            count = state.notes.count { it.id in state.selection && it.isLocked },
            onConfirm = { confirmUnlock = false; lockAction = LockAction.Unlock },
            onDismiss = { confirmUnlock = false },
        )
    }

    val lockedToDelete = deleteLockedCount
    if (confirmTrash && lockedToDelete != null) {
        AppDialog(
            onDismissRequest = { confirmTrash = false },
            dismissLabel = stringResource(R.string.feature_notes_cancel),
            confirmLabel = stringResource(R.string.feature_notes_move_to_trash_confirm),
            onConfirm = { confirmTrash = false; if (lockedToDelete > 0) lockAction = LockAction.Trash else viewModel.moveSelectionToTrash() },
            atBottom = true,
        ) {
            val text = trashDialogText(selectedFolders.size, state.selection.size, selectedFolderHasChild)
            Text(
                stringResource(text.message, *text.args.toTypedArray()),
                style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary,
            )
            LockedDeleteNotice(lockedToDelete)
        }
    }
    if (confirmDeleteForever && lockedToDelete != null) {
        AppDialog(
            onDismissRequest = { confirmDeleteForever = false },
            dismissLabel = stringResource(R.string.feature_notes_cancel),
            confirmLabel = stringResource(R.string.feature_notes_delete_forever_confirm),
            onConfirm = {
                confirmDeleteForever = false
                if (lockedToDelete > 0) lockAction = LockAction.DeleteForever else viewModel.deleteSelectionForever()
            },
            atBottom = true,
        ) {
            val text = permanentDeleteDialogText(selectedFolders.size, state.selection.size, selectedFolderHasChild)
            Text(
                stringResource(text.message, *text.args.toTypedArray()),
                style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary,
            )
            LockedDeleteNotice(lockedToDelete)
        }
    }
    if (confirmEmpty && lockedToDelete != null) {
        AppDialog(
            onDismissRequest = { confirmEmpty = false },
            dismissLabel = stringResource(R.string.feature_notes_cancel),
            confirmLabel = stringResource(R.string.feature_notes_trash_empty_action),
            onConfirm = { confirmEmpty = false; if (lockedToDelete > 0) lockAction = LockAction.EmptyTrash else viewModel.emptyTrash() },
            atBottom = true,
        ) {
            Text(stringResource(R.string.feature_notes_trash_empty_title), style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary)
            LockedDeleteNotice(lockedToDelete)
        }
    }
}

/** "All" select-all circle with its label, shown at the start of the toolbar in selection mode. */
@Composable
private fun SelectAllControl(state: NotesUiState, onClick: () -> Unit) {
    val all = state.allSelected
    Column(
        // Ref #17: circle 20.6dp centred 33.7dp from the edge, 22px above the toolbar centre; label 10.7sp.
        Modifier.padding(start = 11.4.dp).offset(y = 3.2.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.4.dp), // r8: ref label centre 65px below the circle centre
    ) {
        SelectionCircle(all, size = 20.6.dp)
        Text(stringResource(R.string.feature_notes_select_all), fontSize = cappedSp(11f), lineHeight = cappedSp(14f), color = SameSameTheme.colors.textPrimary)
    }
}

/** The Recycle bin uses the compact toolbar: title + count at the start, "Edit" and the overflow menu. */
@Composable
private fun TrashLayout(
    title: String,
    subtitle: String?,
    state: NotesUiState,
    drawerButton: @Composable () -> Unit,
    overflow: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    body: @Composable () -> Unit,
    onEdit: () -> Unit,
    selectAll: () -> Unit,
) {
    val colors = SameSameTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        SimpleTopBar(
            title = {
                Column {
                    Text(title, style = SameSameText.SearchTitle.copy(fontSize = 21.sp, lineHeight = 26.sp).capped(), color = colors.textPrimary)
                    if (subtitle != null) Text(subtitle, fontSize = fixedSp(12.2f), lineHeight = fixedSp(16f), color = colors.textSecondary)
                }
            },
            navigationIcon = { if (state.selecting) SelectAllControl(state, selectAll) else drawerButton() },
            actions = {
                if (!state.selecting) {
                    if (state.notes.isNotEmpty() || state.trashFolders.isNotEmpty()) {
                        Text(
                            stringResource(R.string.feature_notes_menu_edit), style = SameSameText.Button.copy(fontSize = 17.sp).capped(),
                            color = colors.textPrimary, modifier = Modifier.clickable(onClick = onEdit).padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        )
                        overflow()
                    }
                }
            },
        )
        Box(Modifier.weight(1f)) { body() }
        bottomBar()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesGrid(
    state: NotesUiState,
    onClickNote: (Note) -> Unit,
    onLongClickNote: (Note) -> Unit,
    onOpenFolder: (org.samesame.notes.core.model.FolderId) -> Unit,
    onToggleFolder: (org.samesame.notes.core.model.Folder) -> Unit,
    onLongClickFolder: (org.samesame.notes.core.model.Folder) -> Unit,
    sortMenu: Boolean,
    onSortMenu: (Boolean) -> Unit,
    onSortField: (SortField) -> Unit,
    onToggleDirection: () -> Unit,
    showSortRow: Boolean,
    onScrollableChange: (Boolean) -> Unit,
    darkTheme: Boolean,
    previewFor: (Note) -> ImageBitmap?,
    requestPreview: (Note) -> PreviewTicket?,
) {
    val mode = state.viewMode
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val columns = notesGridColumns(
        mode, config.screenWidthDp, config.smallestScreenWidthDp,
        landscape = config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE,
    )
    val now = remember { System.currentTimeMillis() }
    val verticalGap = when (mode) {
        ViewMode.List -> 25.1.dp
        ViewMode.SimpleList -> 0.dp
        else -> 36.dp
    }
    val gridState = rememberLazyGridState()
    // Per-item heights differ per view mode, so the estimate restarts when the mode changes.
    val scrollEstimate = remember(mode) { ScrollEstimateHolder() }
    // Full-span items above the notes (in DSL order): each is one row of the scroll bar's row model.
    val headerItems =
        (if (state.isTrash) 1 else 0) +
            (if (state.trashFolders.isNotEmpty()) 1 else 0) +
            (if (state.visibleFolders.isNotEmpty()) 1 else 0) +
            (if (showSortRow) 1 else 0)
    // Item animation specs are built once, not per card and per recomposition.
    val placementSpec = remember { tween<androidx.compose.ui.unit.IntOffset>(SameSameMotion.ListReflowMs, easing = SameSameMotion.SineInOut90) }
    val fadeOutSpec = remember { tween<Float>(SameSameMotion.FabMs, easing = SameSameMotion.SineInOut80) }
    // Derived: the list host recomposes when scrollability flips, not on every scroll frame.
    val scrollable by remember(gridState) { derivedStateOf { gridState.canScrollBackward || gridState.canScrollForward } }
    LaunchedEffect(scrollable) { onScrollableChange(scrollable) }
    // Stable per-note click lambdas: a new screen-level lambda (it captures the UI state) must not recompose every card.
    val latestClick by rememberUpdatedState(onClickNote)
    val latestLongClick by rememberUpdatedState(onLongClickNote)
    val latestRequest by rememberUpdatedState(requestPreview)
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = 18.3.dp, end = 18.3.dp, bottom = 110.dp - verticalGap),
        horizontalArrangement = Arrangement.spacedBy(18.3.dp),
        // Row gaps are applied per item (see below) so the sort row can sit closer to the first card.
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.isTrash) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.feature_notes_trash_hint), fontSize = cappedSp(17f), lineHeight = cappedSp(23f),
                    // Ref #24: first glyph row 22dp below the top bar, text 24dp from the edge, card 35dp below the last line.
                    color = SameSameTheme.colors.textPrimary, modifier = Modifier.padding(start = 5.7.dp, top = 18.9.dp, bottom = 35.dp),
                )
            }
        }
        if (state.trashFolders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FolderCardRow(
                    folders = state.trashFolders, state = state,
                    onOpenFolder = onOpenFolder, onToggleFolder = onToggleFolder, onLongClickFolder = onLongClickFolder,
                    modifier = Modifier.padding(top = 4.dp, bottom = verticalGap),
                )
            }
        }
        if (state.visibleFolders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                FolderCardRow(
                    folders = state.visibleFolders, state = state,
                    onOpenFolder = onOpenFolder, onToggleFolder = onToggleFolder, onLongClickFolder = onLongClickFolder,
                    modifier = Modifier.padding(top = 4.dp, bottom = verticalGap),
                )
            }
        }
        if (showSortRow) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // the reference leaves 18dp between the sort bar and the first card row.
                Box(Modifier.padding(bottom = if (mode == ViewMode.SimpleList) 13.7.dp else 18.dp)) { SortRow(state, sortMenu, onSortMenu, onSortField, onToggleDirection) }
            }
        }
        items(state.notes, key = { it.id.value }, contentType = { "note" }) { note ->
            val selected = note.id in state.selection
            val daysLeft = if (state.isTrash) note.daysLeftInTrash(now) else null
            val click = remember(note) { { latestClick(note) } }
            val long = remember(note) { { latestLongClick(note) } }
            Box(
                Modifier.padding(bottom = verticalGap)
                    // Bounds changes (removal, re-sort) glide like the reference list's 500 ms change-bounds.
                    .animateItem(fadeInSpec = null, placementSpec = placementSpec, fadeOutSpec = fadeOutSpec),
            ) {
                when (mode) {
                    // List view keeps the text preview (reference layout): no page render is requested.
                    ViewMode.List -> NoteListItem(note, state.selecting, selected, click, long, daysLeft = daysLeft)
                    else -> {
                        // Renders for composed (visible) cards only; the text preview stays until the bitmap lands.
                        // The lock state is part of the key so removing a lock asks for the preview again.
                        // A card scrolled away before its render started drops its request (a fling through 100 notes
                        // must not queue 100 renders ahead of the cards now on screen).
                        LaunchedEffect(note.id, note.modifiedAt, note.isLocked, darkTheme) {
                            val ticket = latestRequest(note)
                            try {
                                awaitCancellation()
                            } finally {
                                ticket?.release()
                            }
                        }
                        val preview = previewFor(note)
                        if (mode == ViewMode.SimpleList) {
                            NoteSimpleItem(note, state.selecting, selected, click, long, daysLeft = daysLeft, preview = preview)
                        } else {
                            NoteGridItem(note, mode, state.selecting, selected, click, long, daysLeft = daysLeft, preview = preview)
                        }
                    }
                }
            }
        }
    }
        // topMargin keeps the thumb (and its 48 dp touch zone) below the sort row at the top of the list, so the sort
        // arrow is never under the bar's touch target.
        GridScrollBar(gridState, scrollEstimate, columns, headerItems, topMargin = 36.dp)
    }
}

/**
 * The scroll bar of the home lists (notes, folders, trash, search results; list and grid views): the shared
 * [FastScrollBar], the same thumb and motion as the editor's, draggable at every list size from the thumb or the 48 dp
 * edge strip (relative drag). The grid's layout info is read only inside a derived state that the bar reads in its
 * layout / draw phases, so scrolling recomposes nothing (not the list host, not this composable): only the thumb moves.
 */
@Composable
internal fun GridScrollBar(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    scrollEstimate: ScrollEstimateHolder,
    columns: Int,
    headerItems: Int,
    topMargin: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    val metrics = remember(gridState, scrollEstimate, columns, headerItems) {
        derivedStateOf { gridMetrics(gridState.layoutInfo, columns, headerItems, scrollEstimate) }
    }
    val scrubJob = remember { arrayOfNulls<Job>(1) }
    FastScrollBar(
        scrollFraction = { metrics.value.scrollFraction },
        onScrub = { f ->
            scrubJob[0]?.cancel()
            val (index, within) = lazyScrollTarget(metrics.value, f)
            // UNDISPATCHED: the snap lands before this frame's layout, so the thumb's hand-back at the end of the
            // drag reads the position the drag asked for instead of a frame-old one.
            scrubJob[0] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                gridState.scrollToItem(index.coerceAtLeast(0), within)
            }
        },
        needed = { metrics.value.thumbFraction < 0.999f },
        edgeStrip = true,
        showOnStart = false,
        topMargin = topMargin,
        accentColor = SameSameTheme.colors.accent,
    )
}

private fun gridMetrics(
    info: androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo,
    columns: Int,
    headerItems: Int,
    scrollEstimate: ScrollEstimateHolder,
): LazyScrollMetrics {
    val visible = info.visibleItemsInfo
    if (visible.isEmpty() || info.totalItemsCount == 0) return LazyScrollMetrics()
    // Row geometry from the laid-out items: one row per distinct row index, advance = top of the next row.
    val rows = gridRows(
        visible.map { GridItemPosition(it.index, it.offset.y, it.size.height) },
        columns,
        headerItems,
    )
    val estimate = scrollEstimate.estimateFor(info.totalItemsCount, columns, headerItems, measuredRowAdvance(rows, headerItems))
    return lazyScrollMetrics(
        itemCount = info.totalItemsCount,
        columns = columns,
        headerItems = headerItems,
        rows = rows,
        viewportExtent = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
        estimatedRowAdvance = estimate,
        knownAdvances = scrollEstimate.advancesWith(rows),
    )
}

/** Folder cards above the notes, selectable in selection mode like the notes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderCardRow(
    folders: List<Folder>,
    state: NotesUiState,
    onOpenFolder: (org.samesame.notes.core.model.FolderId) -> Unit,
    onToggleFolder: (Folder) -> Unit,
    onLongClickFolder: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        folders.forEach { folder ->
            FolderCard(
                folder = folder,
                selecting = state.selecting,
                selected = folder.id in state.folderSelection,
                onClick = { if (state.selecting) onToggleFolder(folder) else onOpenFolder(folder.id) },
                onLongClick = { if (state.selecting) onToggleFolder(folder) else onLongClickFolder(folder) },
            )
        }
    }
}

/** "Sort glyph + field | direction arrow", right-aligned above the notes. */
@Composable
private fun SortRow(
    state: NotesUiState,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onField: (SortField) -> Unit,
    onToggleDirection: () -> Unit,
) {
    val colors = SameSameTheme.colors
    val field = state.preferences.sortField
    val label = stringResource(
        when (field) {
            SortField.Title -> R.string.feature_notes_sort_title
            SortField.DateCreated -> R.string.feature_notes_sort_created
            else -> R.string.feature_notes_sort_modified
        },
    )
    // Measured on ref #02 (px / 2.625): icon centre 229.3dp, text starts 3.8dp after the 24dp icon box,
    // divider 14.9dp after the text, arrow box 13.4dp after the divider, arrow centre 31.6dp from the edge.
    Box(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().padding(top = 5.5.dp, end = 0.6.dp).height(26.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.clickable { onMenu(true) }, verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Sort, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp)) // sort_bar_icon_size
            Text(label, fontSize = cappedSp(13f), color = colors.textTertiary, modifier = Modifier.padding(start = 6.dp)) // icon margin_end 6dp
        }
        Box(Modifier.padding(start = 14.9.dp).width(1.dp).height(16.dp).background(Color(0xFFE8E8E8)))
        // The arrow's touch target is 38 x 26 dp (the drawn 18 dp glyph sits where it always did: 12 dp after the divider,
        // 4 dp padding), not the bare 26 dp box, so it is easy to hit and does not depend on the scroll bar's strip.
        Box(
            Modifier.width(38.dp).fillMaxHeight().clickable(onClick = onToggleDirection).padding(end = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(
                if (state.preferences.sortDirection == SortDirection.Descending) AppIcons.ArrowDown else AppIcons.ArrowUp,
                stringResource(R.string.feature_notes_sort_direction), tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    // Ref #04: the sort menu is right-aligned 10.3dp from the screen edge, its top 9.9dp below the row top.
    Box(Modifier.align(Alignment.TopEnd)) {
        AppDropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }, offset = androidx.compose.ui.unit.DpOffset((-8.4).dp, 9.9.dp), motion = PopupMotion.DropdownList) {
            listOf(
                SortField.Title to R.string.feature_notes_sort_title,
                SortField.DateCreated to R.string.feature_notes_sort_created,
                SortField.DateModified to R.string.feature_notes_sort_modified,
            ).forEach { (f, res) ->
                AppMenuItem(stringResource(res), { onField(f); onMenu(false) }, checked = f == field)
            }
        }
    }
    }
}
