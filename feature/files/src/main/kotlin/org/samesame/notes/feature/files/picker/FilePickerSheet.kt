package org.samesame.notes.feature.files.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.component.EmptyState
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.feature.files.FileFolderCard
import org.samesame.notes.feature.files.FileListItem
import org.samesame.notes.feature.files.FilesFormat
import org.samesame.notes.feature.files.R

/**
 * Full-screen "pick from Files" sheet for other features (Insert > From Files): browse folders, search by name, pick one
 * or more files. Only live files are offered, never the Recycle bin. Calls [onPicked] once with a non-empty list in pick
 * order, or [onDismiss] (Cancel, Back at the root).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilePickerSheet(
    onPicked: (List<FileId>) -> Unit,
    onDismiss: () -> Unit,
    viewModel: FilePickerViewModel = hiltViewModel(),
) {
    // The ViewModel outlives one showing of the sheet, so every showing starts clean.
    LaunchedEffect(Unit) { viewModel.reset() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = SameSameTheme.colors
    val scope = rememberCoroutineScope()
    val back = { if (!viewModel.navigateUp()) onDismiss() }

    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
            SimpleTopBar(
                title = {
                    val query = state.query
                    if (query != null) {
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        BasicTextField(
                            value = query, onValueChange = viewModel::setQuery, singleLine = true,
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
                    } else {
                        Text(
                            when {
                                state.selected.isNotEmpty() -> stringResource(R.string.feature_files_selected_count, state.selected.size)
                                else -> state.title ?: stringResource(R.string.feature_files_select_files)
                            },
                            style = SameSameText.SearchTitle.capped(), color = colors.textPrimary, maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Back, stringResource(R.string.feature_files_back), tint = colors.textPrimary)
                    }
                },
                actions = {
                    if (!state.searching) IconButton(onClick = viewModel::startSearch, modifier = Modifier.size(40.dp)) {
                        Icon(AppIcons.Search, stringResource(R.string.feature_files_search), tint = colors.textPrimary)
                    }
                },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !state.loaded -> Unit
                    state.searching && state.query.isNullOrBlank() -> Unit
                    state.isEmpty -> EmptyState(stringResource(if (state.searching) R.string.feature_files_empty_search else R.string.feature_files_empty))
                    else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val gap = 14.dp
                        val folderWidth = (maxWidth - 16.dp * 2 - gap * 3) / 4
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.subFolders.isNotEmpty()) item(key = "folders") {
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
                                ) {
                                    state.subFolders.forEach { folder ->
                                        FileFolderCard(folder, folderWidth, selecting = false, selected = false, onClick = { viewModel.openFolder(folder.id) }, onLongClick = {})
                                    }
                                }
                            }
                            items(state.files, key = { it.id.value }) { file ->
                                val path = if (state.searching) FilesFormat.path(file.folderId, state.allFolders) else ""
                                FileListItem(
                                    file, { viewModel.contentUri(file) }, selecting = true, selected = file.id in state.selected,
                                    onClick = { viewModel.toggle(file) }, onLongClick = { viewModel.toggle(file) },
                                    secondLine = if (path.isEmpty()) FilesFormat.sizeAndDate(file) else stringResource(R.string.feature_files_search_in, path),
                                )
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(colors.background).navigationBarsPadding().height(56.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PickerButton(stringResource(R.string.feature_files_cancel), true, Modifier.weight(1f), onDismiss)
                Box(Modifier.width(1.dp).height(16.dp).background(colors.divider))
                PickerButton(stringResource(R.string.feature_files_picker_insert), state.selected.isNotEmpty(), Modifier.weight(1f)) {
                    scope.launch {
                        val ids = viewModel.confirmed()
                        if (ids.isNotEmpty()) onPicked(ids) else onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Box(modifier.height(48.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, style = SameSameText.Button, color = if (enabled) colors.textPrimary else colors.textTertiary)
    }
}
