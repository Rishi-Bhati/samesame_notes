package org.samesame.notes.feature.composer.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import org.samesame.notes.core.designsystem.theme.fixedSp
import org.samesame.notes.feature.composer.R

/**
 * The new page rising from the bottom (sliding in from the end edge in horizontal page view) while the user pulls past
 * the last page: the page colour with a "+" and "Drag for new page." / "Release to add page." (from the threshold, with a
 * haptic tick). Takes no touches; the pull distance is read in the layer / draw phase only, so a pull never recomposes.
 */
@Composable
internal fun PullToAddPageIndicator(
    state: PullToAddPageState,
    horizontal: Boolean,
    pageColor: Color,
    inkColor: Color,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val armed by remember(state) { derivedStateOf { state.armed } }
    LaunchedEffect(state) {
        state.maxPx = with(density) { PullToAddPageMath.MAX_DP.dp.toPx() }
        state.thresholdPx = with(density) { PullToAddPageMath.THRESHOLD_DP.dp.toPx() }
        snapshotFlow { state.armed }.drop(1).collect { if (it) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
    Box(
        modifier.fillMaxSize().onSizeChanged { state.fullPx = (if (horizontal) it.width else it.height).toFloat() },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val v = state.visible
                val sheet = state.alpha
                alpha = if (v <= 0.5f) 0f else sheet
                if (horizontal) translationX = size.width - v else translationY = size.height - v
                shadowElevation = if (v > 0.5f) 6.dp.toPx() * sheet else 0f
            },
        ) {
            Canvas(Modifier.fillMaxSize()) { drawRect(pageColor) }
            Column(
                Modifier.align(if (horizontal) Alignment.CenterStart else Alignment.TopCenter)
                    .padding(top = if (horizontal) 0.dp else 20.dp, start = if (horizontal) 12.dp else 20.dp, end = 20.dp)
                    .graphicsLayer { alpha = state.labelAlpha },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Canvas(Modifier.size(32.dp).graphicsLayer { rotationZ = PullToAddPageMath.progress(state.visible, state.thresholdPx) * 90f }) {
                    val w = 2.dp.toPx()
                    drawCircle(inkColor, size.minDimension / 2f - w, style = Stroke(w))
                    val c = size.width / 2f; val a = size.width * 0.22f
                    drawLine(inkColor, Offset(c - a, c), Offset(c + a, c), w)
                    drawLine(inkColor, Offset(c, c - a), Offset(c, c + a), w)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (armed) R.string.feature_composer_release_add_page else R.string.feature_composer_pull_add_page),
                    color = inkColor, fontSize = fixedSp(14f), textAlign = TextAlign.Center,
                    modifier = if (horizontal) Modifier.size(width = 100.dp, height = 60.dp) else Modifier,
                )
            }
        }
    }
}
