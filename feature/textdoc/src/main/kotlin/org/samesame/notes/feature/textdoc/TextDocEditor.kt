package org.samesame.notes.feature.textdoc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.component.AppDialog
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.richtext.markdown.LiveDecorations
import org.samesame.notes.core.richtext.markdown.LiveLink
import org.samesame.notes.core.richtext.markdown.LiveMarkdownDocument
import org.samesame.notes.core.richtext.markdown.LiveMarkdownStyle
import org.samesame.notes.core.richtext.markdown.LiveMarkdownView
import org.samesame.notes.core.richtext.markdown.MarkdownParser
import org.samesame.notes.core.richtext.markdown.decorations
import org.samesame.notes.core.richtext.markdown.drawLiveDecorations
import org.samesame.notes.core.richtext.markdown.styled
import org.samesame.notes.core.richtext.markdown.taskBoxRect
import org.samesame.notes.core.richtext.markdown.touchesPrefix
import kotlin.math.abs

/**
 * The text editor. With [live] the Markdown syntax is styled in place: the text field keeps the exact source (an
 * identity [VisualTransformation] only adds styles), markers are hidden except on the caret's line or code block,
 * and bullets, task boxes, quote bars, code backgrounds and rules are drawn behind the text. Tapping a drawn task box
 * toggles it in the source; tapping a link on a line that is not being edited asks before opening it.
 *
 * Without [live] it is the plain source editor.
 */
