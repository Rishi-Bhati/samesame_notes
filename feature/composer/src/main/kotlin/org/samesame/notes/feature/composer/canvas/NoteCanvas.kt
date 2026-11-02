package org.samesame.notes.feature.composer.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.richtext.PageTextPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import kotlinx.coroutines.flow.distinctUntilChanged
import org.samesame.notes.core.ink.DarkModeInk
import org.samesame.notes.core.ink.brush.GrainTextureStore
import org.samesame.notes.core.ink.tools.StraightLine
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.PaintingObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.document.bitmapMedia
import org.samesame.notes.core.model.document.Affine2
import org.samesame.notes.core.model.document.frame
import org.samesame.notes.core.model.document.mapped
import org.samesame.notes.core.model.document.transformedBy
import org.samesame.notes.core.ink.tools.StrokeTransforms
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.feature.composer.input.InputRouter
import org.samesame.notes.feature.composer.input.InputSettings
import org.samesame.notes.feature.composer.input.ToolHandler
import org.samesame.notes.feature.composer.input.InputTool
import org.samesame.notes.feature.composer.input.editorInput

/** The live selection transform of one page (docs 2.5): selected elements are drawn transformed while a drag is in flight. */
class LiveSelection(val pageId: String, val ids: Set<ElementId>, val matrix: AffineTransform)

/** Colours of the page chrome. Dark values are measured from #26 (page 3,3,3, surround 37,37,37). */
object CanvasColors {
    val DarkPage = Color(0xFF030303)
    val DarkSurround = Color(0xFF252525)
    val LightPage = Color.White
    val LightSurround = Color(0xFFF2F2F2)
}

/**
 * The editor surface (docs 2.3): surround, page backgrounds and templates, page ink in z-order, then live
 * ink on top; scroll/zoom/fling through [viewport]; input routed by [InputRouter] (docs section 3).
 *
 * @param brush stored (non-inverted) brush for live ink; the canvas inverts it for display on adjusted dark pages.
 * @param startsTextInteraction text mode: true when a touch at this view position begins a selection-handle drag, so it
 *   wins over scrolling.
 * @param drawPageText WP-F hook, called per visible page in page space (pu, origin = page top-left, clipped to the
 *   page) between the template and the ink, drawn live every frame. Prefer [pageText].
 * @param pageText body text split for caching: its static pass is recorded once per page into a layer (re-recorded when
 *   its version changes) and replayed while scrolling; selection, search hits and caret are drawn live. Wins over
 *   [drawPageText].
 * @param overlay Compose overlays (selection box, lasso, eraser circle) in view space.
 */
