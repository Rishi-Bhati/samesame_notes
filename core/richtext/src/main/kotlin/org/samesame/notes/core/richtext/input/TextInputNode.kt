package org.samesame.notes.core.richtext.input

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.samesame.notes.core.richtext.ui.RichTextEditorState

/**
 * Connects the body to the IME while focused (doc §6: `PlatformTextInputModifierNode` +
 * `BaseInputConnection`). Starts a text input session on focus, ends it on blur or detach.
 */
class TextInputNode(var state: RichTextEditorState) :
    Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {

    private var session: Job? = null

    override fun onFocusEvent(focusState: FocusState) {
        state.isFocused = focusState.isFocused
        if (focusState.isFocused && state.enabled) start() else stop()
    }

    private fun start() {
        if (session?.isActive == true) return
        session = coroutineScope.launch {
            establishTextInputSession {
                val view = view
                state.attachInput(view)
                try {
                    startInputMethod(object : PlatformTextInputMethodRequest {
                        override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                            state.createInputConnection(view, outAttributes)
                    })
                } finally {
                    state.detachInput(view)
                }
            }
        }
    }

    private fun stop() {
        session?.cancel()
        session = null
    }

    override fun onDetach() {
        stop()
    }
}

private data class TextInputElement(val state: RichTextEditorState) : ModifierNodeElement<TextInputNode>() {
    override fun create() = TextInputNode(state)
    override fun update(node: TextInputNode) {
        node.state = state
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "richTextInput"
    }
}

/** Makes the element focusable and routes the IME to [state]'s body while it has focus. */
fun Modifier.richTextInput(state: RichTextEditorState): Modifier =
    this.then(TextInputElement(state)).focusRequester(state.focusRequester).focusable()
