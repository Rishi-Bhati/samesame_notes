package org.samesame.notes.feature.composer.voice

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.core.model.VoiceSpeed
import org.samesame.notes.feature.composer.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Colours of the voice chrome (light and dark): panel surface, icon/text, secondary text, seek bar progress and
 * track, divider, recording red, the accent of the playing / checked row and the strip below the list.
 */
internal class VoiceColors(dark: Boolean) {
    val surface = if (dark) Color(0xFF171717) else Color(0xFFFCFCFC)
    val icon = if (dark) Color(0xE6FCFCFC) else Color(0xE6252525)
    val sub = if (dark) Color(0x80FCFCFC) else Color(0x80252525)
    val progress = if (dark) Color(0xFFFCFCFC) else Color(0xFF252525)
    val track = if (dark) Color(0xFF323232) else Color(0x26252525)
    val divider = if (dark) Color(0x33FCFCFC) else Color(0x26252525)
    val speed = if (dark) Color(0xFFFCFCFC) else Color(0xFF252525)
    val record = Color(0xFFF03E3E)
    /** Name of the row that is playing, and the check circles of the select mode. */
    val playingItem = Color(0xFFF46A4E)
    /** Background of a checked row in the list's select mode. */
    val checkedRow = if (dark) Color(0x26FCFCFC) else Color(0x14000000)
    /** The thin strip between the list and the page. */
    val gap = if (dark) Color(0xFF262626) else Color(0xFFE6E6E6)
}

/** A 32 dp round button with a One UI icon. */
@Composable
internal fun IconGlyphButton(icon: ImageVector, description: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
    }
}

/** The record button: a filled circle with the red record dot. */
@Composable
private fun RecordButton(c: VoiceColors, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            drawCircle(c.progress)
            drawCircle(c.record, radius = size.minDimension * 0.24f)
        }
    }
}

/** Skip button: a circular arrow with "10" inside; [forward] mirrors it. */
@Composable
private fun Skip10Button(forward: Boolean, description: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(Skip10Glyph.WIDTH.dp, Skip10Glyph.HEIGHT.dp)) {
            val d = 1.dp.toPx()
            val path = androidx.compose.ui.graphics.Path()
            // One open outline: from the gap end of the arrow-side edge, round the box, along the top and out to the
            // arrow tip; then the two arrowhead wings from the tip. Stroked once, so nothing is drawn twice.
            Skip10Glyph.outline(forward).forEach { seg ->
                path.moveTo(seg.first().x * d, seg.first().y * d)
                seg.drop(1).forEach { path.lineTo(it.x * d, it.y * d) }
            }
            drawPath(
                path, tint,
                style = Stroke(Skip10Glyph.STROKE * d, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
        Text(
            "10", color = tint, fontSize = fixedSp(8.5f), fontWeight = FontWeight.Bold,
            // Centred in the box part (which sits below the arrowhead), not in the whole glyph.
            modifier = Modifier.offset(y = (Skip10Glyph.textCenterY - Skip10Glyph.HEIGHT / 2f).dp),
        )
    }
}

/**
 * Geometry of the 10 s skip glyphs in dp (measured on the reference capture: 19 x 16 dp overall, 1.2 dp stroke):
 * a rounded box whose top edge runs straight on past the box's left (back) or right (forward) side into an arrowhead.
 * The box's side under the arrow starts lower down, leaving the corner open, so the arrow is part of the outline.
 * Polylines only (corners are flattened arcs) so tests can check the shape without a Canvas.
 */
internal object Skip10Glyph {
    const val WIDTH = 19f
    const val HEIGHT = 16f
    const val STROKE = 1.2f
    private const val LEFT = 0.6f
    private const val RIGHT = 18.4f
    private const val TOP = 3.1f
    private const val BOTTOM = 15.3f
    private const val RADIUS = 3f
    /** Where the open side of the box starts (its upper end), below the arrowhead's lower wing. */
    const val GAP_END_Y = 8.4f
    /** Arrowhead wing length along each axis (45 degree wings). */
    const val HEAD = 2.5f
    val textCenterY get() = (TOP + BOTTOM) / 2f

    data class P(val x: Float, val y: Float)

    /** The glyph as polylines: [0] the box + shaft ending at the tip, [1] the arrowhead wings through the tip. */
    fun outline(forward: Boolean): List<List<P>> {
        // Built for "back" (arrow points left), mirrored for forward.
        val box = buildList {
            add(P(LEFT, GAP_END_Y))
            addAll(arc(LEFT + RADIUS, BOTTOM - RADIUS, 180f, 90f)) // bottom-left corner
            addAll(arc(RIGHT - RADIUS, BOTTOM - RADIUS, 90f, 0f)) // bottom-right corner
            addAll(arc(RIGHT - RADIUS, TOP + RADIUS, 0f, -90f)) // top-right corner
            add(P(LEFT, TOP)) // the shaft: straight on to the tip, over the open top-left corner
        }
        val tip = P(LEFT, TOP)
        val head = listOf(P(LEFT + HEAD, TOP - HEAD), tip, P(LEFT + HEAD, TOP + HEAD))
        val mirror = { p: P -> if (forward) P(WIDTH - p.x, p.y) else p }
        return listOf(box.map(mirror), head.map(mirror))
    }

    /** Points of a circular arc from [fromDeg] to [toDeg] (screen angles: 0 = right, 90 = down). */
    private fun arc(cx: Float, cy: Float, fromDeg: Float, toDeg: Float, steps: Int = 6): List<P> =
        (0..steps).map { i ->
            val a = Math.toRadians((fromDeg + (toDeg - fromDeg) * i / steps).toDouble())
            P(cx + RADIUS * cos(a).toFloat(), cy + RADIUS * sin(a).toFloat())
        }
}

private val PillShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)