@Composable
internal fun TextDocEditor(
    state: TextDocUiState,
    live: Boolean,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SameSameTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(state.text)) }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pendingLink by remember { mutableStateOf<LiveLink?>(null) }
    val holder = remember { LiveHolder() }

    LaunchedEffect(state.text) {
        if (field.text != state.text) field = field.copy(text = state.text, selection = TextRange(state.text.length))
    }
    LaunchedEffect(state.activeMatchIndex, state.findQuery) {
        val match = FindReplace.matches(field.text, state.findQuery).getOrNull(state.activeMatchIndex)
        if (match != null) field = field.copy(selection = TextRange(match.first, match.last + 1))
    }

    // ---- live analysis ------------------------------------------------------------------------------------------
    if (live && holder.doc == null && field.text.length <= SYNC_ANALYZE_CHARS) {
        holder.doc = LiveMarkdownDocument.analyze(field.text)
    }
    LaunchedEffect(live, holder.requests) {
        if (!live || (holder.doc != null && holder.requests <= holder.served)) return@LaunchedEffect
        holder.served = holder.requests
        val snapshot = field.text
        val fresh = withContext(Dispatchers.Default) { LiveMarkdownDocument.analyze(snapshot) }
        holder.doc = fresh
        holder.version++
    }
    val complete = remember(field.text, holder.version, live) { if (live) holder.doc?.update(field.text) ?: true else true }
    LaunchedEffect(complete, holder.version) { if (!complete) holder.requests++ }

    val doc = if (live) holder.doc else null
    val paddingTop = with(density) { EditorPaddingVertical.toPx() }
    val centerOffset by remember {
        derivedStateOf {
            val l = layout ?: return@derivedStateOf 0
            val y = (vertical.value + vertical.viewportSize / 2f - paddingTop).coerceIn(0f, l.size.height.toFloat())
            l.getLineStart(l.getLineForVerticalPosition(y)) / WINDOW_BUCKET_CHARS
        }
    }
    val style = remember(colors) { LiveMarkdownStyle(colors) }
    var windowed = false
    val view = if (doc == null) null else {
        val budget = (TOKEN_WORK_BUDGET / doc.lineCount.coerceAtLeast(1)).coerceIn(MIN_TOKENS, MAX_TOKENS)
        windowed = doc.tokenCount > budget
        val window = doc.window(doc.lineAt((centerOffset * WINDOW_BUCKET_CHARS).coerceAtMost(doc.text.length)), budget)
        val selection = if (focused) field.selection else null
        val revealed = if (selection != null) doc.revealLines(selection.min, selection.max) else IntRange.EMPTY
        LiveMarkdownView(window, revealed, selection)
    }
    if (view != null && view.window != holder.lastWindow) {
        // Only a partial window can restyle lines above the viewport (a whole-document one changes with the text).
        holder.windowMoved = windowed && holder.lastWindow != null
        holder.lastWindow = view.window
    }
    val styled: AnnotatedString? = remember(field.text, holder.version, view, style) {
        if (doc != null && view != null && doc.text == field.text) doc.styled(view, style) else null
    }
    val decorations: LiveDecorations? = remember(field.text, holder.version, view) {
        if (doc != null && view != null && doc.text == field.text) doc.decorations(view) else null
    }
    val transformation = remember(styled) { if (styled == null) VisualTransformation.None else StyledText(styled) }

    // ---- taps on task boxes and links --------------------------------------------------------------------------
    val boxSize = with(density) { TaskBoxSize.toPx() }
    val touchSlack = with(density) { 12.dp.toPx() }
    val hitTest = rememberUpdatedState { position: Offset ->
        hit(position, doc, view, layout, state.isReadOnly, boxSize, touchSlack)
    }
    val onHit = rememberUpdatedState { target: Hit ->
        when (target) {
            is Hit.Task -> {
                val text = field.text
                val checked = text.getOrNull(target.bracket + 1)?.let { it == 'x' || it == 'X' } ?: false
                val updated = MarkdownParser.withTaskChecked(text, target.bracket, !checked)
                if (updated != text) {
                    holder.keepSelection = field.selection
                    holder.keepSelectionUntil = SystemClock.uptimeMillis() + KEEP_SELECTION_MS
                    field = field.copy(text = updated)
                    onText(updated)
                }
            }
            is Hit.Link -> pendingLink = target.link
        }
    }

    val textStyle = TextStyle(
        fontFamily = if (state.isMonospace) FontFamily.Monospace else FontFamily.SansSerif,
        fontSize = if (state.isMonospace) 13.sp else 16.sp,
        // Live: natural line heights, so headings get the room they need (a fixed height would overlap them).
        lineHeight = if (live) TextUnit.Unspecified else if (state.isMonospace) 19.sp else 24.sp,
        color = colors.textPrimary,
    )
    val scroll = Modifier.verticalScroll(vertical).then(if (state.isWrap) Modifier else Modifier.horizontalScroll(horizontal))
    BasicTextField(
        value = field,
        onValueChange = { value ->
            // A tap on a task box may also reach the text field; keep the caret where it was.
            val keep = holder.keepSelection
            if (keep != null && value.text == field.text && value.selection != keep && value.composition == null &&
                SystemClock.uptimeMillis() < holder.keepSelectionUntil
            ) {
                return@BasicTextField
            }
            holder.keepSelection = null
            field = value
            onText(value.text)
        },
        modifier = modifier.then(scroll).imePadding()
            .padding(horizontal = EditorPaddingHorizontal, vertical = EditorPaddingVertical)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused },
        readOnly = state.isReadOnly,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = transformation,
        onTextLayout = { result ->
            val previous = layout
            if (holder.windowMoved && previous != null) {
                // Restyling lines above the viewport changes their height: keep the first visible line in place.
                holder.windowMoved = false
                val y = vertical.value - paddingTop
                if (y > 0f) {
                    val anchor = previous.getOffsetForPosition(Offset(0f, y)).coerceAtMost(result.layoutInput.text.length)
                    val before = previous.getLineTop(previous.getLineForOffset(anchor))
                    val after = result.getLineTop(result.getLineForOffset(anchor))
                    val delta = after - before
                    if (abs(delta) > 0.5f) scope.launch { vertical.scrollBy(delta) }
                }
            }
            layout = result
        },
        decorationBox = { inner ->
            Box(
                Modifier
                    .drawBehind {
                        val l = layout
                        if (decorations != null && l != null && !decorations.isEmpty) drawLiveDecorations(l, decorations, colors)
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val target = hitTest.value(down.position) ?: return@awaitEachGesture
                            down.consume()
                            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var result: PointerInputChange? = null
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUpIgnoreConsumed()) {
                                        result = change
                                        break
                                    }
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                }
                                result
                            }
                            if (up != null) {
                                up.consume()
                                onHit.value(target)
                            }
                        }
                    },
            ) { inner() }
        },
    )

    pendingLink?.let { link ->
        val openable = isOpenable(link.target)
        AppDialog(
            onDismissRequest = { pendingLink = null },
            dismissLabel = stringResource(R.string.feature_textdoc_action_edit),
            confirmLabel = stringResource(R.string.feature_textdoc_link_open),
            onConfirm = {
                pendingLink = null
                openLink(context, link.target)
            },
            title = stringResource(R.string.feature_textdoc_link_title),
            confirmEnabled = openable,
            onDismissClick = {
                // Edit: put the caret into the link, which shows its syntax.
                pendingLink = null
                field = field.copy(selection = TextRange(link.start))
                focusRequester.requestFocus()
            },
        ) {
            Text(
                link.target.ifEmpty { stringResource(R.string.feature_textdoc_link_empty) },
                color = if (openable) colors.textPrimary else colors.textSecondary,
            )
        }
    }
}

