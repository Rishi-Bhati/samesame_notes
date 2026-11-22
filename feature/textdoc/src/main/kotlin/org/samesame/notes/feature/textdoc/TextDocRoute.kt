package org.samesame.notes.feature.textdoc

import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.component.AppDropdownMenu
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.FontSectionScope
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.FontSection
import org.samesame.notes.core.richtext.markdown.MarkdownParser
import org.samesame.notes.core.richtext.markdown.MarkdownPreview

/**
 * The text document screen. [uri] is an external document; [libraryFileId] is a Files entry, which saves through the
 * repository. Both may be given (the Files viewer knows the file and its content URI).
 *
 * [showTopBar] is false when the host already draws its own bar (the Files viewer); the editor's action row stays.
 */
@Composable
fun TextDocRoute(
    uri: Uri? = null,
    libraryFileId: String? = null,
    displayName: String? = null,
    writable: Boolean = true,
    showTopBar: Boolean = true,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    noteId: String? = null,
    mimeType: String? = null,
    /** Opens the note that Save as note made (markdown = a Markdown note); null leaves the user in the editor. */
    onOpenNote: ((noteId: String, markdown: Boolean) -> Unit)? = null,
    viewModel: TextDocViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var finding by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    // A document from another app: Save offers every way of keeping it (TextDocSaveHost).
    val foreign = uri != null && noteId == null && libraryFileId == null
    var saveOptions by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uri, libraryFileId, writable, noteId) {
        viewModel.load(uri, libraryFileId, writable, displayName, noteId, mimeType)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, noteId, libraryFileId, writable) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (writable && (noteId != null || libraryFileId != null)) {
                    viewModel.flushOnStop(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { target ->
        if (target != null) scope.launch {
            val ok = viewModel.saveAs(context, target)
            Toast.makeText(context, if (ok) R.string.feature_textdoc_toast_saved else R.string.feature_textdoc_toast_save_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val saveAsName = state.title ?: stringResource(R.string.feature_textdoc_untitled)
    fun saveInPlace() {
        if (state.isReadOnly) {
            saveAs.launch(saveAsName)
            return
        }
        scope.launch {
            if (viewModel.saveInPlace(context)) {
                Toast.makeText(context, R.string.feature_textdoc_toast_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.feature_textdoc_toast_cant_save_here, Toast.LENGTH_SHORT).show()
                saveAs.launch(saveAsName)
            }
        }
    }
    fun save() {
        if (!foreign) saveInPlace()
        else if (!state.isLoading && !state.loadFailed) saveOptions = true
    }

    fun handleBack() {
        if (noteId != null) {
            if (state.isModified) {
                scope.launch {
                    val saved = viewModel.saveInPlace(context)
                    if (saved) {
                        onBack()
                    } else {
                        Toast.makeText(context, R.string.feature_textdoc_toast_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                onBack()
            }
        } else if (state.isModified) {
            discard = true
        } else {
            onBack()
        }
    }

    BackHandler { handleBack() }

    val config = LocalConfiguration.current
    val isWideLandscape = config.screenWidthDp >= 600 && config.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(modifier.fillMaxSize().background(SameSameTheme.colors.background)) {
        if (showTopBar) {
            TopBar(
                title = state.title ?: stringResource(R.string.feature_textdoc_title),
                state = state,
                onBack = ::handleBack,
                onSave = ::save,
                onSaveAs = { saveAs.launch(saveAsName) },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onFind = { finding = !finding },
                onMonospace = { viewModel.setMonospace(!state.isMonospace) },
                onWrap = { viewModel.setWrap(!state.isWrap) },
                onPreview = { viewModel.setHtmlPreview(!state.isHtmlPreview) },
                onMarkdownMode = viewModel::setMarkdownMode,
            )
        } else {
            ActionRow(
                state = state,
                onSave = ::save,
                onSaveAs = { saveAs.launch(saveAsName) },
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onFind = { finding = !finding },
                onMonospace = { viewModel.setMonospace(!state.isMonospace) },
                onWrap = { viewModel.setWrap(!state.isWrap) },
                onPreview = { viewModel.setHtmlPreview(!state.isHtmlPreview) },
                onMarkdownMode = viewModel::setMarkdownMode,
            )
        }
        if (state.isReadOnly) {
            Text(
                stringResource(if (state.isLockedNote) R.string.feature_textdoc_locked_read_only else R.string.feature_textdoc_read_only_notice),
                style = SameSameText.Caption,
                color = SameSameTheme.colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (finding) {
            FindBar(
                state = state,
                onQuery = viewModel::find,
                onReplaceQuery = viewModel::setReplaceQuery,
                onReplace = viewModel::replaceCurrent,
                onReplaceAll = viewModel::replaceAll,
                onNext = viewModel::nextMatch,
                onPrevious = viewModel::previousMatch,
                onClose = { finding = false },
            )
        }
        Box(Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SameSameTheme.colors.accent)
                }
                state.loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.feature_textdoc_open_failed), color = SameSameTheme.colors.textSecondary)
                }
                state.isHtmlPreview -> HtmlPreview(state.text, Modifier.fillMaxSize())
                state.isMarkdownPreview && !isWideLandscape -> {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)) {
                        MarkdownPreview(
                            markdown = state.text,
                            modifier = Modifier.fillMaxWidth(),
                            onTaskCheckedChange = if (state.isReadOnly) null else { offset, checked ->
                                viewModel.updateText(MarkdownParser.withTaskChecked(state.text, offset, checked))
                            },
                        )
                    }
                }
                state.isMarkdownPreview && isWideLandscape -> {
                    var previewText by remember { mutableStateOf(state.text) }
                    LaunchedEffect(state.text) {
                        if (previewText.isEmpty() && state.text.isNotEmpty()) {
                            previewText = state.text
                        } else {
                            delay(300)
                            previewText = state.text
                        }
                    }
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            TextDocEditor(
                                state,
                                live = state.markdownEditMode == MarkdownViewMode.Live,
                                onText = viewModel::updateText,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(SameSameTheme.colors.divider))
                        Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)) {
                            MarkdownPreview(
                                markdown = previewText,
                                modifier = Modifier.fillMaxWidth(),
                                onTaskCheckedChange = if (state.isReadOnly) null else { offset, checked ->
                                    viewModel.updateText(MarkdownParser.withTaskChecked(state.text, offset, checked))
                                },
                            )
                        }
                    }
                }
                else -> TextDocEditor(state, live = state.isLiveMarkdown, onText = viewModel::updateText, modifier = Modifier.fillMaxSize())
            }
        }
    }

    if (foreign) {
        TextDocSaveSupport(
            visible = saveOptions,
            onDismiss = { saveOptions = false },
            state = state,
            displayName = displayName,
            mimeType = mimeType,
            uri = uri,
            onSaveInPlace = ::saveInPlace,
            onOpenNote = onOpenNote,
        )
    }

    if (discard) {
        AppDialog(
            onDismissRequest = { discard = false },
            dismissLabel = stringResource(R.string.feature_textdoc_cancel),
            confirmLabel = stringResource(R.string.feature_textdoc_discard_action),
            onConfirm = { discard = false; onBack() },
            title = stringResource(R.string.feature_textdoc_discard_title),
        ) {
            Text(stringResource(R.string.feature_textdoc_discard_message), color = SameSameTheme.colors.textPrimary)
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    state: TextDocUiState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onMonospace: () -> Unit,
    onWrap: () -> Unit,
    onPreview: () -> Unit,
    onMarkdownMode: (MarkdownViewMode) -> Unit,
) {
    val colors = SameSameTheme.colors
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(56.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
            Icon(AppIcons.Back, stringResource(R.string.feature_textdoc_back), tint = colors.textPrimary)
        }
        Text(
            title,
            style = SameSameText.ToolbarTitle.capped(),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        ActionButtons(state, onSave, onSaveAs, onUndo, onRedo, onFind, onMonospace, onWrap, onPreview, onMarkdownMode)
    }
}

@Composable
private fun ActionRow(
    state: TextDocUiState,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onMonospace: () -> Unit,
    onWrap: () -> Unit,
    onPreview: () -> Unit,
    onMarkdownMode: (MarkdownViewMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButtons(state, onSave, onSaveAs, onUndo, onRedo, onFind, onMonospace, onWrap, onPreview, onMarkdownMode)
    }
}

@Composable
private fun ActionButtons(
    state: TextDocUiState,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onFind: () -> Unit,
    onMonospace: () -> Unit,
    onWrap: () -> Unit,
    onPreview: () -> Unit,
    onMarkdownMode: (MarkdownViewMode) -> Unit,
) {
    val colors = SameSameTheme.colors
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = onUndo, enabled = state.canUndo && !state.isReadOnly, modifier = Modifier.size(40.dp)) {
        Icon(AppIcons.Undo, stringResource(R.string.feature_textdoc_action_undo), tint = colors.textPrimary)
    }
    IconButton(onClick = onRedo, enabled = state.canRedo && !state.isReadOnly, modifier = Modifier.size(40.dp)) {
        Icon(AppIcons.Redo, stringResource(R.string.feature_textdoc_action_redo), tint = colors.textPrimary)
    }
    IconButton(onClick = onFind, modifier = Modifier.size(40.dp)) {
        Icon(AppIcons.Search, stringResource(R.string.feature_textdoc_action_find_replace), tint = colors.textPrimary)
    }
    if (state.isMarkdown) {
        // Live / Source / Preview: the button opens the three-way choice (also in the More menu).
        var modeMenu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { modeMenu = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (state.isMarkdownPreview) AppIcons.Eye else AppIcons.Pencil,
                    stringResource(R.string.feature_textdoc_markdown_view),
                    tint = colors.textPrimary,
                )
            }
            AppDropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }, offset = DpOffset((-2).dp, (-46).dp)) {
                MarkdownModeItems(state.markdownMode) { mode -> modeMenu = false; onMarkdownMode(mode) }
            }
        }
    }
    IconButton(onClick = onSave, enabled = !state.isSaving, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Outlined.Save, stringResource(R.string.feature_textdoc_action_save), tint = colors.textPrimary)
    }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
            Icon(AppIcons.More, stringResource(R.string.feature_textdoc_more), tint = colors.textPrimary)
        }
        AppDropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset((-2).dp, (-46).dp)) {
            AppMenuItem(stringResource(R.string.feature_textdoc_action_save_as), { menu = false; onSaveAs() })
            AppMenuItem(stringResource(R.string.feature_textdoc_toggle_monospace), { menu = false; onMonospace() }, checked = state.isMonospace)
            AppMenuItem(stringResource(R.string.feature_textdoc_toggle_wrap), { menu = false; onWrap() }, checked = state.isWrap)
            AppMenuItem(
                stringResource(if (state.isHtmlPreview) R.string.feature_textdoc_toggle_source else R.string.feature_textdoc_toggle_rendered),
                { menu = false; onPreview() },
                checked = state.isHtmlPreview,
            )
            if (state.isMarkdown) {
                MarkdownModeItems(state.markdownMode) { mode -> menu = false; onMarkdownMode(mode) }
            }
        }
    }
}

