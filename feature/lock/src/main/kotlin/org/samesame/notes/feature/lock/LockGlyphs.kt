package org.samesame.notes.feature.lock

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.addPathNodes

/**
 * TODO(icons): temporary lock glyph. Replace with `AppIcons.Lock` (library `ic_oui_lock_outline`) once the
 * AppIcons library swap lands, then delete this file.
 */
object LockGlyphs {
    val Lock: ImageVector by lazy {
        ImageVector.Builder("Lock", 24.dp, 24.dp, 24f, 24f).apply {
            val stroke = SolidColor(Color.Black)
            addPath(
                addPathNodes("M7.75 10H16.75A2 2 0 0 1 18.75 12V18.7A2 2 0 0 1 16.75 20.7H7.75A2 2 0 0 1 5.75 18.7V12A2 2 0 0 1 7.75 10Z"),
                stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
            addPath(
                addPathNodes("M8.6 10V7.0C8.6 4.9 10.2 3.3 12.25 3.3C14.3 3.3 15.9 4.9 15.9 7.0V10"),
                stroke = stroke, strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
            addPath(addPathNodes("M10.95 14.2A1.3 1.3 0 1 0 13.55 14.2A1.3 1.3 0 1 0 10.95 14.2Z"), fill = stroke)
        }.build()
    }
}
