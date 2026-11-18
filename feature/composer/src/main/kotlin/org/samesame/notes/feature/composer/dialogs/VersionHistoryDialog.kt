package org.samesame.notes.feature.composer.dialogs

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.samesame.notes.core.data.repository.NoteVersionInfo
import org.samesame.notes.core.data.repository.NoteVersionText
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.feature.composer.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Automatic version history, newest first. Tapping a row shows its saved text read-only; Restore asks first and is
 * undoable because the current note is kept as a version. Locked notes show only time and size: a preview would reveal
 * their content, and restoring needs the note to be open in this session.
 */
@Composable
internal fun VersionHistoryDialog(
    locked: Boolean,
    loadVersions: suspend () -> List<NoteVersionInfo>,
    loadText: suspend (Long) -> NoteVersionText?,
    onRestore: (Long, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = SameSameTheme.colors
    var versions by remember { mutableStateOf<List<NoteVersionInfo>?>(null) }
    var selected by remember { mutableStateOf<NoteVersionInfo?>(null) }
    var preview by remember { mutableStateOf<NoteVersionText?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { versions = runCatching { loadVersions() }.getOrDefault(emptyList()) }
    LaunchedEffect(selected?.id) {
        val v = selected ?: return@LaunchedEffect
        previewLoading = true
        preview = runCatching { loadText(v.id) }.getOrNull()
        previewLoading = false
    }

    Dialog(
        onDismissRequest = { if (!restoring) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        androidx.compose.runtime.SideEffect { window?.setGravity(android.view.Gravity.BOTTOM) }
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).navigationBarsPadding()
                .heightIn(min = 220.dp, max = 600.dp),
            shape = RoundedCornerShape(28.dp),
            color = colors.dialog,
        ) {
            Column(Modifier.fillMaxWidth()) {
                val title = if (selected == null) {
                    stringResource(R.string.feature_composer_ov_version_history)
                } else {
                    stringResource(R.string.feature_composer_version_from, versionTime(selected!!.takenAtMs))
                }
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(start = 24.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected != null && !restoring) {
                        Box(
                            Modifier.size(40.dp).clickable(onClick = { selected = null; confirmRestore = false; failed = false }, role = Role.Button),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Back, stringResource(R.string.feature_composer_back), Modifier.size(24.dp), tint = colors.textPrimary)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        title,
                        color = colors.textPrimary,
                        fontSize = 19.8.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!restoring) {
                        Box(
                            Modifier.size(44.dp).clickable(onClick = onDismiss, role = Role.Button),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Close, stringResource(R.string.feature_composer_close), Modifier.size(24.dp), tint = colors.textPrimary)
                        }
                    }
                }

                val v = selected
                if (v == null) {
                    VersionList(
                        versions = versions,
                        locked = locked,
                        onPick = { selected = it; failed = false },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    VersionPreview(
                        version = v,
                        text = preview,
                        loading = previewLoading,
                        confirmRestore = confirmRestore,
                        restoring = restoring,
                        failed = failed,
                        onAskRestore = { confirmRestore = true },
                        onCancelRestore = { confirmRestore = false },
                        onRestore = {
                            restoring = true
                            failed = false
                            onRestore(v.id) { ok ->
                                restoring = false
                                confirmRestore = false
                                if (ok) onDismiss() else failed = true
                            }
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun VersionList(
    versions: List<NoteVersionInfo>?,
    locked: Boolean,
    onPick: (NoteVersionInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = SameSameTheme.colors
    if (versions == null) {
        Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (versions.isEmpty()) {
        Text(
            stringResource(R.string.feature_composer_version_empty),
            color = colors.textSecondary,
            fontSize = 16.sp,
            modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
        )
        return
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
        versions.forEach { v ->
            Row(
                Modifier.fillMaxWidth().clickable(onClick = { onPick(v) }, role = Role.Button)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(versionTime(v.takenAtMs), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (!locked && v.title.isNotBlank()) {
                        Text(
                            v.title,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (!locked && v.preview.isNotBlank()) {
                        Text(
                            v.preview.replace('\n', ' '),
                            color = colors.textSecondary,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    Formatter.formatShortFileSize(context, v.sizeBytes),
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun VersionPreview(
    version: NoteVersionInfo,
    text: NoteVersionText?,
    loading: Boolean,
    confirmRestore: Boolean,
    restoring: Boolean,
    failed: Boolean,
    onAskRestore: () -> Unit,
    onCancelRestore: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SameSameTheme.colors
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            if (version.pages > 0) {
                Text(stringResource(R.string.feature_composer_version_pages, version.pages), color = colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
            }
            when {
                loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                text == null -> Text(
                    stringResource(R.string.feature_composer_version_preview_failed),
                    color = colors.textSecondary,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                else -> {
                    if (text.title.isNotBlank()) {
                        Text(text.title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(text.body, color = colors.textPrimary, fontSize = 16.sp)
                }
            }
        }
        if (failed) {
            Text(
                stringResource(R.string.feature_composer_version_restore_failed),
                color = colors.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
        }
        if (restoring) {
            Text(
                stringResource(R.string.feature_composer_version_restoring),
                color = colors.textSecondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else if (confirmRestore) {
            Text(
                stringResource(R.string.feature_composer_version_restore_confirm),
                color = colors.textPrimary,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                DialogTextButton(stringResource(android.R.string.cancel), onCancelRestore, Modifier.weight(1f))
                DialogTextButton(stringResource(R.string.feature_composer_version_restore), onRestore, Modifier.weight(1f), bold = true)
            }
        } else {
            // The repository refuses a locked note that is not open; this screen is only reachable while it is.
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                DialogTextButton(stringResource(R.string.feature_composer_version_restore), onAskRestore, Modifier.weight(1f), bold = true)
            }
        }
    }
}

@Composable
private fun DialogTextButton(label: String, onClick: () -> Unit, modifier: Modifier, bold: Boolean = false) {
    Box(
        modifier.height(56.dp).clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = SameSameTheme.colors.textPrimary,
            fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** "Today 14:05" / "Yesterday 14:05" / "12 Nov 2026, 14:05" in the device's locale. */
@Composable
private fun versionTime(millis: Long): String {
    // Configuration-aware lookups: a locale or resource change must reformat without a restart.
    val resources = androidx.compose.ui.platform.LocalResources.current
    val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
    val time = remember(millis, locale) { SimpleDateFormat("HH:mm", locale).format(Date(millis)) }
    val dateTime = remember(millis, locale) { SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(millis)) }
    return when {
        DateUtils.isToday(millis) -> resources.getString(R.string.feature_composer_version_today, time)
        DateUtils.isToday(millis + TimeUnit.DAYS.toMillis(1)) ->
            resources.getString(R.string.feature_composer_version_yesterday, time)
        else -> dateTime
    }
}
