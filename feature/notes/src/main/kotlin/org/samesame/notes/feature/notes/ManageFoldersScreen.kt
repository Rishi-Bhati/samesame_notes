package org.samesame.notes.feature.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.component.BottomActionBar
import org.samesame.notes.core.designsystem.component.BottomBarItem
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.component.SelectionCircle
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.FolderColors
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.designsystem.theme.cappedSp
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId

@Composable
internal fun ManageFoldersScreen(
    folders: List<Folder>,
    /** Top-level folders: the Folders row counts folders, not the notes without one; null when there are none. */
    rootFolderCount: Int?,
    onBack: () -> Unit,
    onOpenFolder: (FolderId) -> Unit,
    onOpenRoot: () -> Unit,
    /** Create under [parent] (null = top level): the bottom row (null) and "Create subfolder" for the selected folder. */
    onCreateFolder: (name: String, color: Long?, parent: FolderId?) -> Unit,
    onRenameFolder: (FolderId, String) -> Unit = { _, _ -> },
    /** Trashes the folders; the count is the locked notes inside them (more than zero: verify identity first). */
    onTrashFolders: (List<FolderId>, lockedCount: Int) -> Unit = { _, _ -> },
    /** Locked notes inside the folders' subtrees, for the delete dialog. */
    lockedNotesIn: suspend (List<FolderId>) -> Int = { 0 },
) {
    val colors = SameSameTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val tooDeep = stringResource(R.string.feature_notes_subfolder_too_deep)
    var creating by remember { mutableStateOf(false) }
    // Edit mode (ref #16 "Edit" action): tapping a folder selects it; the bottom bar creates a subfolder, deletes or renames.
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<FolderId>>(emptySet()) }
    var renaming by remember { mutableStateOf<Folder?>(null) }
    var subfolderParent by remember { mutableStateOf<Folder?>(null) }
    var confirmTrash by remember { mutableStateOf(false) }
    val selectedFolders = folders.filter { it.id in selected }
    val displayed = orderedFolders(folders)
    fun leaveEdit() { editing = false; selected = emptySet() }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        SimpleTopBar(
            title = { Text(stringResource(R.string.feature_notes_manage_folders), style = SameSameText.SearchTitle.capped(), color = colors.textPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_notes_back), tint = colors.textPrimary)
                }
            },
            actions = {
                if (folders.isNotEmpty()) {
                    Text(
                        stringResource(if (editing) R.string.feature_notes_done else R.string.feature_notes_menu_edit),
                        style = SameSameText.Button.copy(fontSize = 17.sp).capped(), color = colors.textPrimary,
                        modifier = Modifier.clickable { if (editing) leaveEdit() else editing = true }.padding(start = 10.dp, end = 8.6.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
            },
        )
        Column(
            Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(26.dp)),
        ) {
            ManageRow(
                icon = {
                    Icon(AppIcons.Folder, null, tint = colors.iconMuted, modifier = Modifier.size(24.dp))
                },
                label = stringResource(R.string.feature_notes_folders), count = rootFolderCount, onClick = onOpenRoot,
            )
            RowDivider()
            displayed.forEach { folder ->
                ManageRow(
                    icon = {
                        Icon(AppIcons.Folder, null, tint = folder.color?.let { Color(it.toInt()) } ?: colors.iconMuted, modifier = Modifier.size(24.dp))
                    },
                    label = folder.name, count = if (editing) null else folder.noteCount,
                    onClick = { if (editing) selected = selected.toggle(folder.id) else onOpenFolder(folder.id) },
                    onLongClick = if (editing) null else ({ editing = true; selected = setOf(folder.id) }),
                    indent = ((folder.depth + 1) * 18).dp,
                    selected = if (editing) folder.id in selected else null,
                )
                RowDivider()
            }
            ManageRow(
                icon = { Icon(AppIcons.Add, null, tint = colors.positive, modifier = Modifier.size(28.dp).offset(x = 1.7.dp)) },
                label = stringResource(R.string.feature_notes_create_folder), count = null, onClick = { creating = true },
                labelGap = 21.8.dp,
            )
        }
        if (editing) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            BottomActionBar(
                listOf(
                    BottomBarItem(
                        AppIcons.Add, stringResource(R.string.feature_notes_create_subfolder),
                        {
                            selectedFolders.singleOrNull()?.let { folder ->
                                // At MAX_FOLDER_DEPTH the menu item stays visible but refuses with a toast, like the reference.
                                if (canCreateSubfolder(folder.depth)) subfolderParent = folder
                                else android.widget.Toast.makeText(context, tooDeep, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        selectedFolders.size == 1,
                    ),
                    BottomBarItem(
                        AppIcons.EditSquare, stringResource(R.string.feature_notes_rename_folder),
                        { selectedFolders.singleOrNull()?.let { renaming = it } },
                        selectedFolders.size == 1,
                    ),
                    BottomBarItem(
                        AppIcons.Trash, stringResource(R.string.feature_notes_action_delete),
                        { confirmTrash = true }, selectedFolders.isNotEmpty(),
                    ),
                ),
            )
        }
    }
    // Locked notes the delete would take along (null while counting: the dialog waits for it).
    var lockedToDelete by remember { mutableStateOf<Int?>(null) }
    androidx.compose.runtime.LaunchedEffect(confirmTrash) {
        lockedToDelete = null
        if (confirmTrash) lockedToDelete = lockedNotesIn(selectedFolders.map { it.id })
    }
    val lockedCount = lockedToDelete
    if (confirmTrash && lockedCount != null) {
        val text = trashDialogText(
            selectedFolders.size, 0,
            selectedFolders.any { hasContents(it, folders) },
        )
        AppDialog(
            onDismissRequest = { confirmTrash = false },
            dismissLabel = stringResource(R.string.feature_notes_cancel),
            confirmLabel = stringResource(R.string.feature_notes_move_to_trash_confirm),
            onConfirm = {
                confirmTrash = false
                val ids = selectedFolders.map { it.id }
                selected = emptySet()
                onTrashFolders(ids, lockedCount)
            },
            atBottom = true,
        ) {
            Text(stringResource(text.message, *text.args.toTypedArray()), style = SameSameText.DialogMessage.capped(), color = colors.textPrimary)
            LockedDeleteNotice(lockedCount)
        }
    }
    renaming?.let { f ->
        CreateFolderDialog(
            defaultName = f.name, onDismiss = { renaming = null },
            onCreate = { name, _ -> renaming = null; onRenameFolder(f.id, name) },
            title = stringResource(R.string.feature_notes_rename_folder), confirmLabel = stringResource(R.string.feature_notes_done),
            showColours = false,
        )
    }
    if (creating) {
        CreateFolderDialog(
            defaultName = stringResource(R.string.feature_notes_folder_default_name, folders.size + 1),
            onDismiss = { creating = false },
            onCreate = { name, color -> creating = false; onCreateFolder(name, color, null) },
        )
    }
    subfolderParent?.let { parent ->
        CreateFolderDialog(
            defaultName = stringResource(R.string.feature_notes_folder_default_name, folders.size + 1),
            onDismiss = { subfolderParent = null },
            onCreate = { name, color -> subfolderParent = null; onCreateFolder(name, color, parent.id) },
            title = stringResource(R.string.feature_notes_create_subfolder),
        )
    }
}

/** Ref #13: the 1px divider (#343434) sits on the last pixel row of each row, 20dp inset. */
@Composable
internal fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(0.dp)) {
        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).offset(y = (-1).dp).background(SameSameTheme.colors.pill))
    }
}

