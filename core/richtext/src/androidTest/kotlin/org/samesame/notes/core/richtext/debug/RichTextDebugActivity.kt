package org.samesame.notes.core.richtext.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.richtext.ListKind
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextAlign
import org.samesame.notes.core.richtext.BodyText
import org.samesame.notes.core.richtext.RichTextColors
import org.samesame.notes.core.richtext.edit.TextEditEvent
import org.samesame.notes.core.richtext.layout.SimplePageGeometry
import org.samesame.notes.core.richtext.layout.ViewTransform
import org.samesame.notes.core.richtext.ui.RichTextEditor
import org.samesame.notes.core.richtext.ui.rememberRichTextEditorState

/** Debug host: one infinite page (or short fixed pages to try page breaks) with the body editor. */
class RichTextDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface { RichTextDebugScreen(dark) }
            }
        }
    }
}

private enum class Mode { Infinite, ShortPages, A4 }

@Composable
fun RichTextDebugScreen(dark: Boolean) {
    val body = remember { BodyText(RichText.of("")) }
    var mode by remember { mutableStateOf(Mode.Infinite) }
    var pageCount by remember { mutableIntStateOf(1) }
    val geometry = when (mode) {
        Mode.Infinite -> SimplePageGeometry(pageCount, continuous = true)
        Mode.ShortPages -> SimplePageGeometry(pageCount, pageHeight = 420f)
        Mode.A4 -> SimplePageGeometry(pageCount)
    }
    val state = rememberRichTextEditorState(body, geometry)
    state.colors = if (dark) RichTextColors.Dark else RichTextColors.Light
    state.hint = "Tap Title to add a title or select a folder. Notes save automatically."

    // Minimal local history to exercise undo/redo application (WP-H owns the real one).
    val undo = remember { mutableStateListOf<TextEditEvent>() }
    val redo = remember { mutableStateListOf<TextEditEvent>() }
    var lastEvent by remember { mutableStateOf("") }
    DisposableEffect(body) {
        val l: (TextEditEvent) -> Unit = { e ->
            val prev = undo.lastOrNull()
            val merged = if (prev is TextEditEvent.TextEdit && e is TextEditEvent.TextEdit) prev.mergeWith(e) else null
            if (merged != null && !merged.inserted.text.endsWith(" ")) undo[undo.lastIndex] = merged else undo += e
            redo.clear()
            lastEvent = describe(e)
        }
        body.addEditListener(l)
        onDispose { body.removeEditListener(l) }
    }
    // Append pages the text needs (the session does this in the app).
    val required = state.engine.requiredPageCount.also { state.revision }
    if (required > pageCount) pageCount = required

    var widthPx by remember { mutableFloatStateOf(1080f) }
    var scroll by remember { mutableFloatStateOf(0f) }
    val scale = widthPx / Limits.PAGE_WIDTH_PU
    state.transform = ViewTransform(scale, 0f, -scroll)
    val scrollState = rememberScrollableState { d ->
        val maxScroll = (geometry.pageRect(maxOf(pageCount, required) - 1).bottom * scale).coerceAtLeast(0f)
        val old = scroll
        scroll = (scroll - d).coerceIn(0f, maxScroll)
        if (old != scroll) state.dismissSelectionUi()
        old - scroll
    }
    val pageColor = Color(state.colors.page)
    Column(
        Modifier.fillMaxSize().background(if (dark) Color(0xFF1B1B1B) else Color(0xFFE6E6E6)).safeDrawingPadding().imePadding(),
    ) {
        RichTextEditor(
            state,
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { widthPx = it.width.toFloat() }
                .scrollable(scrollState, Orientation.Vertical).testTag("editor"),
            pageBackground = { _, r -> drawRect(pageColor, r.topLeft, r.size) },
        )
        Text(
            "${mode.name} · pages $pageCount · sel ${body.selection.start}-${body.selection.end} · $lastEvent".also { state.revision },
            fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            B("B") { body.applyCharStyle { it.copy(bold = !it.bold) } }
            B("I") { body.applyCharStyle { it.copy(italic = !it.italic) } }
            B("U") { body.applyCharStyle { it.copy(underline = !it.underline) } }
            B("S") { body.applyCharStyle { it.copy(strikethrough = !it.strikethrough) } }
            B("A+") { body.applyCharStyle { it.copy(fontSize = (it.fontSize ?: 17f) + 3f) } }
            B("Red") { body.applyCharStyle { it.copy(color = if (it.color == null) 0xFFE53935 else null) } }
            B("Hl") { body.applyCharStyle { it.copy(highlight = if (it.highlight == null) 0x80FFEB3B else null) } }
            B("☐") { body.toggleList(ListKind.Checklist) }
            B("•") { body.toggleList(ListKind.Bullet) }
            B("1.") { body.toggleList(ListKind.Numbered) }
            B("→") { body.indent() }
            B("←") { body.outdent() }
            B("≡") {
                val a = body.currentParagraphStyle().align
                body.setAlign(TextAlign.entries[(a.ordinal + 1) % TextAlign.entries.size])
            }
            B("Undo") { undo.removeLastOrNull()?.let { body.undo(it); redo += it } }
            B("Redo") { redo.removeLastOrNull()?.let { body.redo(it); undo += it } }
            B(mode.name) {
                mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
                pageCount = 1
            }
        }
    }
}

@Composable
private fun B(label: String, onClick: () -> Unit) = TextButton(onClick = onClick) { Text(label) }

private fun describe(e: TextEditEvent): String = when (e) {
    is TextEditEvent.TextEdit -> "edit@${e.start} -\"${e.removed.text}\" +\"${e.inserted.text}\"${if (e.composed) " (ime)" else ""}"
    is TextEditEvent.CharStyleChange -> "style@${e.start}+${e.before.length}"
    is TextEditEvent.ParagraphStyleChange -> "para ${e.firstParagraph}: ${e.after.map { it.list }}"
}
