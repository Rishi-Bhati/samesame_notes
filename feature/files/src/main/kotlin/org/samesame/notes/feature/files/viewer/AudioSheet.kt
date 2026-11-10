package org.samesame.notes.feature.files.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameText
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.capped
import org.samesame.notes.core.model.VoiceSpeed
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.feature.files.FilesFormat
import org.samesame.notes.feature.files.R

/**
 * The audio player sheet: it sits at the bottom of the screen over whatever opened the file and starts playing at once.
 * Playback lasts while the sheet is open and the app is in front; it pauses when the app goes to the background.
 */
@Composable
internal fun AudioSheet(
    file: LibraryFile,
    uri: Uri,
    actions: @Composable RowScope.(ChromeColors) -> Unit,
    onDuration: (Long) -> Unit,
    onOpenWith: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val colors = SameSameTheme.colors
    val player = remember(file.sha256) { AudioPlayer(MediaPlayerAudioEngine(context, uri)) }
    val state by player.state.collectAsState()
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) { withContext(Dispatchers.IO) { player.load(autoPlay = true) } }
    LaunchedEffect(state.ready, state.durationMs) { if (state.ready && state.durationMs > 0) onDuration(state.durationMs.toLong()) }
    LaunchedEffect(state.playing) { while (state.playing) { player.refresh(); delay(200) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(colors.scrim).pointerInput(Unit) { detectTapGestures { onClose() } }) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(colors.dialog, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                // Taps on the sheet don't close it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .navigationBarsPadding().padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(file.displayName, style = SameSameText.ListTitle.capped(), color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(FilesFormat.size(file.sizeBytes), style = SameSameText.Caption, color = colors.textSecondary)
                }
                actions(themeChrome().copy(bar = colors.dialog))
            }
            if (state.failed) {
                ViewerMessage(stringResource(R.string.feature_files_viewer_audio_failed), onOpenWith, Modifier.fillMaxWidth().padding(end = 12.dp))
            } else {
                var dragging by remember { mutableStateOf<Float?>(null) }
                val duration = state.durationMs.coerceAtLeast(1)
                Slider(
                    value = dragging ?: state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { player.seekTo(it.toInt()) }; dragging = null },
                    valueRange = 0f..duration.toFloat(),
                    enabled = state.ready,
                    colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.pill),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, end = 12.dp),
                )
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(AudioPlayer.time((dragging ?: state.positionMs.toFloat()).toInt()), style = SameSameText.Caption, color = colors.textSecondary)
                    Text(AudioPlayer.time(state.durationMs), style = SameSameText.Caption, color = colors.textSecondary)
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.width(64.dp).clickable(enabled = state.ready, onClick = player::cycleSpeed).padding(vertical = 10.dp)) {
                        Text(VoiceSpeed.label(state.speed), style = SameSameText.Button, color = colors.textPrimary)
                    }
                    IconButton(onClick = player::rewind, enabled = state.ready, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Replay10, stringResource(R.string.feature_files_viewer_audio_rewind), tint = colors.textPrimary, modifier = Modifier.size(30.dp))
                    }
                    Box(
                        Modifier.size(60.dp).background(colors.accent, CircleShape).clickable(enabled = state.ready, onClick = player::toggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.playing) AppIcons.ControlPause else AppIcons.ControlPlay,
                            stringResource(if (state.playing) R.string.feature_files_viewer_audio_pause else R.string.feature_files_viewer_audio_play),
                            tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(30.dp),
                        )
                    }
                    IconButton(onClick = player::forward, enabled = state.ready, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Forward10, stringResource(R.string.feature_files_viewer_audio_forward), tint = colors.textPrimary, modifier = Modifier.size(30.dp))
                    }
                    Box(Modifier.width(64.dp))
                }
            }
        }
    }
}
