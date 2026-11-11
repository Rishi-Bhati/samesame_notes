package org.samesame.notes.feature.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.oneuiproject.oneui.R as Oui
import org.samesame.notes.core.designsystem.component.SelectionCircle
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.FolderColors
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.cappedSp
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.LibraryFile
import java.util.concurrent.ConcurrentHashMap

/** The coloured file-type glyph of a file (library `file_type_*` icons). */
internal fun typeIconRes(file: LibraryFile): Int = when (file.category) {
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

private val thumbCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
private val failedThumbs: MutableSet<String> = ConcurrentHashMap.newKeySet()

/** Decodes run two at a time: a fling past a hundred images must not start a hundred decodes at once. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val thumbDispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)

/** Decodes a small preview of a renderable image through its provider URI (bounded; null when it can't be read). */
@Composable
internal fun rememberThumbnail(file: LibraryFile, uriOf: () -> Uri, maxPx: Int): Bitmap? {
    if (!file.isRenderableImage || file.sizeBytes > MAX_THUMB_SOURCE_BYTES) return null
    val context = LocalContext.current
    val key = "${file.sha256}_$maxPx"
    var bitmap by remember(key) { mutableStateOf(thumbCache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap != null || key in failedThumbs) return@LaunchedEffect
        val decoded = kotlinx.coroutines.withContext(thumbDispatcher) {
            // A card scrolled away while it waited for its turn is skipped (its effect was cancelled).
            coroutineContext.ensureActive()
            thumbCache.get(key)?.let { return@withContext it }
            runCatching { decodeBounded(context, uriOf(), maxPx) }.getOrNull()
        }
        if (decoded != null) { thumbCache.put(key, decoded); bitmap = decoded } else failedThumbs += key
    }
    return bitmap
}

private const val MAX_THUMB_SOURCE_BYTES = 64L * 1024 * 1024

private fun decodeBounded(context: android.content.Context, uri: Uri, maxPx: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

/** The square type tile or thumbnail of a file. */
@Composable
internal fun FileThumb(file: LibraryFile, uriOf: () -> Uri, modifier: Modifier = Modifier, thumbPx: Int = 320) {
    val thumb = rememberThumbnail(file, uriOf, thumbPx)
    if (thumb != null) {
        Image(thumb.asImageBitmap(), null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        val colors = SameSameTheme.colors
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(typeIconRes(file)), null, modifier = Modifier.size(40.dp))
                val badge = FilesFormat.badge(file)
                if (badge.isNotEmpty()) {
                    Text(
                        badge, fontSize = 11.sp, color = colors.textSecondary,
                        modifier = Modifier.padding(top = 6.dp).background(colors.elevated, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileGridItem(
    file: LibraryFile,
    uriOf: () -> Uri,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    daysLeft: Int? = null,
) {
    val colors = SameSameTheme.colors
    // Formatted once per file, not on every recomposition of the card (date/number formatters are costly while flinging).
    val sizeAndDate = remember(file.sizeBytes, file.createdAt) { FilesFormat.sizeAndDate(file) }
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            FileThumb(
                file, uriOf,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(colors.card),
            )
            if (selected) Box(Modifier.matchParentSize().border(1.5.dp, colors.accent, RoundedCornerShape(12.dp)))
            if (selecting) SelectionCircle(selected, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (daysLeft != null) {
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(21.dp)
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)).background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) { Text(daysText(daysLeft), fontSize = 11.sp, color = colors.textPrimary) }
            }
        }
        Text(
            file.displayName, style = SameSameText.CardTitle, color = colors.textPrimary, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            sizeAndDate, style = SameSameText.CardDate, color = colors.textSecondary,
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun daysText(days: Int): String =
    if (days == 1) stringResource(R.string.feature_files_bin_day) else stringResource(R.string.feature_files_bin_days, days)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileListItem(
    file: LibraryFile,
    uriOf: () -> Uri,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondLine: String = remember(file.sizeBytes, file.createdAt) { FilesFormat.sizeAndDate(file) },
) {
    val colors = SameSameTheme.colors
    Row(
        modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(12.dp)).background(colors.card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) SelectionCircle(selected, Modifier.padding(end = 12.dp))
        FileThumb(file, uriOf, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), thumbPx = 96)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(file.displayName, style = SameSameText.ListTitle.copy(fontSize = 16.sp), color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(secondLine, style = SameSameText.ListBody, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (file.isFavorite && !selecting) Icon(AppIcons.StarFilled, null, tint = colors.accent, modifier = Modifier.size(14.dp))
    }
}

private const val FolderAspect = 85f / 66f

/** A folder tile (the notes folder card's look): tab, count and bold name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileFolderCard(
    folder: FileFolder,
    width: Dp,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SameSameTheme.colors
    val tab = folder.color?.let { Color(it.toInt()) } ?: FolderColors[0]
    Box(modifier.width(width).aspectRatio(FolderAspect).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.align(Alignment.TopEnd).size(width * (40f / 85f), width * (12f / 85f))
                .background(tab, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
        )
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.86f).background(tab.copy(alpha = 0.55f), RoundedCornerShape(10.dp))) {
            Column(Modifier.padding(start = 10.dp, top = 8.dp, end = 6.dp)) {
                Text(folder.fileCount.toString(), fontSize = cappedSp(13f), color = colors.textPrimary)
                Text(folder.name, fontSize = cappedSp(13f), color = colors.textPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selecting) SelectionCircle(selected, Modifier.align(Alignment.TopStart).padding(4.dp), size = 18.dp)
    }
}

/** "Sort glyph + field | direction arrow", right-aligned. */
@Composable
internal fun FilesSortRow(label: String, ascending: Boolean, onLabelClick: () -> Unit, onToggleDirection: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SameSameTheme.colors
    Row(
        modifier.fillMaxWidth().height(26.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.clickable(onClick = onLabelClick), verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Sort, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            Text(label, fontSize = cappedSp(13f), color = colors.textTertiary, modifier = Modifier.padding(start = 6.dp))
        }
        Box(Modifier.padding(start = 14.9.dp).width(1.dp).height(16.dp).background(colors.divider))
        Box(
            Modifier.width(38.dp).fillMaxHeight().clickable(onClick = onToggleDirection).padding(end = 4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Icon(if (ascending) AppIcons.ArrowUp else AppIcons.ArrowDown, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
}
