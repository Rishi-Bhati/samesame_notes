package org.samesame.notes.feature.notes

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.designsystem.component.SelectionCircle
import org.samesame.notes.core.designsystem.motion.SameSameMotion
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.FolderColors
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.Folder
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.ViewMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cards' content aspect ratio (width / height), measured from the grid references. */
private fun ViewMode.cardRatio() = when (this) {
    ViewMode.GridSmall -> 1.0137f
    ViewMode.GridMedium -> 0.7167f
    else -> 0.7145f
}

@Composable
internal fun noteDisplayTitle(note: Note): String {
    if (note.isLocked) return stringResource(R.string.feature_notes_locked_note_title)
    if (note.title.isNotBlank()) return note.title
    // Remembered: a card entering the screen while scrolling must not build a SimpleDateFormat.
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val date = remember(note.createdAt, locale) { SimpleDateFormat("d/M", locale).format(Date(note.createdAt)) }
    return stringResource(R.string.feature_notes_default_title, date)
}

@Composable
internal fun noteTimeLabel(millis: Long): String {
    val context = LocalContext.current
    return remember(millis) {
        if (DateUtils.isToday(millis)) {
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(millis))
        } else {
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        }
    }
}

/** One note in a grid mode: a content-preview card with the title and date underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteGridItem(
    note: Note,
    mode: ViewMode,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    daysLeft: Int? = null,
    highlight: String? = null,
    preview: ImageBitmap? = null,
) {
    val colors = SameSameTheme.colors
    Column(
        modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The selection frame and marker sit outside the card's rounded clip, so neither is cut off at the corner.
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(mode.cardRatio())
                    .background(colors.card, RoundedCornerShape(CardRadius))
                    .clip(RoundedCornerShape(CardRadius)),
            ) {
                if (note.isLocked) {
                    // No preview for locked notes: the lock glyph on the empty card.
                    Icon(AppIcons.Lock, null, tint = colors.textSecondary, modifier = Modifier.align(Alignment.Center).size(24.dp))
                } else if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else Text(
                    text = highlighted(note.previewText, highlight),
                    style = SameSameText.CardBody,
                    color = colors.textPrimary,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxSize().padding(start = 16.4.dp, end = 16.dp, top = 11.9.dp, bottom = if (daysLeft != null) 28.dp else 5.dp),
                )
                if (daysLeft != null) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(21.dp).background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(daysLabel(daysLeft), fontSize = 11.sp, color = colors.textPrimary)
                    }
                }
            }
            if (selected) SelectionFrame(Modifier.matchParentSize())
            SelectionFade(selecting) { fade -> SelectionMark(selected, Modifier.align(Alignment.TopStart).then(fade)) }
        }
        Text(
            noteDisplayTitle(note), style = SameSameText.CardTitle, color = colors.textPrimary,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 11.7.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
            Text(
                noteTimeLabel(note.modifiedAt), style = SameSameText.CardDate, color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (note.isFavorite && !selecting) {
                Icon(AppIcons.StarFilled, null, tint = colors.accent, modifier = Modifier.padding(start = 3.4.dp).size(12.dp))
            }
        }
    }
}

@Composable
private fun daysLabel(days: Int) =
    stringResource(if (days == 1) R.string.feature_notes_trash_day else R.string.feature_notes_trash_days, days)

/** "List" view mode: a full-width card with the time on top and the content below. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteListItem(
    note: Note,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    daysLeft: Int? = null,
) {
    val colors = SameSameTheme.colors
    Box(
        modifier.fillMaxWidth()
            .background(colors.card, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        // Measured on ref #09: time 12.2dp from the card edge, body 16dp; 19dp top, 17dp bottom.
        Column(Modifier.padding(start = 12.2.dp, end = 16.dp, top = 19.dp, bottom = 17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (daysLeft != null) daysLabel(daysLeft) else noteTimeLabel(note.modifiedAt),
                    style = SameSameText.CardDate, color = colors.textSecondary,
                    modifier = Modifier.padding(start = animateDpAsState(if (selecting) 28.dp else 0.dp, tween(SameSameMotion.ListReflowMs, easing = SameSameMotion.SineInOut90), label = "datePad").value),
                )
                if (note.isFavorite && !selecting) {
                    Icon(AppIcons.StarFilled, null, tint = colors.accent, modifier = Modifier.padding(start = 4.dp).size(12.dp))
                }
            }
            Row(Modifier.padding(start = 3.8.dp, top = 12.1.dp), verticalAlignment = Alignment.CenterVertically) {
                if (note.isLocked) Icon(AppIcons.Lock, null, tint = colors.textSecondary, modifier = Modifier.padding(end = 6.dp).size(16.dp))
                Text(
                    (if (note.isLocked) "" else note.previewText).ifBlank { noteDisplayTitle(note) },
                    style = SameSameText.ListBody.capped(), color = colors.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) SelectionFrame(Modifier.matchParentSize())
        SelectionFade(selecting) { fade -> SelectionMark(selected, Modifier.align(Alignment.TopStart).padding(start = 12.2.dp, top = 12.dp).then(fade)) }
    }
}

/** "Simple list" view mode: a small thumbnail, the title and the time. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteSimpleItem(
    note: Note,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    daysLeft: Int? = null,
    preview: ImageBitmap? = null,
) {
    val colors = SameSameTheme.colors
    Row(
        // Ref #10: thumbnail 2.2dp further in than the other modes, 79.6dp row pitch (15.8dp above/below).
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 2.2.dp, top = 15.8.dp, bottom = 15.8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp)
                .background(colors.card, RoundedCornerShape(12.dp))
                .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
        ) {
            if (note.isLocked) {
                Icon(AppIcons.Lock, null, tint = colors.textSecondary, modifier = Modifier.align(Alignment.Center).size(20.dp))
            } else if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else Text(note.previewText, fontSize = 5.sp, lineHeight = 6.sp, color = colors.textPrimary, overflow = TextOverflow.Clip, modifier = Modifier.padding(6.dp))
            SelectionFade(selecting) { fade -> SelectionCircle(selected, Modifier.align(Alignment.Center).then(fade)) }
        }
        Column(Modifier.padding(start = 20.8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (note.isLocked) "" else note.title.ifBlank { note.previewText.lineSequence().firstOrNull().orEmpty() }).ifBlank { noteDisplayTitle(note) },
                    style = SameSameText.ListTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (note.isFavorite && !selecting) {
                    Icon(AppIcons.StarFilled, null, tint = colors.accent, modifier = Modifier.padding(start = 6.dp).size(12.dp))
                }
            }
            Text(
                if (daysLeft != null) daysLabel(daysLeft) else noteTimeLabel(note.modifiedAt),
                style = SameSameText.CardDate, color = colors.textSecondary, modifier = Modifier.padding(top = 3.8.dp),
            )
        }
    }
}

/** A folder tile: count top-left, bold name below, with a darker tab like a folder. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCard(
    folder: Folder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selecting: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val colors = SameSameTheme.colors
    val tab = folder.color?.let { Color(it.toInt()) } ?: FolderColors[0]
    Box(
        modifier.size(width = 85.dp, height = 66.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.align(Alignment.TopEnd).size(width = 40.dp, height = 12.dp).background(tab, RoundedCornerShape(topStart = 8.dp, topEnd = 6.dp)))
        Column(
            Modifier.fillMaxSize().padding(top = 5.dp).background(colors.card, RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Text(folder.noteCount.toString(), fontSize = 11.sp, color = colors.textSecondary)
            Text(folder.name, style = SameSameText.CardTitle, fontWeight = FontWeight.Bold, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SelectionFade(selecting) { fade ->
            SelectionCircle(selected, Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp).then(fade))
        }
    }
}

private val CardRadius = 12.dp
private val SelectionInset = 2.dp
private val SelectionStrokeWidth = 1.5.dp

/** A rounded outline framing a selected card, inset so it follows the card's own corners without being clipped. */
@Composable
private fun SelectionFrame(modifier: Modifier = Modifier) {
    Box(
        modifier.padding(SelectionInset).border(
            SelectionStrokeWidth, SameSameTheme.colors.accent,
            RoundedCornerShape(CardRadius - SelectionInset),
        ),
    )
}

