package org.samesame.notes.feature.textdoc

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.MarkdownEditMode
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.richtext.markdown.taskBoxRect
import java.io.InputStream

/**
 * A document another app opened with a read-only grant (most file managers): Markdown is styled like any other (Live by
 * default), task boxes don't toggle, links still ask before opening, and the notice says "Showing first 5 MB" only when
 * the file was really cut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadOnlyMarkdownTest {
    @get:Rule val rule = createComposeRule()

    private val source = "# Packing list\n\n- [x] Passport\n- [ ] Charger\n**Train tickets**\n[site](https://example.com)\n"
    private val uri = Uri.parse("content://media/external/file/42")

    private fun viewModel(text: String, mode: MarkdownEditMode = MarkdownEditMode.Live) =
        TextDocViewModel(NoFiles, OneUri(text), NoNotes, DefaultMode(mode)).apply { ioDispatcher = Dispatchers.Main }

    private fun withMain(block: suspend () -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            runTest { block() }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.first()
    }

    private fun AnnotatedString.hasSpan(start: Int, end: Int, test: (androidx.compose.ui.text.SpanStyle) -> Boolean) =
        spanStyles.any { it.start <= start && it.end >= end && test(it.item) }

    private fun showEditor(state: TextDocUiState, onText: (String) -> Unit = {}) {
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                TextDocEditor(state, live = state.isLiveMarkdown, onText = onText, modifier = Modifier.testTag("editor"))
            }
        }
        rule.waitForIdle()
    }

    @Test fun `a read-only Markdown file opens in Live with its markers hidden`() {
        val vm = viewModel(source)
        withMain { vm.load(uri, writable = false, displayName = "packing.md", mimeType = "text/markdown").join() }
        val state = vm.uiState.value
        assertThat(state.isReadOnly).isTrue()
        assertThat(state.isMarkdown).isTrue()
        assertThat(state.markdownMode).isEqualTo(MarkdownViewMode.Live)
        assertThat(state.isLiveMarkdown).isTrue()

        showEditor(state)
        // Even after a tap (the field takes focus), a read-only document keeps its markers hidden.
        rule.onNodeWithTag("editor").performClick()
        rule.waitForIdle()
        val styled = layout().layoutInput.text
        assertThat(styled.text).isEqualTo(source)
        assertThat(styled.hasSpan(0, 2) { it.color == Color.Transparent }).isTrue()
        assertThat(styled.hasSpan(2, 14) { it.fontWeight == FontWeight.Bold }).isTrue()
        val bold = source.indexOf("**Train")
        assertThat(styled.hasSpan(bold, bold + 2) { it.color == Color.Transparent }).isTrue()
        assertThat(styled.hasSpan(bold + 2, bold + 15) { it.fontWeight == FontWeight.Bold }).isTrue()
    }

    @Test fun `the Source default still applies to read-only files`() {
        val vm = viewModel(source, MarkdownEditMode.Source)
        withMain { vm.load(uri, writable = false, displayName = "packing.md").join() }
        assertThat(vm.uiState.value.markdownMode).isEqualTo(MarkdownViewMode.Source)
        assertThat(vm.uiState.value.isLiveMarkdown).isFalse()
    }

    @Test fun `a read-only document ignores edits, task ticks, undo and replace`() {
        val vm = viewModel(source)
        withMain {
            vm.load(uri, writable = false, displayName = "packing.md").join()
            vm.updateText("changed")
            vm.replaceText(source.replace("- [ ] Charger", "- [x] Charger"))
            vm.find("Passport")
            vm.setReplaceQuery("Visa")
            vm.replaceAll()
            vm.undo()
        }
        assertThat(vm.uiState.value.text).isEqualTo(source)
        assertThat(vm.uiState.value.isModified).isFalse()
    }

    @Test fun `tapping a task box in a read-only document does not toggle it`() {
        val edits = mutableListOf<String>()
        showEditor(
            TextDocUiState(text = source, isMarkdown = true, markdownMode = MarkdownViewMode.Live, isLoading = false, isReadOnly = true),
            onText = { edits += it },
        )
        val box = source.indexOf("[ ]")
        rule.onNodeWithTag("editor").performTouchInput { click(taskBoxRect(layout(), box, 10f).center) }
        rule.waitForIdle()
        // The tap may move the caret (reported with the same text), but nothing is ever ticked.
        assertThat(edits.filter { it != source }).isEmpty()
        assertThat(rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsProperties.EditableText].text).isEqualTo(source)
    }

    @Test fun `a link in a read-only document asks before opening, with Cancel instead of Edit`() {
        showEditor(TextDocUiState(text = source, isMarkdown = true, markdownMode = MarkdownViewMode.Live, isLoading = false, isReadOnly = true))
        val at = source.indexOf("site") + 1
        val center = layout().getBoundingBox(at).center
        rule.onNodeWithTag("editor").performTouchInput { click(center) }
        rule.waitForIdle()
        rule.onNodeWithText("https://example.com").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test fun `a complete read-only file says Read-only, a cut one says Showing first 5 MB`() {
        val small = viewModel(source)
        withMain { small.load(uri, writable = false, displayName = "packing.md").join() }
        assertThat(small.uiState.value.readOnlyNotice).isEqualTo(ReadOnlyNotice.ReadOnly)
        assertThat(small.uiState.value.isTruncated).isFalse()

        val big = viewModel("a".repeat((Limits.MAX_TEXTDOC_EDIT_BYTES + 10).toInt()))
        withMain { big.load(uri, writable = true, displayName = "big.md").join() }
        assertThat(big.uiState.value.isReadOnly).isTrue()
        assertThat(big.uiState.value.readOnlyNotice).isEqualTo(ReadOnlyNotice.Truncated)

        assertThat(TextDocUiState(isReadOnly = false).readOnlyNotice).isEqualTo(ReadOnlyNotice.None)
        assertThat(TextDocUiState(isReadOnly = true, isLockedNote = true).readOnlyNotice).isEqualTo(ReadOnlyNotice.LockedNote)
    }

    @Test fun `plain text and HTML read-only files keep their own views`() {
        val txt = viewModel("# not a heading\n")
        withMain { txt.load(uri, writable = false, displayName = "notes.txt", mimeType = "text/plain").join() }
        assertThat(txt.uiState.value.isMarkdown).isFalse()
        assertThat(txt.uiState.value.isLiveMarkdown).isFalse()
        assertThat(txt.uiState.value.readOnlyNotice).isEqualTo(ReadOnlyNotice.ReadOnly)

        val html = viewModel("<p>hi</p>")
        withMain {
            html.load(uri, writable = false, displayName = "page.html", mimeType = "text/html").join()
            html.setHtmlPreview(true)
        }
        assertThat(html.uiState.value.canShowRendered).isTrue()
        assertThat(html.uiState.value.isHtmlPreview).isTrue()
        assertThat(html.uiState.value.readOnlyNotice).isEqualTo(ReadOnlyNotice.ReadOnly)
    }

    @Test fun `the banner text and its Edit as note action`() {
        var notice by mutableStateOf(ReadOnlyNotice.ReadOnly)
        var taps = 0
        rule.setContent {
            SameSameTheme(darkTheme = false) {
                ReadOnlyBanner(notice, onEditAsNote = if (notice == ReadOnlyNotice.ReadOnly) ({ taps++ }) else null)
            }
        }
        rule.onNodeWithText("Read-only").assertExists()
        rule.onNodeWithText("Edit as note").performClick()
        assertThat(taps).isEqualTo(1)

        notice = ReadOnlyNotice.Truncated
        rule.waitForIdle()
        rule.onNodeWithText("Showing first 5 MB · Read-only").assertExists()
        rule.onNodeWithText("Edit as note").assertDoesNotExist()

        notice = ReadOnlyNotice.None
        rule.waitForIdle()
        rule.onNodeWithText("Read-only", substring = true).assertDoesNotExist()
    }

    private object NoFiles : TextDocFileStore {
        override suspend fun get(fileId: FileId): LibraryFile? = null
        override fun contentUri(file: LibraryFile): Uri = Uri.EMPTY
        override suspend fun updateContent(fileId: FileId, open: () -> InputStream): LibraryFile = error("unused")
    }

    private class OneUri(private val text: String) : TextDocUriStore {
        override suspend fun read(uri: Uri): InputStream = text.byteInputStream()
        override suspend fun write(uri: Uri, bytes: ByteArray) = error("read-only")
    }

    private object NoNotes : TextDocNoteStore {
        override suspend fun readMarkdown(noteId: String): String? = null
        override suspend fun writeMarkdown(noteId: String, text: String) = Unit
    }

    private class DefaultMode(private val mode: MarkdownEditMode) : TextDocMarkdownModeStore {
        override suspend fun defaultMode() = mode
        override suspend fun modeFor(documentKey: String): MarkdownViewMode? = null
        override suspend fun setModeFor(documentKey: String, mode: MarkdownViewMode) = Unit
    }
}