/**
 * The floating recorder pill: flush with the right edge at the top of the canvas,
 * rounded on the left, overlaying the canvas. Recording: stop, the red elapsed `mm:ss`, minimize. Otherwise
 * (the collapsed VOICEINIT state): a chevron that opens the docked menu, record (play in a read-only note), the total
 * `mm:ss` and minimize. Minimized it shrinks to an 18 dp sliver; a tap brings it back. Our extra Pause button is gone:
 * the captured pill shows none (pause stays on the notification).
 */
@Composable
internal fun VoiceRecordPill(
    recording: RecorderState.Active?,
    totalMs: Long,
    editable: Boolean,
    minimized: Boolean,
    onMinimizeChange: (Boolean) -> Unit,
    onStop: () -> Unit,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VoiceColors(SameSameTheme.colors.isDark)
    if (minimized) {
        val expand = stringResource(R.string.feature_composer_voice_expand)
        Box(
            modifier.width(18.dp).height(44.dp).shadow(2.dp, PillShape).background(c.surface, PillShape)
                .clickable(role = Role.Button) { onMinimizeChange(false) }.semantics { contentDescription = expand },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(8.dp)) { drawCircle(if (recording != null && !recording.paused) c.record else c.sub) }
        }
        return
    }
    val minimize = stringResource(R.string.feature_composer_voice_minimize)
    if (recording != null) {
        // The timer ticks here, not in the screen, so a tick recomposes only the pill.
        var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(recording) {
            while (!recording.paused) {
                now = android.os.SystemClock.elapsedRealtime()
                kotlinx.coroutines.delay(200)
            }
        }
        Row(
            modifier.width(168.dp).height(44.dp).shadow(2.dp, PillShape).background(c.surface, PillShape),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconGlyphButton(AppIcons.ControlStop, stringResource(R.string.feature_composer_voice_stop), c.progress, onClick = onStop)
            Text(
                VoiceFormat.clock(recording.elapsedMs(if (recording.paused) 0L else now)),
                color = c.record, fontSize = fixedSp(18f),
            )
            IconGlyphButton(AppIcons.Minimize, minimize, c.icon) { onMinimizeChange(true) }
        }
        return
    }
    Row(
        modifier.width(204.dp).height(44.dp).shadow(2.dp, PillShape).background(c.surface, PillShape),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconGlyphButton(AppIcons.ChevronLeft, stringResource(R.string.feature_composer_voice_expand), c.icon, onClick = onExpand)
        // Idle pill always offers play (the record button only exists while recording is under way).
        IconGlyphButton(AppIcons.ControlPlay, stringResource(R.string.feature_composer_voice_play), c.icon, onClick = onPlay)
        Text(VoiceFormat.clock(totalMs), color = c.progress, fontSize = fixedSp(18f))
        IconGlyphButton(AppIcons.Minimize, minimize, c.icon) { onMinimizeChange(true) }
    }
}

