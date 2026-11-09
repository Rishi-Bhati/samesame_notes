package org.samesame.notes.feature.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.component.AppPillButton
import org.samesame.notes.core.designsystem.component.AppSwitch
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.NoteFilter

val DrawerWidth = 347.dp

@Composable
internal fun NotesDrawer(
    selected: NoteFilter,
    allCount: Int,
    foldersCount: Int,
    favoritesCount: Int = 0,
    lockedCount: Int = 0,
    onSelectAll: () -> Unit,
    onSelectTrash: () -> Unit,
    onSelectFolders: () -> Unit,
    onSelectFavorites: () -> Unit = {},
    onSelectLocked: () -> Unit = {},
    showFolderCards: Boolean,
    onShowFolderCards: (Boolean) -> Unit,
    onManageFolders: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = SameSameTheme.colors
    Box(
        Modifier.fillMaxHeight().width(DrawerWidth)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(top = 16.dp),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .background(colors.card, RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                .padding(start = 8.dp, end = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(end = 8.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(AppIcons.Settings, stringResource(R.string.feature_notes_settings), tint = colors.iconMuted)
                }
            }
            DrawerRow(AppIcons.Notes, stringResource(R.string.feature_notes_all), allCount, selected == NoteFilter.All, onSelectAll)
            if (favoritesCount > 0) {
                DrawerRow(AppIcons.Star, stringResource(R.string.feature_notes_favorites), favoritesCount, selected == NoteFilter.Favorites, onSelectFavorites)
            }
            if (lockedCount > 0) {
                DrawerRow(AppIcons.Lock, stringResource(R.string.feature_notes_locked), lockedCount, selected == NoteFilter.Locked, onSelectLocked)
            }
            DrawerRow(AppIcons.Trash, stringResource(R.string.feature_notes_trash), null, selected == NoteFilter.Trash, onSelectTrash)
            // Ref #11: divider centre 17.1dp below the Recycle bin row, Folders row starts 1.9dp below it.
            DottedDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.1.dp, bottom = 0.9.dp))
            DrawerRow(
                AppIcons.Folder, stringResource(R.string.feature_notes_folders), foldersCount,
                selected == NoteFilter.Uncategorized || selected is NoteFilter.InFolder, onSelectFolders,
            )
            ShowFoldersRow(checked = showFolderCards, onCheckedChange = onShowFolderCards)
            Spacer(Modifier.height(12.dp))
            AppPillButton(
                stringResource(R.string.feature_notes_manage_folders), onManageFolders,
                Modifier.align(Alignment.CenterHorizontally).width(267.dp),
            )
        }
    }
}

@Composable
private fun DrawerRow(icon: ImageVector, label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(55.dp)
            .background(if (selected) colors.elevated else colors.card, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(start = 23.4.dp, end = 21.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.icon, modifier = Modifier.size(24.dp))
        Text(
            label, fontSize = 18.sp, color = colors.textPrimary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f).padding(start = 15.dp),
        )
        if (count != null) Text(count.toString(), fontSize = 13.sp, color = colors.textTertiary)
    }
}

/** Drawer switch for the folder cards in All notes and inside folders. Tapping the row toggles it; the drawer stays open. */
@Composable
private fun ShowFoldersRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(55.dp)
            .background(colors.card, RoundedCornerShape(8.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(start = 23.4.dp, end = 21.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Same column as the other rows' labels (icon 24dp + 15dp gap), so the label lines up.
        Spacer(Modifier.width(39.dp))
        Text(
            stringResource(R.string.feature_notes_show_folders), fontSize = 18.sp, color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked, null)
    }
}

@Composable
internal fun DottedDivider(modifier: Modifier = Modifier) {
    val color = SameSameTheme.colors.checkOutline
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(
            color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
            strokeWidth = 2.dp.toPx() * 0.8f, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
        )
    }
}
