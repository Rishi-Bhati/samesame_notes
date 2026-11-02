package org.samesame.notes.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import org.samesame.notes.core.designsystem.theme.cappedSp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.foundation.layout.Box as LayoutBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.component.EmptyState
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.ViewMode

@Composable
internal fun SearchScreen(
    onBack: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onSearchSettings: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val colors = SameSameTheme.colors
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        SimpleTopBar(
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_notes_back), tint = colors.textPrimary)
                }
            },
            title = {
                BasicTextField(
                    value = query, onValueChange = { viewModel.query.value = it }, singleLine = true,
                    textStyle = SameSameText.SearchTitle.copy(fontSize = 22.sp, lineHeight = 28.sp, color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.remember(query); keyboard?.hide() }),
                    modifier = Modifier.padding(start = 4.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        LayoutBox {
                            if (query.isEmpty()) {
                                Text(stringResource(R.string.feature_notes_search_hint), style = SameSameText.SearchTitle.copy(fontSize = 22.sp, lineHeight = 28.sp), color = colors.textSecondary)
                            }
                            inner()
                        }
                    },
                )
            },
            actions = {
                if (query.isEmpty()) {
                    // Voice search stays inert: the app has no microphone access.
                    IconButton(onClick = {}, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Mic, stringResource(R.string.feature_notes_search_voice), tint = colors.textPrimary)
                    }
                } else {
                    IconButton(onClick = { viewModel.query.value = "" }, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Close, stringResource(R.string.feature_notes_search_clear), tint = colors.textPrimary)
                    }
                }
                LayoutBox {
                    // Ref #20-#22: overflow centre 41.9dp from the edge (9.9dp further in than the list toolbar).
                    IconButton(onClick = { menu = true }, modifier = Modifier.padding(end = 9.9.dp).size(40.dp)) {
                        Icon(AppIcons.More, stringResource(R.string.feature_notes_more_options), tint = colors.textPrimary)
                    }
                    AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = androidx.compose.ui.unit.DpOffset((-1.7).dp, (-46.1).dp)) {
                        AppMenuItem(stringResource(R.string.feature_notes_search_settings), { menu = false; onSearchSettings() })
                    }
                }
            },
        )
        when {
            query.isBlank() && recents.isEmpty() -> EmptyState(stringResource(R.string.feature_notes_search_no_recent))
            query.isBlank() -> RecentSearches(
                recents, onPick = { viewModel.query.value = it.keyword }, onDelete = { viewModel.forget(it.keyword) },
                onClearAll = viewModel::clearHistory,
            )
            results.isEmpty() -> EmptyState(stringResource(R.string.feature_notes_search_no_results))
            else -> Box(Modifier.fillMaxSize()) {
                val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val scrollEstimate = remember { ScrollEstimateHolder() }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 18.3.dp, end = 18.3.dp, top = 5.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.3.dp),
                    verticalArrangement = Arrangement.spacedBy(36.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { it.id.value }) { note ->
                        NoteGridItem(
                            note, ViewMode.GridLarge, selecting = false, selected = false,
                            onClick = { viewModel.remember(query); onOpenNote(note.id) }, onLongClick = {}, highlight = query,
                        )
                    }
                }
                // The same draggable scroll bar as the home lists (two note columns, no full-span rows).
                GridScrollBar(gridState, scrollEstimate, columns = 2, headerItems = 0)
            }
        }
    }
}

/** "Recent searches" (layouts recent_search_list_*): sub-header, one row per keyword with its date and a clear button, "Clear all". */
@Composable
private fun RecentSearches(
    recents: List<org.samesame.notes.core.data.repository.RecentSearch>,
    onPick: (org.samesame.notes.core.data.repository.RecentSearch) -> Unit,
    onDelete: (org.samesame.notes.core.data.repository.RecentSearch) -> Unit,
    onClearAll: () -> Unit,
) {
    val colors = SameSameTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val dateFormat = remember { android.text.format.DateFormat.getMediumDateFormat(context) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                stringResource(R.string.feature_notes_search_recent), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.textTertiary,
                modifier = Modifier.padding(start = 24.dp, top = 11.75.dp, bottom = 5.dp),
            )
        }
        listItems(recents, key = { it.keyword }) { item ->
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().clickable { onPick(item) }.padding(start = 24.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(item.keyword, fontSize = cappedSp(17f), color = colors.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(dateFormat.format(java.util.Date(item.time)), fontSize = 14.sp, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 15.dp))
                Icon(
                    AppIcons.Close, stringResource(R.string.feature_notes_search_delete_recent), tint = colors.textSecondary,
                    modifier = Modifier.padding(end = 20.dp).size(24.dp).clickable { onDelete(item) },
                )
            }
        }
        item {
            Box(Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClearAll), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(stringResource(R.string.feature_notes_search_clear_all), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.accent)
            }
        }
    }
}
