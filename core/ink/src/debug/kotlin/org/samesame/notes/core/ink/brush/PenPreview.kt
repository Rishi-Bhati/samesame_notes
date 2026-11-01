package org.samesame.notes.core.ink.brush

import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.math.PI
import kotlin.math.sin

private val LEVELS = listOf(1, 30, 100)

/** Fixed input batch: a sine sweep with a pressure hump, 200 pu wide. */
internal fun previewInputs(): MutableStrokeInputBatch {
    val b = MutableStrokeInputBatch()
    for (i in 0..40) {
        val t = i / 40f
        b.add(
            InputToolType.STYLUS, x = 10f + 220f * t, y = 20f + 14f * sin(t * 2 * PI).toFloat(),
            elapsedTimeMillis = i * 10L, pressure = 0.2f + 0.8f * sin(t * PI).toFloat(),
        )
    }
    return b
}

/** Debug-only: every pen at levels 1/30/100 drawn from the same input batch. */
@Composable
fun PenCatalogPreview(modifier: Modifier = Modifier) {
    val renderer = CanvasStrokeRenderer.create(GrainTextureStore)
    Column(modifier.background(Color.White).padding(8.dp)) {
        for (spec in PenCatalog.pens + PenCatalog.highlighter) {
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val canvas = drawContext.canvas.nativeCanvas
                LEVELS.forEachIndexed { i, level ->
                    val stroke = Stroke(spec.brush(level, 0xFF1A3FD6), previewInputs())
                    val m = Matrix().apply { setTranslate(i * size.width / 3f, 0f) }
                    renderer.draw(canvas, stroke, m)
                }
            }
        }
    }
}

@Preview(widthDp = 720, heightDp = 400)
@Composable
private fun PenCatalogPreviewPreview() = PenCatalogPreview()
