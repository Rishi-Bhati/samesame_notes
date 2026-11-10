package org.samesame.notes.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.component.AppPillButton
import org.samesame.notes.core.designsystem.component.SimpleTopBar
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.viewer.openLibraryFile

internal fun categoryLabel(category: FileCategory): Int = when (category) {
    FileCategory.Image -> R.string.feature_files_category_images
    FileCategory.Pdf -> R.string.feature_files_category_pdf
    FileCategory.Text -> R.string.feature_files_category_text
    FileCategory.Markdown -> R.string.feature_files_category_markdown
    FileCategory.Audio -> R.string.feature_files_category_audio
    FileCategory.Video -> R.string.feature_files_category_video
    FileCategory.Archive -> R.string.feature_files_category_archives
    FileCategory.Document -> R.string.feature_files_category_documents
    FileCategory.Other -> R.string.feature_files_category_other
}

internal fun categoryColor(category: FileCategory): Color = when (category) {
    FileCategory.Image -> Color(0xFF4C9BF5)
    FileCategory.Pdf -> Color(0xFFE5604D)
    FileCategory.Text -> Color(0xFF8E9AAF)
    FileCategory.Markdown -> Color(0xFF6C7A93)
    FileCategory.Audio -> Color(0xFFB07CE8)
    FileCategory.Video -> Color(0xFFF2A33A)
    FileCategory.Archive -> Color(0xFFC79A5B)
    FileCategory.Document -> Color(0xFF3FB58A)
    FileCategory.Other -> Color(0xFF9E9E9E)
}

/** The Files Storage page: what the library uses, by type, its largest files, the Recycle bin and the free-space warning. */
@Composable
internal fun StorageScreen(contentUriOf: (LibraryFile) -> android.net.Uri, onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val colors = SameSameTheme.colors
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        SimpleTopBar(
            title = { Text(stringResource(R.string.feature_files_storage_title), style = SameSameText.SearchTitle.capped(), color = colors.textPrimary) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(AppIcons.Back, stringResource(R.string.feature_files_back), tint = colors.textPrimary)
                }
            },
        )
        if (!state.loaded) return@Column
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StorageCard {
                Text(FilesFormat.size(state.totalBytes), style = SameSameText.LargeTitle.capped(), color = colors.textPrimary)
                Text(
                    listOfNotNull(
                        pluralStringResource(R.plurals.feature_files_summary_files, state.fileCount, state.fileCount),
                        state.freeBytes?.let { stringResource(R.string.feature_files_storage_free, FilesFormat.size(it)) },
                    ).joinToString(" · "),
                    style = SameSameText.ListBody, color = colors.textSecondary,
                )
                if (state.totalBytes > 0) {
                    Row(Modifier.padding(top = 16.dp).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(colors.pill)) {
                        state.categories.forEach { c ->
                            if (c.bytes > 0) Box(Modifier.weight(c.bytes.toFloat()).height(12.dp).background(categoryColor(c.category)))
                        }
                    }
                    state.categories.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(categoryColor(c.category), CircleShape))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(stringResource(categoryLabel(c.category)), style = SameSameText.ListBody.copy(color = colors.textPrimary))
                                Text(pluralStringResource(R.plurals.feature_files_summary_files, c.count, c.count), style = SameSameText.Caption, color = colors.textSecondary)
                            }
                            Text(FilesFormat.size(c.bytes), style = SameSameText.ListBody, color = colors.textSecondary)
                        }
                    }
                }
            }
            if (state.lowSpace) StorageCard {
                Text(stringResource(R.string.feature_files_storage_low_title), style = SameSameText.CardTitle, color = colors.textPrimary)
                Text(
                    stringResource(R.string.feature_files_storage_low_message, FilesFormat.size(org.samesame.notes.core.model.Limits.LIBRARY_FREE_SPACE_RESERVE_BYTES)),
                    style = SameSameText.ListBody, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp),
                )
            }
            StorageCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.feature_files_bin_title), style = SameSameText.CardTitle, color = colors.textPrimary)
                        Text(
                            if (state.trashedCount == 0) stringResource(R.string.feature_files_bin_empty)
                            else stringResource(
                                R.string.feature_files_summary,
                                pluralStringResource(R.plurals.feature_files_summary_files, state.trashedCount, state.trashedCount),
                                FilesFormat.size(state.trashedBytes),
                            ),
                            style = SameSameText.ListBody, color = colors.textSecondary,
                        )
                    }
                    if (state.trashedCount > 0) AppPillButton(
                        stringResource(R.string.feature_files_empty_bin_action), { confirmEmpty = true },
                        Modifier.padding(start = 12.dp).size(width = 96.dp, height = 36.dp),
                    )
                }
            }
            if (state.largest.isNotEmpty()) {
                Text(
                    stringResource(R.string.feature_files_storage_largest), style = SameSameText.SectionSubtitle, color = colors.textSecondary,
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                )
                state.largest.forEach { file ->
                    FileListItem(
                        file, { contentUriOf(file) }, selecting = false, selected = false,
                        onClick = { openLibraryFile(context, file.id) }, onLongClick = {},
                        secondLine = FilesFormat.size(file.sizeBytes),
                    )
                }
            }
        }
    }
    if (confirmEmpty) SimpleConfirmDialog(
        stringResource(R.string.feature_files_empty_bin_title), stringResource(R.string.feature_files_empty_bin_message),
        stringResource(R.string.feature_files_empty_bin_action), { confirmEmpty = false }, { confirmEmpty = false; viewModel.emptyTrash() },
    )
}

@Composable
private fun StorageCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(SameSameTheme.colors.card, RoundedCornerShape(26.dp)).padding(horizontal = 22.dp, vertical = 18.dp)) { content() }
}
