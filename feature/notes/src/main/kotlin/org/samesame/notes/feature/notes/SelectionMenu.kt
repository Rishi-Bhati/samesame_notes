package org.samesame.notes.feature.notes

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped

/** The items of the selection bar's More menu, in menu order. */
internal enum class SelectionMenuItem { Lock, Unlock, SaveAsFile, Duplicate, RemoveFavorite, AddFavorite }

/** What is selected, as far as the More menu cares. */
internal data class SelectionCounts(
    val notes: Int,
    val locked: Int = 0,
    val favorites: Int = 0,
    val folders: Int = 0,
)

/**
 * The More menu rules (reference behaviour, our own code):
 * - Lock while any selected note is unlocked; Unlock when every selected note is locked.
 * - Duplicate is hidden as soon as one locked note is selected (a locked note is never copied).
 * - "Remove from favourites" when every selected note is a favourite, otherwise "Add to favourites".
 * - Save as file stays for locked notes (they are skipped with a notice, or the "unlock first" dialog shows).
 * - With a folder in the selection only folder actions apply: none of these items is shown.
 */
internal fun SelectionCounts.canShow(item: SelectionMenuItem): Boolean {
    if (notes <= 0 || folders > 0) return false
    val allLocked = locked >= notes
    val allFavorite = favorites >= notes
    return when (item) {
        SelectionMenuItem.Lock -> !allLocked
        SelectionMenuItem.Unlock -> allLocked
        SelectionMenuItem.SaveAsFile -> true
        SelectionMenuItem.Duplicate -> locked == 0
        SelectionMenuItem.RemoveFavorite -> allFavorite
        SelectionMenuItem.AddFavorite -> !allFavorite
    }
}

/** The visible More items, in order; empty means the More button has nothing to offer. */
internal fun SelectionCounts.moreMenuItems(): List<SelectionMenuItem> = SelectionMenuItem.entries.filter { canShow(it) }

/** The extra delete-dialog line when [lockedCount] locked notes would be deleted too; nothing when there are none. */
@Composable
internal fun LockedDeleteNotice(lockedCount: Int) {
    if (lockedCount <= 0) return
    Text(
        pluralStringResource(R.plurals.feature_notes_delete_includes_locked, lockedCount, lockedCount),
        style = SameSameText.DialogMessage.capped(), color = SameSameTheme.colors.textPrimary,
        modifier = Modifier.padding(top = 12.dp),
    )
}