/** The 32 dp corner cell that holds the 21 dp selection circle (reference: the checkbox sits in a 32 dp box). */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(32.dp), contentAlignment = Alignment.Center) {
        SelectionCircle(selected)
    }
}

/** Marks case-insensitive matches of [query] words in [text] with the highlight colour. */
@Composable
internal fun highlighted(text: String, query: String?): AnnotatedString {
    if (query.isNullOrBlank()) return AnnotatedString(text)
    val bg = SameSameTheme.colors.highlight
    return remember(text, query, bg) {
        buildAnnotatedString {
            val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val ranges = words.flatMap { w -> Regex(Regex.escape(w), RegexOption.IGNORE_CASE).findAll(text).map { it.range }.toList() }
            val mask = BooleanArray(text.length)
            ranges.forEach { r -> for (i in r) mask[i] = true }
            var i = 0
            while (i < text.length) {
                var j = i
                while (j < text.length && mask[j] == mask[i]) j++
                if (mask[i]) withStyle(SpanStyle(background = bg)) { append(text.substring(i, j)) } else append(text.substring(i, j))
                i = j
            }
        }
    }
}

/** Fades the selection checkbox in and out with selection mode (200 ms) and keeps it out of composition when idle. */
@Composable
private fun SelectionFade(selecting: Boolean, content: @Composable (Modifier) -> Unit) {
    val alpha = animateFloatAsState(
        if (selecting) 1f else 0f, tween(SameSameMotion.FabMs, easing = SameSameMotion.SineInOut80), label = "selectionFade",
    )
    if (selecting || alpha.value > 0f) content(Modifier.graphicsLayer { this.alpha = alpha.value })
}
