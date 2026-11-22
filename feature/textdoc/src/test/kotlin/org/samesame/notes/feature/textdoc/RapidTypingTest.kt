package org.samesame.notes.feature.textdoc

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.model.MarkdownEditMode
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.NoteKind
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.LibraryFile
import java.io.InputStream

/**
 * Typing faster than the screen recomposes (adb `input text`, a fast typist, a hardware keyboard) must never lose
 * characters: the editor's text is only overwritten by changes that did not come from the editor itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RapidTypingTest {
    @get:Rule val rule = createComposeRule()

    private val start = "# Notes\n\nSome **bold** text.\n- [ ] task\n"

    private fun editor(): SemanticsNodeInteraction = rule.onNode(hasSetTextAction())

    private fun editorText(): String = editor().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun open(mode: MarkdownEditMode): TextDocViewModel {
        val vm = TextDocViewModel(NoFiles, NoUris, OneNote(start), DefaultMode(mode)).apply { ioDispatcher = Dispatchers.Main }
        rule.setContent {
            SameSameTheme(darkTheme = true) { TextDocRoute(noteId = "n1", viewModel = vm) }
        }
        rule.waitUntil(5_000) { !vm.uiState.value.isLoading }
        rule.waitForIdle()
        editor().performClick()
        rule.waitForIdle()
        return vm
    }

    /** 200 characters as separate commits, with frames only now and then (no idle between most of them). */
    private fun burst(vm: TextDocViewModel, every: Int = 7) {
        val typed = buildString { repeat(25) { append("abcdefg ") } }
        val before = editorText()
        rule.mainClock.autoAdvance = false
        typed.forEachIndexed { i, c ->
            editor().performTextInput(c.toString())
            if (i % every == every - 1) rule.mainClock.advanceTimeByFrame()
        }
        // A few hardware key presses on top.
        editor().performKeyInput { pressKey(Key.X); pressKey(Key.Y); pressKey(Key.Z) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val result = editorText()
        assertThat(result.length).isEqualTo(before.length + typed.length + 3)
        // Whatever the caret position, the typed run arrives in order and nothing else changes.
        val at = result.indexOf(typed)
        assertThat(at).isAtLeast(0)
        assertThat(result.removeRange(at, at + typed.length).replace("xyz", "")).isEqualTo(before)
        assertThat(vm.uiState.value.text).isEqualTo(result)
    }

    @Test fun `rapid typing in Live mode keeps every character`() = burst(open(MarkdownEditMode.Live))

    @Test fun `rapid typing in Source mode keeps every character`() = burst(open(MarkdownEditMode.Source))

    @Test fun `rapid typing with a frame after every key keeps every character`() = burst(open(MarkdownEditMode.Live), every = 1)

    /**
     * The screen's state reaches the editor later than the editor's own input (it is collected from the view model).
     * Feed the editor a state that is always a few edits behind while typing and check nothing typed is lost.
     */
    private fun laggingParent(live: Boolean) {
        val sent = mutableListOf(start)
        var parent by mutableStateOf(
            TextDocUiState(text = start, isMarkdown = true, markdownMode = if (live) MarkdownViewMode.Live else MarkdownViewMode.Source, isLoading = false),
        )
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                TextDocEditor(parent, live = live, onText = { sent += it }, modifier = Modifier.testTag("editor"))
            }
        }
        rule.onNodeWithTag("editor").performClick()
        rule.waitForIdle()
        val typed = buildString { repeat(25) { append("hijklmn ") } }
        val shownTexts = mutableListOf<String>()
        val expected = mutableListOf<String>()
        rule.mainClock.autoAdvance = false
        typed.forEachIndexed { i, c ->
            rule.onNodeWithTag("editor").performTextInput(c.toString())
            if (i % 3 == 2) {
                // The parent catches up only to an older edit, then a frame runs.
                parent = parent.copy(text = sent[(sent.size - 3).coerceAtLeast(0)], isModified = true)
                repeat(3) {
                    rule.mainClock.advanceTimeByFrame()
                    // The stale state must never replace what is on screen (the IME would then type over it).
                    shownTexts += rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
                    expected += sent.last()
                }
            }
        }
        parent = parent.copy(text = sent.last())
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        val result = rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertThat(result.length).isEqualTo(start.length + typed.length)
        val at = result.indexOf(typed)
        assertThat(at).isAtLeast(0)
        assertThat(result.removeRange(at, at + typed.length)).isEqualTo(start)
        assertThat(sent.last()).isEqualTo(result)
        assertThat(shownTexts).isEqualTo(expected)
    }

    @Test fun `undo and other outside changes still reach the editor`() {
        var parent by mutableStateOf(
            TextDocUiState(text = start, isMarkdown = true, markdownMode = MarkdownViewMode.Live, isLoading = false),
        )
        rule.setContent {
            SameSameTheme(darkTheme = true) {
                TextDocEditor(parent, live = true, onText = {}, modifier = Modifier.testTag("editor"))
            }
        }
        parent = parent.copy(text = "# undone\n", externalEdits = parent.externalEdits + 1)
        rule.waitForIdle()
        assertThat(rule.onNodeWithTag("editor").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            .isEqualTo("# undone\n")
    }

    @Test fun `a lagging screen state never overwrites newer input in Live mode`() = laggingParent(live = true)

    @Test fun `a lagging screen state never overwrites newer input in Source mode`() = laggingParent(live = false)

    private object NoFiles : TextDocFileStore {
        override suspend fun get(fileId: FileId): LibraryFile? = null
        override fun contentUri(file: LibraryFile): Uri = Uri.EMPTY
        override suspend fun updateContent(fileId: FileId, open: () -> InputStream): LibraryFile = error("unused")
    }

    private object NoUris : TextDocUriStore {
        override suspend fun read(uri: Uri): InputStream = error("unused")
        override suspend fun write(uri: Uri, bytes: ByteArray) = Unit
    }

    private class OneNote(var text: String) : TextDocNoteStore {
        override suspend fun readMarkdown(noteId: String): String = text
        override suspend fun writeMarkdown(noteId: String, text: String) {
            this.text = text
        }
        override suspend fun getNote(noteId: String): Note = Note(
            id = NoteId(noteId),
            title = "Notes",
            previewText = "",
            folderId = null,
            isFavorite = false,
            isLocked = false,
            pageMode = PageMode.Infinite,
            backgroundColor = 0,
            createdAt = 0,
            modifiedAt = 0,
            lastOpenedAt = 0,
            trashedAt = null,
            kind = NoteKind.Markdown,
        )
    }

    private class DefaultMode(private val mode: MarkdownEditMode) : TextDocMarkdownModeStore {
        override suspend fun defaultMode() = mode
        override suspend fun modeFor(documentKey: String): MarkdownViewMode? = null
        override suspend fun setModeFor(documentKey: String, mode: MarkdownViewMode) = Unit
    }
}