@Composable
fun NoteCanvas(
    source: PageSource,
    viewport: Viewport,
    brush: Brush,
    inputSettings: InputSettings,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
    toolHandler: (InputTool) -> ToolHandler? = { null },
    onStylusSeen: () -> Unit = {},
    startsTextInteraction: (Offset) -> Boolean = { false },
    /** Text mode: true when a touch at this view position grabs the selected object (it goes to the selection tool). */
    startsObjectGesture: (Offset) -> Boolean = { false },
    /** Pull past the end to add a page (see `editorInput`); null when not allowed now. */
    pullToAddPage: () -> Pair<PullToAddPageState, Boolean>? = { null },
    /** `ImageObject` bitmaps by `media/<name>` path (null while loading); see [ObjectImageCache]. */
    objectImage: (String) -> ImageBitmap? = { null },
    /** Page background images (imported PDF pages) by `media/<name>` path; defaults to [objectImage]. */
    backgroundImage: ((String) -> ImageBitmap?)? = null,
    /** Read into each page layer's cache key; return a value that changes when an object bitmap loads. */
    objectKey: () -> Any = { 0 },
    /** While a selection transform drag is in flight, the selected elements of that page are drawn transformed. */
    liveSelection: () -> LiveSelection? = { null },
    /** A text box being edited in an overlay: its static drawing is skipped (include it in [objectKey] too). */
    hiddenElement: () -> ElementId? = { null },
    drawPageText: (DrawScope.(pageIndex: Int) -> Unit)? = null,
    pageText: PageTextPainter? = null,
    /** Straight-line highlighter / marker: finished strokes snap to the line between their first and last point. */
    straightLine: Boolean = false,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val layout by remember(source) {
        derivedStateOf {
            val d = source.document
            DocumentLayout(d.pages, d.layout)
        }
    }
    // Derived (not read straight into composition): a page edit bumps the source tick, and only a style
    // change should recompose the canvas; new strokes are picked up by the draw phase.
    val darkAdjust by remember(source, darkTheme) {
        derivedStateOf { darkTheme && source.document.style.adjustForDarkMode }
    }
    val painters = remember { ObjectPainters() }
    val latestSettings by rememberUpdatedState(inputSettings)
    val latestTextHook by rememberUpdatedState(startsTextInteraction)
    val latestObjectHook by rememberUpdatedState(startsObjectGesture)
    val latestPull by rememberUpdatedState(pullToAddPage)
    val router = remember(viewport) {
        InputRouter(
            settings = { latestSettings },
            pageHit = { px -> layout.pageAt(viewport.viewToDoc(px)) != null },
            startsTextInteraction = { px -> latestTextHook(px) },
            startsObjectGesture = { px -> latestObjectHook(px) },
        )
    }

    SideEffect { viewport.updateContent(layout.contentSize) }

    // Prefetch visible pages +-1 screen; infinite mode grows when the end is within one viewport.
    LaunchedEffect(source, viewport) {
        snapshotFlow {
            val v = viewport.visibleDoc
            val margin = if (layout.horizontal) v.width else v.height
            val expanded = if (layout.horizontal) Rect(v.left - margin, v.top, v.right + margin, v.bottom)
            else Rect(v.left, v.top - margin, v.right, v.bottom + margin)
            layout.visiblePages(expanded)
        }.distinctUntilChanged().collect { source.onPagesWanted(it) }
    }
    LaunchedEffect(source, viewport) {
        snapshotFlow {
            if (!layout.isInfinite || viewport.size.height == 0) false
            else viewport.visibleDoc.bottom + viewport.visibleDoc.height > layout.contentSize.height
        }.collect { if (it) source.appendPage() }
    }

    val graphics = LocalGraphicsContext.current
    val cache = remember(graphics) { PageLayerCache(graphics) }
    DisposableEffect(cache) { onDispose { cache.release() } }
    val renderer = remember { CanvasStrokeRenderer.create(GrainTextureStore) }
    val drawer = remember(cache, renderer) { PageDrawer(cache, renderer, painters) }
    val liveTransform = remember { LiveInkTransform() }
    // The live-ink transform and mask follow the viewport without recomposing: synced when a pointer goes down (before
    // ink sees the event, so a stroke always starts with the current transform) and after composition.
    SideEffect { liveTransform.sync(viewport, layout) }

    val surround = if (darkTheme) CanvasColors.DarkSurround else CanvasColors.LightSurround

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { viewport.updateSize(it) }
            .pointerInput(viewport, liveTransform, source) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val down = ev.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (down != null) {
                            liveTransform.sync(viewport, layout)
                            // The stroke about to start shows with the page it lands on: a PDF page keeps the stored colours.
                            liveTransform.downOnLightPage = darkAdjust &&
                                StrokePlacement.pageFor(viewport.viewToDoc(down.position), layout)
                                    ?.let { i -> source.document.pages.getOrNull(i)?.let { !adjustsForDark(it, true) } } == true
                        }
                    }
                }
            }
            .editorInput(router, viewport, toolHandler, onStylusSeen, pullToAddPage = { latestPull() }),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(surround)
            // Every state read below happens in this draw phase: scrolling re-runs this lambda (layer replays), never composition.
            drawer.drawFrame(
                scope = this,
                d = source.document,
                lay = layout,
                viewport = viewport,
                source = source,
                darkTheme = darkTheme,
                darkAdjust = darkAdjust,
                objectImage = objectImage,
                backgroundImage = backgroundImage ?: objectImage,
                objectKey = objectKey(),
                live = liveSelection(),
                hidden = hiddenElement(),
                pageText = pageText,
                drawPageText = drawPageText,
            )
        }

        LiveInk(brush, liveTransform, source, layout, darkAdjust, straightLine)

        if (layout.pageCount > 0 && !layout.isInfinite) {
            // Every visible page carries its own "n/m" at its bottom right (#107: "1/2" under page 1, "2/2" under page 2).
            // An IntRange (value equality): scrolling within the same pages neither allocates a list nor recomposes.
            val visible by remember { derivedStateOf { layout.visiblePages(viewport.visibleDoc) } }
            for (i in visible) {
                key(i) {
                    PageIndicator(
                        i + 1, layout.pageCount, darkAdjust || darkTheme,
                        Modifier.align(Alignment.BottomEnd).offset {
                            // Text centre about 17 px above the bottom of the page (#26, #107; or of the viewport while the page runs past it), right edge 42 px from the right.
                            val pageBottom = viewport.docToView(Offset(0f, layout.pageRects[i].bottom)).y
                            val bottom = minOf(viewport.size.height.toFloat(), pageBottom)
                            IntOffset(-INDICATOR_RIGHT_PX, -(viewport.size.height - bottom + INDICATOR_BOTTOM_PX).toInt())
                        },
                    )
                }
            }
        }
        overlay()
    }
}