@Composable
private fun MarkdownModeItems(current: MarkdownViewMode, onSelect: (MarkdownViewMode) -> Unit) {
    MarkdownViewMode.entries.forEach { mode ->
        AppMenuItem(
            stringResource(
                when (mode) {
                    MarkdownViewMode.Live -> R.string.feature_textdoc_markdown_live
                    MarkdownViewMode.Source -> R.string.feature_textdoc_markdown_source
                    MarkdownViewMode.Preview -> R.string.feature_textdoc_action_preview
                },
            ),
            { onSelect(mode) },
            checked = current == mode,
        )
    }
}

@Composable
private fun FindBar(
    state: TextDocUiState,
    onQuery: (String) -> Unit,
    onReplaceQuery: (String) -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    FontSectionScope(FontSection.Chrome) {
        val colors = SameSameTheme.colors
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp)
                .background(colors.card, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = state.findQuery,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = SameSameText.ListBody.copy(color = colors.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (state.findQuery.isEmpty()) Text(stringResource(R.string.feature_textdoc_find_hint), color = colors.textTertiary)
                            inner()
                        }
                    },
                )
                if (state.findQuery.isNotEmpty()) {
                    Text(
                        if (state.matchCount == 0) stringResource(R.string.feature_textdoc_find_none)
                        else stringResource(R.string.feature_textdoc_find_position, state.activeMatchIndex + 1, state.matchCount),
                        style = SameSameText.Caption,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.ChevronUp, stringResource(R.string.feature_textdoc_find_previous), tint = colors.textPrimary)
                }
                IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.ChevronDown, stringResource(R.string.feature_textdoc_find_next), tint = colors.textPrimary)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.Close, stringResource(R.string.feature_textdoc_close), tint = colors.textPrimary)
                }
            }
            if (!state.isReadOnly) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = state.replaceQuery,
                        onValueChange = onReplaceQuery,
                        singleLine = true,
                        textStyle = SameSameText.ListBody.copy(color = colors.textPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (state.replaceQuery.isEmpty()) Text(stringResource(R.string.feature_textdoc_replace_hint), color = colors.textTertiary)
                                inner()
                            }
                        },
                    )
                    androidx.compose.material3.TextButton(onClick = onReplace) {
                        Text(stringResource(R.string.feature_textdoc_action_replace), color = colors.accent)
                    }
                    androidx.compose.material3.TextButton(onClick = onReplaceAll) {
                        Text(stringResource(R.string.feature_textdoc_action_replace_all), color = colors.accent)
                    }
                }
            }
        }
    }
}