/** Mutable live-editor bookkeeping that must not trigger recomposition by itself (except the two counters). */
@Stable
private class LiveHolder {
    var doc: LiveMarkdownDocument? = null
    var version by mutableIntStateOf(0)
    var requests by mutableIntStateOf(0)
    var served = 0
    var lastWindow: IntRange? = null
    var windowMoved = false
    var keepSelection: TextRange? = null
    var keepSelectionUntil = 0L
}

/** Styles the field's text without changing it: offsets map one to one. */
private class StyledText(private val styled: AnnotatedString) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(if (text.text == styled.text) styled else text, OffsetMapping.Identity)
}

private sealed interface Hit {
    data class Task(val bracket: Int) : Hit
    data class Link(val link: LiveLink) : Hit
}

private fun hit(
    position: Offset,
    doc: LiveMarkdownDocument?,
    view: LiveMarkdownView?,
    layout: TextLayoutResult?,
    readOnly: Boolean,
    boxSize: Float,
    slack: Float,
): Hit? {
    if (doc == null || view == null || layout == null) return null
    if (layout.layoutInput.text.text != doc.text) return null
    if (position.y < 0f || position.y > layout.size.height) return null
    val offset = layout.getOffsetForPosition(position)
    val index = doc.lineAt(offset)
    if (index !in view.window) return null
    if (!readOnly) {
        val box = doc.taskBoxAt(index)
        val drawn = !(index in view.revealed && doc.touchesPrefix(index, view.selection))
        if (box >= 0 && drawn && box + 2 < doc.text.length) {
            val rect = taskBoxRect(layout, box, boxSize).inflate(slack)
            if (rect.contains(position)) return Hit.Task(box)
        }
    }
    if (index in view.revealed) return null
    for (candidate in intArrayOf(offset, offset - 1)) {
        if (candidate !in 0 until doc.text.length) continue
        if (!layout.getBoundingBox(candidate).inflate(slack / 3).contains(position)) continue
        val link = doc.linkAt(candidate) ?: continue
        return Hit.Link(link)
    }
    return null
}

private fun isOpenable(target: String): Boolean {
    val scheme = Uri.parse(target).scheme?.lowercase() ?: return false
    return scheme in OPENABLE_SCHEMES
}

private fun openLink(context: Context, target: String) {
    if (!isOpenable(target)) return
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.feature_textdoc_link_cant_open, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, R.string.feature_textdoc_link_cant_open, Toast.LENGTH_SHORT).show()
    }
}

private val OPENABLE_SCHEMES = setOf("http", "https", "mailto")
private val EditorPaddingHorizontal = 20.dp
private val EditorPaddingVertical = 10.dp
private val TaskBoxSize = 18.dp

/** Texts up to this size are analysed on the spot when live mode starts (no unstyled first frame). */
private const val SYNC_ANALYZE_CHARS = 64_000

/** The styled window moves when the viewport centre crosses a bucket of this many characters. */
private const val WINDOW_BUCKET_CHARS = 1024

/**
 * Android lays text out by scanning every span once per paragraph, so the styling cost grows with lines x spans:
 * the token budget is this over the line count, between [MIN_TOKENS] and [MAX_TOKENS].
 */
private const val TOKEN_WORK_BUDGET = 3_000_000
private const val MIN_TOKENS = 200
private const val MAX_TOKENS = 1500

/** How long after a handled tap a caret move from the same tap is ignored. */
private const val KEEP_SELECTION_MS = 400L