private const val INDICATOR_RIGHT_PX = 42
private const val INDICATOR_BOTTOM_PX = 0

private fun Color.luminanceIsDark(): Boolean = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.5f

/**
 * Whether a page takes the dark-mode colour adjustment. A page with a picture as its background (an imported PDF page, an
 * image template) keeps its own light surface in a dark note, so everything written on it (ink, text, text boxes) keeps its
 * stored colours: inverting them put black pen strokes and light text on white paper, where they could not be seen.
 */
internal fun adjustsForDark(spec: PageSpec, darkAdjust: Boolean): Boolean = darkAdjust && spec.template !is TemplateRef.Image

/**
 * Page background. Default: near-black on a dark adjusted page like #26, white otherwise. A stored colour is
 * lightness-inverted when the page is adjusted (docs 2.6); with adjust off a dark app theme keeps light pages (#56).
 */
internal fun pageColor(spec: PageSpec, noteColor: Long?, darkTheme: Boolean, darkAdjust: Boolean): Color {
    val raw = spec.backgroundColor ?: noteColor
    val isDefault = raw == null ||
        raw == 0L ||
        raw == -1L ||
        (raw and 0xFFFFFFL) == 0xFCFCFCL ||
        (raw and 0xFFFFFFL) == 0xFFFFFFL
    val stored = raw.takeUnless { isDefault }
    return when {
        stored == null -> if (darkAdjust) CanvasColors.DarkPage else CanvasColors.LightPage
        darkAdjust -> Color(DarkModeInk.invert(stored.toInt()))
        else -> Color(stored.toInt())
    }
}

private fun DrawScope.drawInk(
    renderer: CanvasStrokeRenderer, cache: PageLayerCache, spec: PageSpec,
    elements: List<org.samesame.notes.core.ink.page.PageElement>, inverted: Boolean,
    objectImage: (String) -> ImageBitmap?, live: LiveSelection?,
    painters: ObjectPainters, hidden: ElementId?, pass: ObjectPass,
) {
    for (e in elements) {
        val m = live?.takeIf { e.id in it.ids }?.matrix
        // Decoded once per element instance (memoised in the cache), not once per check.
        val floating = if (e is ObjectElement) cache.floating(spec.id, e) else null
        // Behind pass: only "wrap behind" objects that are not being dragged; front pass: everything else.
        val behind = floating != null && floating.wrap == org.samesame.notes.core.model.document.TextWrap.Behind && m == null
        if ((pass == ObjectPass.Behind) != behind) continue
        when (e) {
            is InkElement -> {
                val stroke = if (m != null) StrokeTransforms.apply(e.stroke, m) else e.stroke
                val display = if (m != null) { if (inverted) DarkModeInk.invert(stroke) else stroke }
                else cache.displayStroke(spec.id, e.id, stroke, inverted)
                drawIntoCanvas { c -> renderer.draw(c.nativeCanvas, display, AffineTransform.IDENTITY) }
            }
            is ObjectElement -> if (e.id != hidden && floating != null) drawObjectElement(floating, objectImage, m, painters, inverted)
        }
    }
}

internal enum class ObjectPass { Behind, Front }

