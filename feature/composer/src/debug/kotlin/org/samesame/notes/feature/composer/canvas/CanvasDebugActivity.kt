package org.samesame.notes.feature.composer.canvas

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PenType
import org.samesame.notes.core.model.document.NoteStyle
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.feature.composer.input.InputSettings

/** Debug-only host for [NoteCanvas] with an in-memory [PageSource]; see src/debug/AndroidManifest.xml for the intent extras. */
class CanvasDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val i = intent
        val mode = if (i.getStringExtra("mode") == "infinite") PageMode.Infinite else PageMode.Pages
        val ratio = if (i.getStringExtra("ratio") == "long") PageRatio.Long else PageRatio.A4Portrait
        val pageCount = i.getIntExtra("pages", if (mode == PageMode.Pages) 2 else 1)
        val source = InMemoryPageSource(
            PageLayout(mode, ratio),
            NoteStyle(adjustForDarkMode = i.getBooleanExtra("adjust", true)),
            List(pageCount) {
                org.samesame.notes.core.model.document.PageSpec(
                    "p$it",
                    org.samesame.notes.core.model.document.TemplateRef.Builtin(
                        org.samesame.notes.core.model.PageTemplate.valueOf(i.getStringExtra("template") ?: "Blank"),
                    ),
                )
            },
        )
        val dark = if (i.hasExtra("dark")) i.getBooleanExtra("dark", true) else null
        setContent {
            Host(
                source, dark ?: androidx.compose.foundation.isSystemInDarkTheme(), i.getBooleanExtra("finger", true),
                i.getBooleanExtra("lock", false), i.getBooleanExtra("ref", true),
                i.getFloatExtra("zoom", 1f), i.getFloatExtra("scrollx", 0f), i.getFloatExtra("scrolly", 0f),
            )
        }
    }
}

@Composable
private fun Host(source: InMemoryPageSource, dark: Boolean, finger0: Boolean, lock0: Boolean, ref: Boolean, zoom0: Float, scrollX0: Float, scrollY0: Float) {
    var finger by remember { mutableStateOf(finger0) }
    var lock by remember { mutableStateOf(lock0) }
    var adjust by remember { mutableStateOf(source.document.style.adjustForDarkMode) }
    var infinite by remember { mutableStateOf(source.document.layout.mode == PageMode.Infinite) }
    val viewport = remember { Viewport() }
    LaunchedEffect(viewport) {
        snapshotFlow { viewport.size }.collect { if (it.width > 0) { viewport.restore(androidx.compose.ui.geometry.Offset(scrollX0, scrollY0), zoom0); return@collect } }
    }
    val brush = remember { PenCatalog.specFor(PenType.Fountain).brush(30, 0xFF3355FF) }
    val density = LocalDensity.current
    // Reference phone geometry (#26): canvas spans y = 256..2167 of a 1080x2340 screen.
    val top = if (ref) with(density) { 256.toDp() } else 56.dp
    val bottom = if (ref) with(density) { 173.toDp() } else 56.dp

    LaunchedEffect(viewport) {
        snapshotFlow { Triple(viewport.size, viewport.scroll, viewport.zoom) }.collect { (size, scroll, zoom) ->
            val r0 = viewport.docToView(androidx.compose.ui.geometry.Offset.Zero)
            val s = viewport.scale
            Log.i("WPG", "size=$size scroll=$scroll zoom=$zoom page0 topLeftPx=$r0 widthPx=${1080f * s} heightPx=${source.document.let { 1080f * it.layout.ratio.heightOverWidth } * s}")
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().height(top).align(Alignment.TopStart).padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("finger " + if (finger) "on" else "off") { finger = !finger }
            Chip("lock " + if (lock) "on" else "off") { lock = !lock }
            Chip("adjust " + if (adjust) "on" else "off") { adjust = !adjust; source.update(style = NoteStyle(adjustForDarkMode = adjust)) }
            Chip(if (infinite) "infinite" else "pages") {
                infinite = !infinite
                source.update(layout = PageLayout(if (infinite) PageMode.Infinite else PageMode.Pages, source.document.layout.ratio))
            }
            Chip("clear") { source.clear() }
        }
        NoteCanvas(
            source = source,
            viewport = viewport,
            brush = brush,
            inputSettings = InputSettings(fingerDrawing = finger, zoomLock = lock),
            darkTheme = dark,
            modifier = Modifier.fillMaxSize().padding(top = top, bottom = bottom),
        )
        Text(
            "WPG ${if (dark) "dark" else "light"}", color = Color.Gray, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    TextButton(onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
        Text(label, fontSize = 11.sp, color = Color(0xFFBBBBBB))
    }
}
