package org.samesame.notes.feature.composer.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.oneuiproject.oneui.R as Oui
import kotlin.math.roundToInt
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.richtext.ui.RichTextEditorState
import org.samesame.notes.feature.composer.R

/** Visible rows before the list scrolls (the brief's "max ~6 rows"). */
private const val VISIBLE_ROWS = 6
private val ROW_HEIGHT = 54.dp

/**
 * The inline `///` picker: a small popup under the caret listing the Files library (newest first), filtered live by the
 * text after the token. Tapping a row or pressing Enter hands the file to [onPick] (Insert > From Files' own path); the
 * host removes the token in that same edit. Back and a space with no match close it without touching the typed text.
 */
@Composable
internal fun SlashFilePickerHost(
    state: RichTextEditorState,
    files: List<LibraryFile>,
    textCoords: () -> LayoutCoordinates?,
    onPick: (file: LibraryFile, start: Int, end: Int) -> Unit,
    onUpload: (start: Int, end: Int) -> Unit,
) {
    val trigger = state.slashTrigger
    var dismissedStart by remember { mutableStateOf(-1) }
    var highlighted by remember { mutableIntStateOf(0) }
    val filtered = remember(files, trigger?.query) { SlashFileFilter.filter(files, trigger?.query.orEmpty()) }

    // A fresh token starts un-dismissed.
    LaunchedEffect(trigger) {
        if (trigger == null) dismissedStart = -1
    }
    // The IME's Enter picks the highlighted row; cleared whenever the picker is not showing, so a stale closure can't
    // insert after it closed.
    SideEffect {
        val token = trigger
        state.onSlashEnter = if (token != null && token.start != dismissedStart && state.enabled && state.isFocused) {
            enter@{
                val file = filtered.getOrNull(highlighted) ?: return@enter false
                dismissedStart = token.start
                onPick(file, token.start, token.caret)
                true
            }
        } else {
            null
        }
    }
    DisposableEffect(state) { onDispose { state.onSlashEnter = null } }

    val t = trigger ?: return
    if (t.start == dismissedStart || !state.enabled || !state.isFocused) return

    // A second space (or a space after no match) closes the picker and leaves the text alone.
    LaunchedEffect(t.start, t.query, filtered.isEmpty()) {
        if (t.start != dismissedStart) highlighted = 0
        if (t.query.endsWith(" ") && filtered.isEmpty()) dismissedStart = t.start
    }

    BackHandler {
        dismissedStart = t.start
        state.onSlashEnter = null
    }

    val coordinates = textCoords()
    val origin = coordinates?.takeIf { it.isAttached }?.localToWindow(androidx.compose.ui.geometry.Offset.Zero)
        ?: androidx.compose.ui.geometry.Offset.Zero
    val (top, bottom) = state.caretInView(t.caret)
    val anchorTop = top + origin
    val anchorBottom = bottom + origin
    val margin = with(LocalDensity.current) { 8.dp.toPx() }
    val provider = remember(anchorTop, anchorBottom, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
                val x = anchorBottom.x.coerceIn(margin, maxX).roundToInt()
                val below = anchorBottom.y.roundToInt()
                val above = (anchorTop.y.roundToInt() - popupContentSize.height).coerceAtLeast(margin.toInt())
                val y = if (below + popupContentSize.height <= windowSize.height) below else above
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = { dismissedStart = t.start },
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true),
    ) {
        SlashFilePickerCard(
            files = filtered,
            query = t.query,
            highlighted = highlighted,
            onHighlight = { highlighted = it },
            onPick = { file ->
                dismissedStart = t.start
                state.onSlashEnter = null
                onPick(file, t.start, t.caret)
            },
            onUpload = { onUpload(t.start, t.caret) },
        )
    }
}

@Composable
private fun SlashFilePickerCard(
    files: List<LibraryFile>,
    query: String,
    highlighted: Int,
    onHighlight: (Int) -> Unit,
    onPick: (LibraryFile) -> Unit,
    onUpload: () -> Unit,
) {
    val colors = SameSameTheme.colors
    Surface(
        modifier = Modifier.widthIn(min = 240.dp, max = 340.dp),
        shape = RoundedCornerShape(20.dp),
        color = colors.elevated,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        if (files.isEmpty()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(stringResource(R.string.feature_composer_files_slash_no_files), style = SameSameText.ListBody, color = colors.textPrimary)
                if (query.trim().isEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.feature_composer_files_slash_upload),
                        style = SameSameText.Button,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable(onClick = onUpload)
                            .padding(vertical = 6.dp, horizontal = 2.dp),
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = ROW_HEIGHT * VISIBLE_ROWS)) {
                itemsIndexed(files, key = { _, file -> file.id.value }) { index, file ->
                    SlashFileRow(file, selected = index == highlighted, onClick = {
                        onHighlight(index)
                        onPick(file)
                    })
                }
            }
        }
    }
}

@Composable
private fun SlashFileRow(file: LibraryFile, selected: Boolean, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.pill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = ROW_HEIGHT - 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(slashFileIcon(file)), null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.displayName,
                style = SameSameText.ListBody,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (file.description.isNotBlank()) {
                Text(
                    file.description,
                    style = SameSameText.Caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The same `ic_oui_file_type_*` glyphs the Files list uses. */
private fun slashFileIcon(file: LibraryFile): Int = when (file.category) {
    FileCategory.Image -> Oui.drawable.ic_oui_file_type_image
    FileCategory.Pdf -> Oui.drawable.ic_oui_file_type_adobe
    FileCategory.Text, FileCategory.Markdown -> Oui.drawable.ic_oui_file_type_txt
    FileCategory.Audio -> Oui.drawable.ic_oui_file_type_audio
    FileCategory.Video -> Oui.drawable.ic_oui_file_type_video
    FileCategory.Archive -> Oui.drawable.ic_oui_file_type_zip
    FileCategory.Document -> when (file.ext) {
        "xls", "xlsx", "csv", "ods" -> Oui.drawable.ic_oui_file_type_excel
        "ppt", "pptx", "odp" -> Oui.drawable.ic_oui_file_type_ppt
        "html", "htm" -> Oui.drawable.ic_oui_file_type_html
        else -> Oui.drawable.ic_oui_file_type_word
    }
    FileCategory.Other -> if (file.ext == "apk") Oui.drawable.ic_oui_file_type_apk else Oui.drawable.ic_oui_file_type_etc
}
