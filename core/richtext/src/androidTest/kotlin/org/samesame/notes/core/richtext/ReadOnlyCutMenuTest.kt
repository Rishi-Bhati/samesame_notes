package org.samesame.notes.core.richtext

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.richtext.layout.SimplePageGeometry
import org.samesame.notes.core.richtext.ui.RichTextEditorState
import org.samesame.notes.core.richtext.ui.RichTextToolbarEffect

/**
 * Bug 8 (E1): a selection in the view state showed only Copy / Select all / Share / Web search because Cut was gated on
 * `enabled`. The view state must offer Cut too; the host's [RichTextEditorState.onReadOnlyCut] enters edit mode and does
 * the cut, so the library itself must not touch the body.
 */
@RunWith(AndroidJUnit4::class)
class ReadOnlyCutMenuTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val toolbar = RecordingToolbar()

    private fun viewState(body: BodyText): RichTextEditorState =
        RichTextEditorState(body, SimplePageGeometry(1, continuous = true), rule.activity).also {
            it.enabled = false
            it.readOnlySelect = true
        }

    @Test fun aViewStateSelectionOffersCutHandledByTheHost() {
        val body = BodyText(RichText.of("one\ntwo"))
        val state = viewState(body)
        var hostCuts = 0
        state.onReadOnlyCut = { hostCuts++ }
        state.selectAll()

        rule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) { RichTextToolbarEffect(state) { null } }
        }
        rule.waitForIdle()

        val cut = toolbar.onCut
        assertThat(cut).isNotNull()
        rule.runOnIdle { cut!!.invoke() }
        // View state: the library hands the cut to the host and leaves the body untouched.
        assertThat(hostCuts).isEqualTo(1)
        assertThat(body.length).isEqualTo(7)
    }

    @Test fun withoutAHostHandlerTheViewStateDoesNotOfferCut() {
        val state = viewState(BodyText(RichText.of("one")))
        state.selectAll()

        rule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) { RichTextToolbarEffect(state) { null } }
        }
        rule.waitForIdle()

        assertThat(toolbar.onCut).isNull()
        assertThat(toolbar.onCopy).isNotNull()
        assertThat(toolbar.onSelectAll).isNotNull()
    }

    @Test fun editModeCutStillCutsAndPutsPlainTextOnTheClipboard() {
        val body = BodyText(RichText.of("one\ntwo"))
        val state = RichTextEditorState(body, SimplePageGeometry(1, continuous = true), rule.activity)
        state.enabled = true
        state.isFocused = true
        state.selectAll()

        rule.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) { RichTextToolbarEffect(state) { null } }
        }
        rule.waitForIdle()

        val cut = toolbar.onCut
        assertThat(cut).isNotNull()
        rule.runOnIdle { cut!!.invoke() }
        assertThat(body.length).isEqualTo(0)
        val clip = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertThat(clip.primaryClip?.getItemAt(0)?.text?.toString()).isEqualTo("one\ntwo")
    }
}

private class RecordingToolbar : TextToolbar {
    var onCopy: (() -> Unit)? = null
    var onCut: (() -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onSelectAll: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        onCopy = onCopyRequested
        onCut = onCutRequested
        onPaste = onPasteRequested
        onSelectAll = onSelectAllRequested
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
    }
}
