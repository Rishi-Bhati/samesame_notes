package org.samesame.notes.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.component.AppSwitch
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.designsystem.theme.cappedSp

/**
 * Search settings (search screen's overflow menu): whether recent searches are kept, and clearing them.
 * NOTE: no reference capture exists for this screen; it is built from the Recent searches / Clear search history strings.
 */
@Composable
internal fun SearchSettingsScreen(onBack: () -> Unit, viewModel: SearchViewModel = hiltViewModel()) {
    val colors = SameSameTheme.colors
    val enabled by viewModel.historyEnabled.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        SimpleTopBar(
            title = { Text(stringResource(R.string.feature_notes_search_settings), style = SameSameText.SearchTitle.capped(), color = colors.textPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_notes_back), tint = colors.textPrimary)
                }
            },
        )
        Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(26.dp))) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).clickable { viewModel.setHistoryEnabled(!enabled) }.padding(start = 24.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.feature_notes_search_recent), fontSize = cappedSp(17f), color = colors.textPrimary, modifier = Modifier.weight(1f))
                AppSwitch(checked = enabled, onCheckedChange = null)
            }
            Row(
                Modifier.fillMaxWidth().height(56.dp).clickable { viewModel.clearHistory() }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.feature_notes_search_clear_history), fontSize = cappedSp(17f), color = colors.textPrimary)
            }
        }
    }
}