/**
 * The docked menu (the reference's expanded menu): one full-width row under the title bar that pushes the canvas down.
 * Row 1 is the collapse chevron, the seek bar, play/pause, record, the current time (only while the list is closed) and
 * the list button. With the list open a controls row follows (speed pill, "position | total", skip back/forward 10 s),
 * then [list] (header and rows) and the gap strip before the page. The playback state is collected here so a tick
 * recomposes only this menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceDockedMenu(
    player: PlaybackController,
    idleTotalMs: Long,
    listOpen: Boolean,
    canRecord: Boolean,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onRecord: () -> Unit,
    onToggleList: () -> Unit,
    modifier: Modifier = Modifier,
    list: @Composable () -> Unit,
) {
    val c = VoiceColors(SameSameTheme.colors.isDark)
    val state by player.state.collectAsStateWithLifecycle()
    // The playlist state is cumulative over all recordings; before the tracks are registered fall back to the stored total.
    val duration = if (state.durationMs > 0) state.durationMs else idleTotalMs.toInt()
    val position = state.positionMs
    Column(modifier.fillMaxWidth().background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            IconGlyphButton(AppIcons.ChevronRight, stringResource(R.string.feature_composer_voice_collapse), c.icon, Modifier.padding(start = 4.dp), onClick = onCollapse)
            Slider(
                value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { player.seekTo(it.toInt()) },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(thumbColor = c.progress, activeTrackColor = c.progress, inactiveTrackColor = c.track),
                thumb = { Box(Modifier.size(20.dp).background(c.progress, CircleShape)) },
                track = { st ->
                    val frac = ((st.value - st.valueRange.start) / (st.valueRange.endInclusive - st.valueRange.start)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.track)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c.progress))
                    }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconGlyphButton(
                if (state.playing) AppIcons.ControlPause else AppIcons.ControlPlay,
                stringResource(if (state.playing) R.string.feature_composer_voice_pause else R.string.feature_composer_voice_play),
                c.icon, onClick = onTogglePlay,
            )
            if (canRecord) RecordButton(c, stringResource(R.string.feature_composer_voice_record), Modifier.padding(horizontal = 4.dp), onClick = onRecord)
            if (!listOpen) {
                Text(VoiceFormat.clock(position.toLong()), color = c.icon, fontSize = fixedSp(14f), modifier = Modifier.padding(horizontal = 6.dp))
            }
            IconGlyphButton(AppIcons.List, stringResource(R.string.feature_composer_voice_list), c.icon, Modifier.padding(end = 6.dp), onClick = onToggleList)
        }
        if (listOpen) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                val speedDescription = stringResource(R.string.feature_composer_voice_speed)
                Box(
                    Modifier.padding(start = 10.dp).width(50.7.dp).height(19.8.dp)
                        .border(BorderStroke(0.5.dp, c.speed), RoundedCornerShape(11.dp))
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(role = Role.Button, onClick = player::cycleSpeed)
                        .semantics { contentDescription = speedDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(VoiceSpeed.label(state.speed), color = c.speed, fontSize = fixedSp(12f), textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(lineHeight = 12.sp, platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false), lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(VoiceFormat.clock(position.toLong()), color = c.progress, fontSize = fixedSp(18f), fontWeight = FontWeight.Bold)
                    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(10.dp).background(Color(0xFF8F8F8F)))
                    Text(VoiceFormat.clock(duration.toLong()), color = c.sub, fontSize = fixedSp(18f))
                }
                Skip10Button(false, stringResource(R.string.feature_composer_voice_rewind), c.icon, Modifier.padding(end = 6.dp), onClick = player::rewind)
                Skip10Button(true, stringResource(R.string.feature_composer_voice_forward), c.icon, Modifier.padding(end = 8.dp), onClick = player::forward)
            }
            list()
            Box(Modifier.fillMaxWidth().height(13.dp).background(c.gap))
        } else {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.divider))
        }
    }
}
