package org.samesame.notes.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.FolderId

/**
 * "Select folder" (Move action): the folder tree as in Manage folders, "Folders" (top level) first, a "Create folder"
 * row last. Tapping a row moves the selected notes there. Rows, indent and spacing are the Manage folders ones.
 */
@Composable
internal fun MoveFolderScreen(
    folders: List<Folder>,
    /** Top-level folders: the Folders row counts folders, not the notes without one; null when there are none. */
    rootFolderCount: Int?,
    onBack: () -> Unit,
    onPick: (FolderId?) -> Unit,
    onCreateFolder: (name: String, color: Long?) -> Unit,
) {
    val colors = SameSameTheme.colors
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        SimpleTopBar(
            title = { Text(stringResource(R.string.feature_notes_select_folder), style = SameSameText.SearchTitle.capped(), color = colors.textPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_notes_back), tint = colors.textPrimary)
                }
            },
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).background(colors.card, RoundedCornerShape(26.dp))) {
            ManageRow(
                icon = { Icon(AppIcons.Folder, null, tint = colors.iconMuted, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.feature_notes_folders), count = rootFolderCount, onClick = { onPick(null) },
            )
            RowDivider()
            folders.forEach { folder ->
                ManageRow(
                    icon = { Icon(AppIcons.Folder, null, tint = folder.color?.let { Color(it.toInt()) } ?: colors.iconMuted, modifier = Modifier.size(24.dp)) },
                    label = folder.name, count = folder.noteCount, onClick = { onPick(folder.id) },
                    indent = ((folder.depth + 1) * 18).dp,
                )
                RowDivider()
            }
            ManageRow(
                icon = { Icon(AppIcons.Add, null, tint = colors.positive, modifier = Modifier.size(28.dp).offset(x = 1.7.dp)) },
                label = stringResource(R.string.feature_notes_create_folder), count = null, onClick = { creating = true },
                labelGap = 21.8.dp,
            )
        }
    }
    if (creating) {
        CreateFolderDialog(
            defaultName = stringResource(R.string.feature_notes_folder_default_name, folders.size + 1),
            onDismiss = { creating = false },
            onCreate = { name, color -> creating = false; onCreateFolder(name, color) },
        )
    }
}