/** Draws a floating object into its (optionally transformed) rect: images fitted and centred keeping aspect, text boxes and voice pills filled. */
private fun DrawScope.drawObjectElement(
    floating: FloatingObject, objectImage: (String) -> ImageBitmap?, m: AffineTransform?, painters: ObjectPainters, dark: Boolean,
) {
    // The frame (rect + rotation) carried by the live transform; bounds are only the axis-aligned box of it.
    val fr = if (m != null) floating.frame.transformedBy(Affine2(m.m00, m.m10, m.m20, m.m01, m.m11, m.m21)) else floating.frame
    val r = fr.toRect()
    val w = r.width; val h = r.height
    if (w <= 0f || h <= 0f) return
    withTransform({ if (fr.rotationDeg != 0f) rotate(fr.rotationDeg, Offset(fr.cx, fr.cy)) }) {
        when (val o = floating.obj) {
            is ImageObject, is PaintingObject -> {
                // A drawing is drawn from its thumbnail, like an image.
                val bitmap = objectImage(o.bitmapMedia ?: return@withTransform) ?: return@withTransform
                if (bitmap.width <= 0 || bitmap.height <= 0) return@withTransform
                val scale = minOf(w / bitmap.width, h / bitmap.height)
                val dw = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val dh = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val left = (r.left + (w - dw) / 2f).toInt()
                val top = (r.top + (h - dh) / 2f).toInt()
                drawImage(bitmap, dstOffset = IntOffset(left, top), dstSize = IntSize(dw, dh))
            }
            is TextBoxObject -> drawIntoCanvas { c -> painters.drawTextBox(c.nativeCanvas, floating, o, dark, RectPu2(r.left, r.top, w, h)) }
            is VoiceObject -> drawIntoCanvas { c -> painters.drawVoice(c.nativeCanvas, RectPu2(r.left, r.top, w, h), o, dark) }
        }
    }
}

/** Places page-space drawing on the view: translate to the page's view position, then the uniform zoom (pixel-exact edges). */
private inline fun DrawScope.inPage(r: Rect, org: Offset, sc: Float, block: DrawScope.() -> Unit) =
    withTransform({
        translate(org.x + r.left * sc, org.y + r.top * sc)
        scale(sc, sc, Offset.Zero)
    }, block)

/**
 * Draws the pages of one canvas frame from cached layers (docs 2.3), in passes: backgrounds/templates, "wrap behind"
 * objects, body text, ink and floating objects. Per frame it only checks plain-field keys and replays display lists:
 * no key lists, no slot strings, no closures, no JSON decoding. Layers of pages outside the visible +- 1 window are
 * released, and each frame records at most one stale layer of the next page ahead so it is ready before it scrolls in.
 */
