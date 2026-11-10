package org.samesame.notes.feature.composer.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.FilePasteMode
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.TextLink
import org.samesame.notes.core.richtext.ui.RichTextEditorState
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.session.EditorSession
import org.samesame.notes.feature.composer.text.ExtendedTextToolbar
import java.util.UUID

/**
 * Library file links in the body (docs/architecture/files-feature.md 2.3, 2.4): the paste interception and its chooser,
 * "Show in note" (the picture is copied into the note), taps on file links, the selected link's menu rows, the inline
 * picture's "Show as link" / "Open in Files", and Insert > From Files. Main thread only.
 */
internal class FileLinkController(
    private val context: Context,
    private val view: View,
    private val scope: CoroutineScope,
    private val links: NoteFileLinks,
    private val session: EditorSession,
    private val body: BodyText,
    private val textState: RichTextEditorState,
    private val snackbar: SnackbarHostState,
    private val strings: Strings,
) {
    /** Labels resolved by the composable (the controller has no composition of its own). */
    class Strings(
        val pasteAsLink: String,
        val showInNote: String,
        val showImages: String,
        val open: String,
        val copyLink: String,
        val editLabel: String,
        val removeLink: String,
        val showAsLink: String,
        val openInFiles: String,
        val noApp: String,
        val showFailed: String,
    )

    // Inputs that change with composition, kept current by the screen.
    var pasteMode: FilePasteMode = FilePasteMode.Ask
    var locked: Boolean = false
    var navigation: LibraryFilesNavigation? = null

    /** Where the chooser sits (root view coordinates). */
    var anchorRect: () -> Rect = { Rect.Zero }

    // Dialog state, read by [FileLinkDialogs].
    var trashed by mutableStateOf<LibraryFile?>(null)
    var missing by mutableStateOf<MissingLink?>(null)
    var editing by mutableStateOf<EditedLink?>(null)
    var lockedTip by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)

    /** A link whose file the library doesn't know: [range] is where it sits in the body (to remove it). */
    data class MissingLink(val target: FileLinks.Target, val start: Int, val end: Int, val explicit: Boolean)

    /** The link whose label is being edited. */
    data class EditedLink(val start: Int, val end: Int, val uri: String, val label: String)

    private val menu = FloatingChoiceMenu(view)

    // ------------------------------------------------------------------------------------------ paste / insert

    /** The editor's paste hook ([RichTextEditorState.onPasteFileLinks]): links over the current selection. */
    fun paste(targets: List<FileLinks.Target>) {
        val sel = body.selection
        insertAt(targets, sel.min, sel.max)
    }

    /** Insert > From Files: the picked files at the caret, with the same choice as a paste. */
    fun insertPicked(ids: List<FileId>) {
        pickerOpen = false
        if (ids.isEmpty()) return
        val sel = body.selection
        insertAt(ids.map { FileLinks.Target(it, null) }, sel.min, sel.max)
    }

    private fun insertAt(targets: List<FileLinks.Target>, start: Int, end: Int) {
        if (targets.isEmpty()) return
        scope.launch {
            val known = runCatching { links.resolve(targets.map { it.id }) }.getOrDefault(emptyMap())
            val items = FileLinkPaste.items(targets, known)
            when (FileLinkPaste.choice(pasteMode, items)) {
                FileLinkPaste.Choice.Links -> insertParts(FileLinkPaste.parts(items, show = false), start, end)
                FileLinkPaste.Choice.Show -> insertParts(FileLinkPaste.parts(items, show = true), start, end)
                FileLinkPaste.Choice.Ask -> {
                    val showLabel = if (FileLinkPaste.showsSeveral(items)) strings.showImages else strings.showInNote
                    var done = false
                    fun go(show: Boolean) {
                        if (done) return
                        done = true
                        scope.launch { insertParts(FileLinkPaste.parts(items, show), start, end) }
                    }
                    menu.show(
                        anchorRect(),
                        listOf(FloatingChoiceMenu.Row(strings.pasteAsLink) { go(false) }, FloatingChoiceMenu.Row(showLabel) { go(true) }),
                        onDismiss = { go(false) },
                    )
                }
            }
        }
    }

    /** A picture ready to go into the text. */
    private class Shown(val obj: ImageObject)

    /**
     * Replaces `[start, end)` by [parts] (one undo step): link runs, and pictures copied into the bundle first. A picture
     * that can't be read goes in as a link (with a message).
     */
    private suspend fun insertParts(parts: List<FileLinkPaste.Part>, start: Int, end: Int) {
        var failed = false
        val ready: List<Any> = parts.map { part ->
            when (part) {
                is FileLinkPaste.Part.Link -> part
                is FileLinkPaste.Part.Picture -> showable(part.file) ?: run {
                    failed = true
                    FileLinkPaste.Part.Link(part.file.displayName, FileLinks.format(part.file))
                }
            }
        }
        val len = body.length
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        var pictures = 0
        session.group {
            body.setSelection(s, e)
            val style = body.currentCharStyle()
            ready.forEachIndexed { i, p ->
                if (i > 0) body.insertRich(FileLinkPaste.plain(FileLinkPaste.SEPARATOR, style))
                when (p) {
                    is FileLinkPaste.Part.Link -> body.insertRich(FileLinkPaste.linkRun(p.label, p.uri, style))
                    is Shown -> {
                        session.changeInlineObject(p.obj.id, p.obj)
                        body.insertObject(p.obj.id)
                        pictures++
                    }
                }
            }
        }
        if (failed) snackbar.showSnackbar(strings.showFailed)
        if (pictures > 0 && locked && !FileTips.lockedTipSeen(context)) lockedTip = true
    }

    /** Copies [file] into the bundle and builds its inline picture, or null when it can't be read or decoded. */
    private suspend fun showable(file: LibraryFile): Shown? {
        val bytes = runCatching { links.readBytes(file, Limits.MAX_SHOW_IN_NOTE_BYTES) }.getOrNull() ?: return null
        val (w, h) = withContext(Dispatchers.Default) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            o.outWidth to o.outHeight
        }
        if (w <= 0 || h <= 0) return null
        val media = runCatching { session.storeMedia(bytes, FileLinkPaste.mediaExtension(file)) }.getOrNull()
        if (media.isNullOrEmpty()) return null
        val (wPu, hPu) = FileLinkPaste.inlineSize(w, h, Limits.PAGE_WIDTH_PU * INLINE_WIDTH_FRACTION)
        return Shown(ImageObject(UUID.randomUUID().toString(), media, wPu, hPu, libraryFileId = file.id.value))
    }

    // ------------------------------------------------------------------------------------------ taps

    /** A tapped file link (view state, reading mode, or a raw link): opens the file, or explains why it can't. */
    fun open(link: TextLink) {
        val target = FileLinks.parse(link.target) ?: return
        scope.launch {
            val file = runCatching { links.resolve(listOf(target.id))[target.id] }.getOrNull()
            when {
                file == null -> missing = MissingLink(target, link.start, link.end, link.explicit)
                file.isInTrash -> trashed = file
                !openFile(file) -> missing = MissingLink(target, link.start, link.end, link.explicit)
            }
        }
    }

    /** Hands [file] to the viewer for its type (the system one for now); false when its content is gone. */
    private suspend fun openFile(file: LibraryFile): Boolean {
        val uri = links.contentUri(file)
        val readable = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        }
        if (!readable) return false
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            snackbar.showSnackbar(strings.noApp)
        }
        return true
    }

    fun restoreTrashed() {
        val f = trashed ?: return
        trashed = null
        scope.launch {
            runCatching { links.restore(f.id) }
            openFile(f.copy(trashedAt = null))
        }
    }

    /** Missing file dialog's Remove link: an explicit link keeps its label as plain text; a raw link becomes its name. */
    fun removeMissing() {
        val m = missing ?: return
        missing = null
        if (m.end > body.length || m.start >= m.end) return
        if (m.explicit) removeLink(m.start, m.end)
        else session.group {
            body.setSelection(m.start, m.end)
            body.insertRich(FileLinkPaste.plain(m.target.nameHint.orEmpty(), body.currentCharStyle()))
        }
    }

    fun searchMissing() {
        val m = missing ?: return
        missing = null
        navigation?.searchFiles(m.target.nameHint ?: m.target.id.value)
    }

    // ------------------------------------------------------------------------------------------ menus

    /**
     * Extra rows of the floating selection menu while the selection is one stored link (edit mode): Open, Copy link,
     * Edit label, Show in note (pictures), Remove link. Empty otherwise.
     */
    fun linkRows(): List<ExtendedTextToolbar.Extra> {
        val sel = body.selection
        if (sel.collapsed) return emptyList()
        val link = textState.linkInSelection() ?: return emptyList()
        if (sel.min != link.start || sel.max != link.end) return emptyList()
        val target = FileLinks.parse(link.target)
        val label = body.slice(link.start, link.end).plainText()
        val rows = ArrayList<ExtendedTextToolbar.Extra>()
        rows += ExtendedTextToolbar.Extra(strings.open) {
            if (target != null) open(link) else textState.onLinkClick?.invoke(link)
        }
        rows += ExtendedTextToolbar.Extra(strings.copyLink) { copyText(link.target) }
        rows += ExtendedTextToolbar.Extra(strings.editLabel) { editing = EditedLink(link.start, link.end, link.target, label) }
        if (target != null && (FileLinkPaste.looksLikePicture(target.nameHint) || FileLinkPaste.looksLikePicture(label))) {
            rows += ExtendedTextToolbar.Extra(strings.showInNote) { showLinkInNote(target, link.start, link.end) }
        }
        rows += ExtendedTextToolbar.Extra(strings.removeLink) { removeLink(link.start, link.end) }
        return rows
    }

    /**
     * Extra rows when the selection is one inline picture shown from Files: Show as link, and Open in Files when the app
     * provides the Files screen.
     */
    fun pictureRows(): List<ExtendedTextToolbar.Extra> {
        val sel = body.selection
        if (sel.max - sel.min != 1) return emptyList()
        val slice = body.slice(sel.min, sel.max)
        if (slice.text.firstOrNull() != OBJECT_CHAR) return emptyList()
        val objectId = slice.runs.firstOrNull()?.style?.objectId ?: return emptyList()
        val img = session.holder.inlineObject(objectId) as? ImageObject ?: return emptyList()
        val fileId = img.libraryFileId?.let(::FileId) ?: return emptyList()
        val rows = ArrayList<ExtendedTextToolbar.Extra>()
        rows += ExtendedTextToolbar.Extra(strings.showAsLink) { showAsLink(sel.min, objectId, fileId) }
        navigation?.let { nav -> rows += ExtendedTextToolbar.Extra(strings.openInFiles) { nav.openInFiles(fileId) } }
        return rows
    }

    private fun showLinkInNote(target: FileLinks.Target, start: Int, end: Int) {
        scope.launch {
            val file = runCatching { links.resolve(listOf(target.id))[target.id] }.getOrNull()
            val item = FileLinkPaste.items(listOf(target), if (file == null) emptyMap() else mapOf(target.id to file)).single()
            if (!FileLinkPaste.canShow(item)) {
                snackbar.showSnackbar(strings.showFailed)
                return@launch
            }
            insertParts(FileLinkPaste.parts(listOf(item), show = true), start, end)
        }
    }

    private fun showAsLink(offset: Int, objectId: String, fileId: FileId) {
        scope.launch {
            val file = runCatching { links.resolve(listOf(fileId))[fileId] }.getOrNull()
            if (offset >= body.length || body.slice(offset, offset + 1).runs.firstOrNull()?.style?.objectId != objectId) return@launch
            val label = file?.displayName ?: fileId.value
            val uri = if (file != null) FileLinks.format(file) else FileLinks.format(fileId, "")
            session.group {
                body.setSelection(offset, offset + 1)
                body.insertRich(FileLinkPaste.linkRun(label, uri, body.currentCharStyle()))
                session.changeInlineObject(objectId, null)
            }
        }
    }

    fun removeLink(start: Int, end: Int) {
        if (end > body.length || start >= end) return
        body.setSelection(start, end)
        body.applyCharStyle { it.copy(link = null) }
        body.setSelection(end)
    }

    fun applyLabel(newLabel: String) {
        val e = editing ?: return
        editing = null
        val label = newLabel.trim()
        if (label.isEmpty() || label == e.label || e.end > body.length) return
        session.group {
            body.setSelection(e.start, e.end)
            body.insertRich(FileLinkPaste.linkRun(label, e.uri, body.currentCharStyle()))
        }
    }

    private fun copyText(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(text, text))
    }

    fun dismissLockedTip() {
        lockedTip = false
        FileTips.markLockedTipSeen(context)
    }

    fun dispose() = menu.hide()

    private companion object {
        /** A shown picture is at most this part of the page width, like Insert > Image. */
        const val INLINE_WIDTH_FRACTION = 0.7f
    }
}

