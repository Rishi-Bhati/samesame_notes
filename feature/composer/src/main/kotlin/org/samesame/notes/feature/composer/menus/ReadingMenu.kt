package org.samesame.notes.feature.composer.menus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.feature.composer.R

enum class ReadingAction { PageSorter, PageView, FullScreen, Tags, SaveAsFile, Print, Favourite, Lock, Share, Delete }

/**
 * Reading mode (view mode) overflow: same chrome as the editor overflow, with the entries of the reference's view menu:
 * Page sorter, Page view; Full screen, Tags, Save as file, Print; Favourite, Lock, Share, Delete. There is no page template
 * or page settings here. "Add to" (homescreen / reminder) is not built.
 */
@Composable
internal fun ReadingOverflowMenu(locked: Boolean, onAction: (ReadingAction) -> Unit, modifier: Modifier = Modifier, favorite: Boolean = false) {
    MenuSurface(modifier.width(278.9.dp)) {
        Column(Modifier.padding(top = 16.dp, bottom = 22.7.dp)) {
            PillMenuRow(AppIcons.PageSorter, R.string.feature_composer_ov_page_sorter) { onAction(ReadingAction.PageSorter) }
            Spacer(Modifier.height(16.4.dp))
            PillMenuRow(AppIcons.PageTemplate, R.string.feature_composer_ov_page_view) { onAction(ReadingAction.PageView) }
            Spacer(Modifier.height(17.5.dp))
            MenuDottedDivider(Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(9.9.dp))
            PlainActionRow(R.string.feature_composer_ov_full_screen) { onAction(ReadingAction.FullScreen) }
            PlainActionRow(R.string.feature_composer_ov_tags) { onAction(ReadingAction.Tags) }
            PlainActionRow(R.string.feature_composer_ov_save_as_file) { onAction(ReadingAction.SaveAsFile) }
            PlainActionRow(R.string.feature_composer_ov_print) { onAction(ReadingAction.Print) }
            Spacer(Modifier.height(16.3.dp))
            MenuDottedDivider(Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(23.dp))
            // Four actions do not fit the menu at 81 dp each (324 dp in a 278.9 dp menu: the row ran off to the right), so they share the width.
            Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                val cell = Modifier.weight(1f)
                IconAction(if (favorite) AppIcons.StarFilled else AppIcons.Star, R.string.feature_composer_ov_favourite, cell) { onAction(ReadingAction.Favourite) }
                IconAction(if (locked) AppIcons.Lock else AppIcons.Unlock, if (locked) R.string.feature_composer_unlock else R.string.feature_composer_ov_lock, cell) { onAction(ReadingAction.Lock) }
                IconAction(AppIcons.Share, R.string.feature_composer_ov_share, cell) { onAction(ReadingAction.Share) }
                IconAction(AppIcons.Trash, R.string.feature_composer_ov_delete, cell) { onAction(ReadingAction.Delete) }
            }
        }
    }
}