private class PageDrawer(
    private val cache: PageLayerCache,
    private val renderer: CanvasStrokeRenderer,
    private val painters: ObjectPainters,
) {
    private var keptFirst = 0
    private var keptLast = -1
    private var keptPages: List<PageSpec>? = null
    private var lastAxis = Float.NaN
    private var forward = true

    // Per-frame inputs (set at the top of drawFrame; read by the helpers below).
    private lateinit var d: CanvasDocument
    private lateinit var lay: DocumentLayout
    private lateinit var source: PageSource
    private var darkTheme = false
    private var darkAdjust = false
    private var objectImage: (String) -> ImageBitmap? = { null }
    private var backgroundImage: (String) -> ImageBitmap? = { null }
    private var objectKey: Any? = null
    private var hidden: ElementId? = null
    private var hiddenKey = NO_ELEMENT
    private var textVersion = 0L

    fun drawFrame(
        scope: DrawScope, d: CanvasDocument, lay: DocumentLayout, viewport: Viewport, source: PageSource,
        darkTheme: Boolean, darkAdjust: Boolean, objectImage: (String) -> ImageBitmap?, backgroundImage: (String) -> ImageBitmap?,
        objectKey: Any?, live: LiveSelection?, hidden: ElementId?, pageText: PageTextPainter?,
        drawPageText: (DrawScope.(pageIndex: Int) -> Unit)?,
    ) = with(scope) {
        this@PageDrawer.d = d
        this@PageDrawer.lay = lay
        this@PageDrawer.source = source
        this@PageDrawer.darkTheme = darkTheme
        this@PageDrawer.darkAdjust = darkAdjust
        this@PageDrawer.objectImage = objectImage
        this@PageDrawer.backgroundImage = backgroundImage
        this@PageDrawer.objectKey = objectKey
        this@PageDrawer.hidden = hidden
        hiddenKey = hidden?.value ?: NO_ELEMENT
        val sc = viewport.scale
        val org = viewport.origin
        val visible = lay.visiblePages(viewport.visibleDoc)
        val isPages = d.layout.mode == PageMode.Pages
        val axis = if (lay.horizontal) viewport.scroll.x else viewport.scroll.y
        if (!lastAxis.isNaN() && axis != lastAxis) forward = axis > lastAxis
        lastAxis = axis
        retain(visible)
        val recordsBefore = cache.recordCount

        // 1. backgrounds + templates (cached layers)
        for (i in visible) {
            val r = lay.pageRects[i]
            val layer = backgroundLayer(this, i)
            inPage(r, org, sc) { replay(layer) }
            if (isPages) {
                val adjusted = adjustsForDark(d.pages[i], darkAdjust)
                val pageWidth = r.width
                val bottom = org.y + r.bottom * sc
                // Page bottom edge (#26: row 1974 is #171717 and row 1975 #363636, the page ending at 1974).
                val edgeX = org.x + r.left * sc
                if (adjusted) {
                    drawRect(EDGE_DARK_INNER, Offset(edgeX, bottom - 1f), Size(pageWidth * sc, 1f))
                    drawRect(EDGE_DARK_OUTER, Offset(edgeX, bottom), Size(pageWidth * sc, 1f))
                } else {
                    drawRect(EDGE_LIGHT, Offset(edgeX, bottom), Size(pageWidth * sc, 1f))
                }
            }
        }
        // 1b. objects set to "wrap text behind" sit under the body text
        for (i in visible) {
            val spec = d.pages[i]
            val content = source.content(spec.id) ?: continue
            if (!cache.hasBehindObjects(content)) continue
            val liveHere = live?.takeIf { it.pageId == spec.id }
            val layer = behindLayer(this, i, content, liveHere)
            inPage(lay.pageRects[i], org, sc) { replay(layer) }
        }
        // 2. body text: static glyphs from a cached layer, selection / search / caret live
        if (pageText != null) {
            textVersion = pageText.beginFrame()
            for (i in visible) {
                val ps = lay.pageRects[i]
                inPage(ps, org, sc) {
                    clipRect(0f, 0f, ps.width, ps.height) {
                        drawIntoCanvas { pageText.drawUnder(it.nativeCanvas, i) }
                        replay(textLayer(this, i, pageText))
                        drawIntoCanvas { pageText.drawOver(it.nativeCanvas, i) }
                    }
                }
            }
        } else if (drawPageText != null) {
            for (i in visible) {
                val ps = lay.pageRects[i]
                inPage(ps, org, sc) { clipRect(0f, 0f, ps.width, ps.height) { drawPageText.invoke(this, i) } }
            }
        }
        // 3. ink and floating objects (cached layers; the live selection layer is drawn straight)
        for (i in visible) {
            val spec = d.pages[i]
            val content = source.content(spec.id) ?: continue
            if (content.elements.isEmpty()) continue
            val r = lay.pageRects[i]
            if (live != null && live.pageId == spec.id) {
                val adjusted = adjustsForDark(spec, darkAdjust)
                inPage(r, org, sc) {
                    if (isPages) clipRect(0f, 0f, r.width, r.height) {
                        drawInk(renderer, cache, spec, content.elements, adjusted, objectImage, live, painters, hidden, ObjectPass.Front)
                    } else drawInk(renderer, cache, spec, content.elements, adjusted, objectImage, live, painters, hidden, ObjectPass.Front)
                }
                continue
            }
            val layer = inkLayer(this, i, content)
            inPage(r, org, sc) { replay(layer) }
        }
        // 4. Ahead of the viewport: record at most one stale layer of a neighbour page per frame, and only on frames that
        // recorded nothing visible (a keystroke frame re-records the visible text; it must not pay for a neighbour too).
        if (cache.recordCount == recordsBefore &&
            !prefetch(this, LayerRetention.neighbour(visible, lay.pageCount, forward, 0), pageText)
        ) {
            prefetch(this, LayerRetention.neighbour(visible, lay.pageCount, forward, 1), pageText)
        }
    }

    /** Releases layers outside visible +- 1 page; only does work when that window (or the page list) changes. */
    private fun retain(visible: IntRange) {
        val win = LayerRetention.window(visible, lay.pageCount)
        if (win.isEmpty()) return
        if (win.first == keptFirst && win.last == keptLast && d.pages === keptPages) return
        keptFirst = win.first
        keptLast = win.last
        keptPages = d.pages
        val keep = HashSet<String>(win.last - win.first + 1)
        for (i in win) keep += d.pages[i].id
        cache.retainOnly { it in keep }
    }

    private fun backgroundKey(i: Int): LayerKey {
        val spec = d.pages[i]
        val pageSize = lay.pageSize(i)
        val bg = pageColor(spec, d.style.backgroundColor, darkTheme, adjustsForDark(spec, darkAdjust))
        val tpl = (spec.template as? TemplateRef.Builtin)?.template ?: PageTemplate.Blank
        val lineColor = if (bg.luminanceIsDark()) LINE_ON_DARK else LINE_ON_LIGHT
        val phase = if (lay.isInfinite) lay.pageRects[i].top else 0f
        val extra = if (lay.isInfinite) 1f else 0f // overlap hides a seam between segments
        // Image backgrounds (imported PDF pages): the key follows the bitmap load.
        val imagePath = (spec.template as? TemplateRef.Image)?.media
        val image = imagePath?.let(backgroundImage)
        return cache.key.set(
            a = imagePath, b = tpl,
            l1 = bg.value.toLong(), l2 = TemplatePainter.key(tpl, phase, lineColor),
            l3 = (if (lay.isInfinite) 1L else 0L) or (if (image != null) 2L else 0L),
            width = pageSize.width.toInt(), height = (pageSize.height + extra).toInt() + 1,
        )
    }

    private fun backgroundLayer(scope: DrawScope, i: Int): GraphicsLayer {
        val k = backgroundKey(i)
        return cache.layer(cache.slot(d.pages[i].id, LayerSlots.KIND_BACKGROUND), k, scope) { recordBackground(this, i) }
    }

    private fun recordBackground(scope: DrawScope, i: Int) = with(scope) {
        val spec = d.pages[i]
        val pageSize = lay.pageSize(i)
        val bg = pageColor(spec, d.style.backgroundColor, darkTheme, adjustsForDark(spec, darkAdjust))
        val tpl = (spec.template as? TemplateRef.Builtin)?.template ?: PageTemplate.Blank
        val lineColor = if (bg.luminanceIsDark()) LINE_ON_DARK else LINE_ON_LIGHT
        val phase = if (lay.isInfinite) lay.pageRects[i].top else 0f
        val image = (spec.template as? TemplateRef.Image)?.media?.let(backgroundImage)
        drawRect(bg, size = Size(size.width.toFloat(), size.height.toFloat()))
        TemplatePainter.draw(this, tpl, pageSize, lineColor, phase)
        if (image != null) drawImage(
            image, dstSize = IntSize(pageSize.width.toInt(), pageSize.height.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        )
    }

    private fun behindKey(i: Int, content: PageContent, liveHere: LiveSelection?): LayerKey {
        val pageSize = lay.pageSize(i)
        return cache.key.set(
            a = content, b = objectKey, c = liveHere?.ids,
            l1 = if (adjustsForDark(d.pages[i], darkAdjust)) 1L else 0L, l2 = hiddenKey,
            width = pageSize.width.toInt(), height = pageSize.height.toInt() + 1,
        )
    }

    private fun behindLayer(scope: DrawScope, i: Int, content: PageContent, liveHere: LiveSelection?): GraphicsLayer {
        val spec = d.pages[i]
        val k = behindKey(i, content, liveHere)
        return cache.layer(cache.slot(spec.id, LayerSlots.KIND_BEHIND), k, scope) {
            val pageSize = lay.pageSize(i)
            clipRect(0f, 0f, pageSize.width, pageSize.height) {
                drawInk(renderer, cache, spec, content.elements, adjustsForDark(spec, darkAdjust), objectImage, liveHere, painters, hidden, ObjectPass.Behind)
            }
        }
    }

    private fun textKey(i: Int): LayerKey {
        val pageSize = lay.pageSize(i)
        // l3: the page sits on a light surface in a dark note (PDF page), its text is painted with the light colours.
        val light = if (darkAdjust && !adjustsForDark(d.pages[i], darkAdjust)) 1L else 0L
        return cache.key.set(l1 = textVersion, l2 = i.toLong(), l3 = light, width = pageSize.width.toInt(), height = pageSize.height.toInt() + 1)
    }

    private fun textLayer(scope: DrawScope, i: Int, pageText: PageTextPainter): GraphicsLayer {
        val k = textKey(i)
        return cache.layer(cache.slot(d.pages[i].id, LayerSlots.KIND_TEXT), k, scope) {
            drawIntoCanvas { pageText.drawStatic(it.nativeCanvas, i) }
        }
    }

    private fun inkKey(i: Int, content: PageContent): LayerKey {
        val pageSize = lay.pageSize(i)
        return cache.key.set(
            a = content, b = objectKey,
            l1 = if (adjustsForDark(d.pages[i], darkAdjust)) 1L else 0L, l2 = hiddenKey,
            width = pageSize.width.toInt(), height = pageSize.height.toInt() + 1,
        )
    }

    private fun inkLayer(scope: DrawScope, i: Int, content: PageContent): GraphicsLayer {
        val spec = d.pages[i]
        val k = inkKey(i, content)
        return cache.layer(cache.slot(spec.id, LayerSlots.KIND_INK), k, scope) {
            cache.pruneDisplay(spec.id, content.elements.mapTo(HashSet()) { it.id })
            val pageSize = lay.pageSize(i)
            val adjusted = adjustsForDark(spec, darkAdjust)
            if (d.layout.mode == PageMode.Pages) clipRect(0f, 0f, pageSize.width, pageSize.height) {
                drawInk(renderer, cache, spec, content.elements, adjusted, objectImage, null, painters, hidden, ObjectPass.Front)
            } else drawInk(renderer, cache, spec, content.elements, adjusted, objectImage, null, painters, hidden, ObjectPass.Front)
        }
    }

    /** Records the first stale layer of page [i] (not drawn); true when it recorded one. */
    private fun prefetch(scope: DrawScope, i: Int, pageText: PageTextPainter?): Boolean {
        if (i < 0 || i >= d.pages.size) return false
        val id = d.pages[i].id
        if (cache.slot(id, LayerSlots.KIND_BACKGROUND).isStale(backgroundKey(i))) {
            backgroundLayer(scope, i)
            return true
        }
        if (pageText != null && cache.slot(id, LayerSlots.KIND_TEXT).isStale(textKey(i))) {
            textLayer(scope, i, pageText)
            return true
        }
        val content = source.content(id) ?: return false
        if (content.elements.isNotEmpty() && cache.slot(id, LayerSlots.KIND_INK).isStale(inkKey(i, content))) {
            inkLayer(scope, i, content)
            return true
        }
        if (cache.hasBehindObjects(content) && cache.slot(id, LayerSlots.KIND_BEHIND).isStale(behindKey(i, content, null))) {
            behindLayer(scope, i, content, null)
            return true
        }
        return false
    }

    private companion object {
        const val NO_ELEMENT = Long.MIN_VALUE
        val EDGE_DARK_INNER = Color(0xFF171717)
        val EDGE_DARK_OUTER = Color(0xFF363636)
        val EDGE_LIGHT = Color(0x1F000000)
        val LINE_ON_DARK = Color(0x24FFFFFF)
        val LINE_ON_LIGHT = Color(0x2E000000)
    }
}

/**
 * The live-ink transforms, kept as ONE Matrix and ONE mask Path for the life of the canvas and updated in place, so
 * `InProgressStrokes` never recomposes while scrolling (it reads both lazily when a stroke starts). [sync] runs on
 * pointer down and after composition, i.e. never while a stroke is being rendered.
 */
internal class LiveInkTransform {
    val docToView = Matrix()
    val pointerToWorld = Matrix()
    val mask = Path()

    /** The pen went down on a page that keeps its light surface in a dark note (PDF page): the live stroke uses the stored colour. */
    @Volatile var downOnLightPage = false
    private val view = Path()
    private val pages = Path()
    private var lastOrigin = Offset.Unspecified
    private var lastScale = Float.NaN
    private var lastSize = IntSize.Zero
    private var lastLayout: DocumentLayout? = null

    /**
     * Only mutates when the viewport or layout changed since the last sync: a second pointer landing during a stroke
     * (palm, finger) must not rewrite the mask the render thread is clipping with.
     */
    fun sync(viewport: Viewport, layout: DocumentLayout) {
        val org = viewport.origin
        val sc = viewport.scale
        if (org == lastOrigin && sc == lastScale && viewport.size == lastSize && layout === lastLayout) return
        lastOrigin = org
        lastScale = sc
        lastSize = viewport.size
        lastLayout = layout
        docToView.reset()
        docToView.translate(org.x, org.y)
        docToView.scale(sc, sc)
        fillMaskPath(mask, view, pages, viewport, layout)
    }
}

/**
 * Front-buffered live ink above the page layers. Strokes live in document space, so they render correctly
 * under any scroll/zoom. NOTE(WP-G): doc 2.5 says pointerEventToWorld = viewToDoc and strokeToWorld =
 * identity. Ink 1.1.0-alpha09 renders "world" straight onto the view, so the working arrangement is
 * pointerEventToWorld = identity (pointers are already view px) and strokeToWorld = docToView:
 * stroke space (the stroke's own coordinates) is then document space, as the doc intends.
 */
@Composable
private fun LiveInk(
    brush: Brush, transform: LiveInkTransform, source: PageSource, layout: DocumentLayout,
    darkAdjust: Boolean, straightLine: Boolean = false,
) {
    val latestStraight by rememberUpdatedState(straightLine)
    val displayBrush = remember(brush, darkAdjust) { if (darkAdjust) DarkModeInk.invert(brush) else brush }
    val latestLayout by rememberUpdatedState(layout)
    val latestSource by rememberUpdatedState(source)
    val latestBrush by rememberUpdatedState(brush)

    InProgressStrokes(
        defaultBrush = displayBrush,
        nextBrush = { if (transform.downOnLightPage) latestBrush else displayBrush },
        pointerEventToWorldTransform = transform.pointerToWorld,
        strokeToWorldTransform = transform.docToView,
        maskPath = transform.mask,
        textureBitmapStore = GrainTextureStore,
        onStrokesFinished = { finished: List<Stroke> ->
            val lay = latestLayout
            for (s in finished) {
                val snapped = if (latestStraight) StraightLine.snap(s) else s
                // The dark display mapping is not reversible: store the picked brush, not the displayed one.
                val stored = snapped.copy(latestBrush)
                val placed = StrokePlacement.toPage(stored, lay) ?: continue
                latestSource.addStroke(latestSource.document.pages[placed.pageIndex].id, placed.stroke)
            }
        },
    )
}

/** [maskPath] into [out] in place ([view] and [pages] are scratch paths). */
internal fun fillMaskPath(out: Path, view: Path, pages: Path, viewport: Viewport, layout: DocumentLayout) {
    out.reset()
    if (layout.isInfinite || viewport.size.width == 0) return
    view.reset()
    view.addRect(Rect(0f, 0f, viewport.size.width.toFloat(), viewport.size.height.toFloat()))
    pages.reset()
    for (i in layout.visiblePages(viewport.visibleDoc)) {
        val r = layout.pageRects[i]
        pages.addRect(Rect(viewport.docToView(r.topLeft), viewport.docToView(r.bottomRight)))
    }
    out.op(view, pages, PathOperation.Difference)
}

/** View-space region where live ink must not show: everything outside the pages in Pages mode (paper edge and gaps). */
internal fun maskPath(viewport: Viewport, layout: DocumentLayout): Path {
    val path = Path()
    if (layout.isInfinite || viewport.size.width == 0) return path
    val view = Path().apply { addRect(Rect(0f, 0f, viewport.size.width.toFloat(), viewport.size.height.toFloat())) }
    val pages = Path().apply {
        for (i in layout.visiblePages(viewport.visibleDoc)) {
            val r = layout.pageRects[i]
            addRect(Rect(viewport.docToView(r.topLeft), viewport.docToView(r.bottomRight)))
        }
    }
    path.op(view, pages, PathOperation.Difference)
    return path
}