/** The small up/down chevron pair shown at the end of sub-folder rows while reordering. */
@Composable
private fun ReorderGlyph() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(AppIcons.ChevronUp, null, tint = SameSameTheme.colors.textTertiary, modifier = Modifier.size(16.dp))
        Icon(AppIcons.ChevronDown, null, tint = SameSameTheme.colors.textTertiary, modifier = Modifier.size(16.dp).offset(y = (-6).dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ManageRow(
    icon: @Composable () -> Unit,
    label: String,
    count: Int?,
    onClick: () -> Unit,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
    labelGap: androidx.compose.ui.unit.Dp = 14.6.dp,
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().height(55.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 34.dp + indent, end = 55.4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            SelectionCircle(selected, Modifier.padding(end = 12.dp).size(22.dp))
        }
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = cappedSp(17f), color = colors.textPrimary, modifier = Modifier.weight(1f).padding(start = labelGap))
        if (count != null) Text(count.toString(), fontSize = 13.sp, color = colors.textTertiary)
        trailing?.invoke()
    }
}

private fun Set<FolderId>.toggle(id: FolderId): Set<FolderId> = if (id in this) this - id else this + id

/** Name field plus 12 colour swatches; the first swatch means "no colour". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CreateFolderDialog(
    defaultName: String, onDismiss: () -> Unit, onCreate: (String, Long?) -> Unit,
    title: String = stringResource(R.string.feature_notes_create_folder),
    confirmLabel: String = stringResource(R.string.feature_notes_folder_add),
    showColours: Boolean = true,
) {
    val colors = SameSameTheme.colors
    var name by remember { mutableStateOf(TextFieldValue(defaultName, TextRange(0, defaultName.length))) }
    var colorIndex by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        // The IME restart collapses the initial selection; select the whole default name once focused (ref #14).
        kotlinx.coroutines.delay(150)
        name = name.copy(selection = TextRange(0, name.text.length))
    }

    AppDialog(
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.feature_notes_cancel),
        confirmLabel = confirmLabel,
        confirmEnabled = name.text.isNotBlank(),
        onConfirm = { onCreate(name.text, if (colorIndex == 0) null else FolderColors[colorIndex].toArgb().toLong() and 0xFFFFFFFFL) },
        title = title,
        atBottom = true,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                androidx.compose.foundation.text.selection.TextSelectionColors(Color(0xFF4C6973), Color(0x994C6973)),
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
        }
        if (showColours) {
        Row(Modifier.fillMaxWidth().padding(top = 19.6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.feature_notes_folder_colour), fontSize = 13.sp, color = colors.textTertiary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            DottedDivider(Modifier.weight(1f).padding(start = 16.dp))
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.6.dp, bottom = 2.7.dp),
            maxItemsInEachRow = 6,
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(15.9.dp),
        ) {
            FolderColors.forEachIndexed { i, c ->
                Box(
                    Modifier.size(34.dp).background(c, CircleShape).clickable { colorIndex = i },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == colorIndex) Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }
        }
    }
}
