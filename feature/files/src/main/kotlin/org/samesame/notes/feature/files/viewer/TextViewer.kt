package org.samesame.notes.feature.files.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.component.AppMenuItem
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.R

private val PROSE_EXTS = setOf("txt", "text", "md", "markdown", "log", "")

/**
 * Read-only text viewer: the file's text (the first 2 MB), selectable, with a monospace toggle, line wrapping on or off
 * and find. Nothing is rendered or run: HTML and markdown show as their source.
 */
@Composable
internal fun TextViewer(
    file: LibraryFile,
    uri: Uri,
    bar: @Composable (BarSlots, ChromeColors) -> Unit,
    onOpenWith: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val colors = SameSameTheme.colors
    val load by produceState<Result<TextLoad>?>(null, file.sha256) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { TextContent.read(it) } ?: throw java.io.FileNotFoundException() }
        }
    }
    var monospace by rememberSaveable(file.id.value) { mutableStateOf(file.ext.lowercase() !in PROSE_EXTS) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var finding by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var currentMatch by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val lines = load?.getOrNull()?.lines.orEmpty()
    val matches = remember(lines, query, finding) { if (finding) TextContent.find(lines, query) else emptyList() }

    LaunchedEffect(matches) { currentMatch = if (matches.isEmpty()) -1 else 0 }
    LaunchedEffect(currentMatch, matches) {
        // +1 when the "first 2 MB" notice sits above the text.
        matches.getOrNull(currentMatch)?.let { listState.scrollToItem(it.line + if (load?.getOrNull()?.truncated == true) 1 else 0) }
    }
    BackHandler(enabled = finding) { finding = false }

    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        bar(
            BarSlots(
                menu = { dismiss ->
                    AppMenuItem(stringResource(R.string.feature_files_viewer_find), { dismiss(); finding = true })
                    AppMenuItem(stringResource(R.string.feature_files_viewer_monospace), { dismiss(); monospace = !monospace }, checked = monospace)
                    AppMenuItem(stringResource(R.string.feature_files_viewer_wrap), { dismiss(); wrap = !wrap }, checked = wrap)
                },
            ),
            themeChrome(),
        )
        if (finding) FindBar(
            query = query, onQuery = { query = it },
            position = currentMatch, count = matches.size,
            onStep = { forward -> currentMatch = TextContent.step(currentMatch, matches.size, forward) },
            onClose = { finding = false },
        )
        val result = load
        when {
            result == null -> Unit
            result.isFailure -> ViewerMessage(stringResource(R.string.feature_files_viewer_text_failed), onOpenWith, Modifier.fillMaxSize())
            lines.isEmpty() || lines.all { it.isEmpty() } -> ViewerMessage(stringResource(R.string.feature_files_viewer_text_empty), null, Modifier.fillMaxSize())
            else -> {
                val style = TextStyle(
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.SansSerif,
                    fontSize = if (monospace) 13.sp else 15.sp, lineHeight = if (monospace) 19.sp else 22.sp, color = colors.textPrimary,
                )
                val byLine = remember(matches) { matches.withIndex().groupBy({ it.value.line }, { it }) }
                val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val sideways = rememberScrollState()
                SelectionContainer(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = if (wrap) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(sideways),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp + navBar),
                    ) {
                        if (result.getOrNull()?.truncated == true) item(key = "notice") {
                            Text(
                                stringResource(R.string.feature_files_viewer_text_truncated),
                                style = SameSameText.Caption, color = colors.textSecondary,
                                modifier = Modifier.padding(bottom = 10.dp).background(colors.card, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        itemsIndexed(lines) { index, line ->
                            val hits = byLine[index]
                            val text = if (hits == null) AnnotatedString(line) else buildAnnotatedString {
                                append(line)
                                hits.forEach { (n, m) ->
                                    val end = (m.start + m.length).coerceAtMost(line.length)
                                    addStyle(
                                        if (n == currentMatch) SpanStyle(background = colors.accent, color = Color.White) else SpanStyle(background = colors.highlight),
                                        m.start, end,
                                    )
                                }
                            }
                            // An empty line still takes a row.
                            Text(if (text.isEmpty()) AnnotatedString(" ") else text, style = style, softWrap = wrap)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, position: Int, count: Int, onStep: (forward: Boolean) -> Unit, onClose: () -> Unit) {
    val colors = SameSameTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(48.dp)
            .background(colors.card, RoundedCornerShape(24.dp)).padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = SameSameText.ListBody.copy(color = colors.textPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onStep(true) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text(stringResource(R.string.feature_files_viewer_find_hint), style = SameSameText.ListBody.copy(fontSize = 16.sp), color = colors.textTertiary)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Text(
                if (count == 0) stringResource(R.string.feature_files_viewer_find_none)
                else stringResource(R.string.feature_files_viewer_position, position + 1, count),
                style = SameSameText.Caption, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        val chrome = themeChrome()
        ChromeIconButton(AppIcons.ChevronUp, stringResource(R.string.feature_files_viewer_find_previous), chrome, { onStep(false) })
        ChromeIconButton(AppIcons.ChevronDown, stringResource(R.string.feature_files_viewer_find_next), chrome, { onStep(true) })
        ChromeIconButton(AppIcons.Close, stringResource(R.string.feature_files_close), chrome, onClose)
    }
}