/** One-time tips of the Files features (device-local, like the editor's other guides). */
internal object FileTips {
    private const val PREFS = "composer_file_tips"
    private const val LOCKED = "locked_show_in_note"

    fun lockedTipSeen(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(LOCKED, false)

    fun markLockedTipSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(LOCKED, true).apply()
    }
}

/** The dialogs of [FileLinkController] and the Files picker sheet (Insert > From Files). */
@Composable
internal fun FileLinkDialogs(c: FileLinkController) {
    val colors = SameSameTheme.colors
    val cancel = stringResource(android.R.string.cancel)
    c.trashed?.let {
        AppDialog(
            onDismissRequest = { c.trashed = null },
            dismissLabel = cancel,
            confirmLabel = stringResource(R.string.feature_composer_files_restore),
            onConfirm = c::restoreTrashed,
            atBottom = true,
        ) { Text(stringResource(R.string.feature_composer_files_in_trash), color = colors.textPrimary, fontSize = 16.sp) }
    }
    c.missing?.let { m ->
        val name = m.target.nameHint ?: m.target.id.value
        val nav = c.navigation
        AppDialog(
            onDismissRequest = { c.missing = null },
            dismissLabel = if (nav != null) stringResource(R.string.feature_composer_files_search, name) else cancel,
            onDismissClick = if (nav != null) c::searchMissing else ({ c.missing = null }),
            confirmLabel = stringResource(R.string.feature_composer_files_remove_link),
            onConfirm = c::removeMissing,
            atBottom = true,
        ) { Text(stringResource(R.string.feature_composer_files_not_found, name), color = colors.textPrimary, fontSize = 16.sp) }
    }
    c.editing?.let { e ->
        var field by remember(e) { mutableStateOf(TextFieldValue(e.label, TextRange(0, e.label.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(e) { runCatching { focus.requestFocus() } }
        AppDialog(
            onDismissRequest = { c.editing = null },
            dismissLabel = cancel,
            confirmLabel = stringResource(R.string.feature_composer_done),
            onConfirm = { c.applyLabel(field.text) },
            confirmEnabled = field.text.isNotBlank(),
            title = stringResource(R.string.feature_composer_files_edit_label),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(bottom = 12.dp),
            )
        }
    }
    if (c.lockedTip) {
        AppDialog(
            onDismissRequest = c::dismissLockedTip,
            dismissLabel = cancel,
            confirmLabel = stringResource(R.string.feature_composer_files_ok),
            onConfirm = c::dismissLockedTip,
            atBottom = true,
        ) { Text(stringResource(R.string.feature_composer_files_locked_tip), color = colors.textPrimary, fontSize = 16.sp) }
    }
    val picker = LocalLibraryFilePicker.current
    if (c.pickerOpen && picker != null) picker(c::insertPicked) { c.pickerOpen = false }
}

/** [FileLinkController] for the screen, its labels resolved here. */
@Composable
internal fun rememberFileLinkController(
    links: NoteFileLinks,
    view: View,
    scope: CoroutineScope,
    session: EditorSession,
    body: BodyText,
    textState: RichTextEditorState,
    snackbar: SnackbarHostState,
): FileLinkController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val strings = FileLinkController.Strings(
        pasteAsLink = stringResource(R.string.feature_composer_files_paste_as_link),
        showInNote = stringResource(R.string.feature_composer_files_show_in_note),
        showImages = stringResource(R.string.feature_composer_files_show_images),
        open = stringResource(R.string.feature_composer_files_open),
        copyLink = stringResource(R.string.feature_composer_files_copy_link),
        editLabel = stringResource(R.string.feature_composer_files_edit_label),
        removeLink = stringResource(R.string.feature_composer_files_remove_link),
        showAsLink = stringResource(R.string.feature_composer_files_show_as_link),
        openInFiles = stringResource(R.string.feature_composer_files_open_in_files),
        noApp = stringResource(R.string.feature_composer_files_no_app),
        showFailed = stringResource(R.string.feature_composer_files_show_failed),
    )
    val c = remember(session, links) { FileLinkController(context, view, scope, links, session, body, textState, snackbar, strings) }
    androidx.compose.runtime.DisposableEffect(c) { onDispose { c.dispose() } }
    return c
}
