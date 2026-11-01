package org.samesame.notes.feature.composer.pages

import androidx.compose.foundation.layout.width
import org.samesame.notes.core.designsystem.theme.fixedSp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.shape.CircleShape
import org.samesame.notes.core.designsystem.icon.AppIcons
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.ink.geometry.AffineTransform
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.ink.brush.GrainTextureStore
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.PageRatio
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.feature.composer.R
import org.samesame.notes.feature.composer.canvas.TemplatePainter
import kotlin.math.roundToInt

/**
 * Page sorter (`#57`): a grid of page thumbnails (background, template, ink), the current page outlined, a bookmark flag
 * per page, long-press drag to reorder and a trailing "add page" tile. Header buttons (reference view):
 * Edit (multi-select: Copy / Share / Delete), bookmark (filter to bookmarked pages, "No bookmarks" when empty),
 * search (a search field; lists the pages with a hit, recent keywords while the field is empty) and close.
 */
@Composable
internal fun PageSorterScreen(
    pages: List<PageSpec>,
    content: (String) -> PageContent?,
    /** The note's layout: the shape of pages without their own size (v6). */
    pageLayout: PageLayout = PageLayout(),
    currentIndex: Int,
    darkTheme: Boolean,
    onReorder: (from: Int, to: Int) -> Unit,
    onAddPage: () -> Unit,
    onToggleBookmark: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenPage: (Int) -> Unit,
    onBack: () -> Unit,
    /** Page indices (0-based) that contain [query]; backs the search mode. */
    searchPages: (String) -> List<Int> = { emptyList() },
    onDeleteSelected: (Set<String>) -> Unit = {},
    /** Edit-mode Copy / Share / Save as file for the selected pages. */
    onCopySelected: (Set<String>) -> Unit = {},
    onShareSelected: (Set<String>) -> Unit = {},
    onSaveSelected: (Set<String>) -> Unit = {},
    /** Tile menu Paste (normal mode, when [canPaste] says the page clipboard holds pages): paste after page [index]. */
    canPaste: () -> Boolean = { false },
    onPaste: (afterIndex: Int) -> Unit = {},
) {
    val colors = SameSameTheme.colors
    val renderer = remember { CanvasStrokeRenderer.create(GrainTextureStore) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var drag by remember { mutableFloatStateOf(0f) }
    var mode by remember { mutableStateOf(SorterMode.Normal) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(emptyList<String>()) }
    val hits = remember(query, pages) { if (mode == SorterMode.Search && query.isNotEmpty()) searchPages(query) else emptyList() }
    val visible = PageSorterModel.visibleIndices(mode, pages, hits)
    // Back leaves the mode first (reference: setMode(0)), then the sorter.
    BackHandler { if (mode != SorterMode.Normal) { mode = SorterMode.Normal; selected = emptySet(); query = "" } else onBack() }
    LaunchedEffect(pages) { selected = PageSorterModel.prune(selected, pages) }
    fun setMode(m: SorterMode) {
        if (m == mode) return
        selected = emptySet(); query = ""
        mode = m
    }

    // #57: the screen fill is #171717 (the card colour); header 56 dp: "1/1" 19.3 dp at 25.5 dp (current white, total grey), "Edit" text,
    // bookmark / search / close glyphs centred 99.4 / 64.4 / 29.4 dp from the right edge (35 dp apart).
    Surface(Modifier.fillMaxSize(), color = colors.card) {
        Column(Modifier.fillMaxSize()) {
            when (mode) {
                SorterMode.Edit -> EditHeader(
                    count = selected.size,
                    allSelected = visible.isNotEmpty() && selected.size == visible.size,
                    onToggleAll = { selected = PageSorterModel.selectAll(visible.map { pages[it].id }, selected) },
                )
                SorterMode.Search -> SearchHeader(
                    query = query,
                    onQuery = { query = it },
                    onSubmit = { recent = PageSorterModel.addRecent(recent, query) },
                    onBack = { setMode(SorterMode.Normal) },
                )
                else -> Box(Modifier.fillMaxWidth().height(56.dp)) {
                    if (mode == SorterMode.Normal) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = colors.textPrimary)) { append("${currentIndex + 1}/") }
                                withStyle(SpanStyle(color = Color(0xFF8C8C8C))) { append("${pages.size}") }
                            },
                            fontSize = fixedSp(19.3f),
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 25.5.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.feature_composer_edit), color = colors.textPrimary, fontSize = fixedSp(19.3f),
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 134.dp)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { setMode(SorterMode.Edit) },
                    )
                    HeaderGlyph(
                        AppIcons.Bookmark, R.string.feature_composer_bookmark, 99.4.dp, colors.textPrimary,
                        selected = mode == SorterMode.Bookmark,
                    ) { setMode(if (mode == SorterMode.Bookmark) SorterMode.Normal else SorterMode.Bookmark) }
                    HeaderGlyph(AppIcons.Search, R.string.feature_composer_search, 64.4.dp, colors.textPrimary) { setMode(SorterMode.Search) }
                    HeaderGlyph(AppIcons.Close, R.string.feature_composer_close, 29.4.dp, colors.textPrimary, onClick = onBack)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 29.7.dp, top = 8.dp)) {
                    if (mode == SorterMode.Search && query.isEmpty()) {
                        RecentSearches(recent, onPick = { query = it }, onClear = { recent = emptyList() })
                    }
                    val addTile = PageSorterModel.showsAddTile(mode)
                    val cells = visible.size + if (addTile) 1 else 0
                    if (!(mode == SorterMode.Search && query.isEmpty())) for (rowStart in 0 until cells step 3) {
                        Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(27.4.dp)) {
                            for (col in 0 until 3) {
                                val cell = rowStart + col
                                if (cell == visible.size && addTile) {
                                    AddPageTile(Modifier.width(99.dp), onAddPage)
                                } else if (cell < visible.size) {
                                    val index = visible[cell]
                                    val spec = pages[index]
                                    var tileSize by remember { mutableStateOf(IntSize.Zero) }
                                    val dragging = dragFrom == index
                                    val canDrag = mode == SorterMode.Normal
                                    Box(
                                        Modifier.width(99.dp)
                                            .offset { if (dragging) IntOffset(0, drag.roundToInt()) else IntOffset.Zero }
                                            .pointerInput(index, pages.size, tileSize, canDrag) {
                                                if (!canDrag) return@pointerInput
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { dragFrom = index; drag = 0f },
                                                    onDragEnd = {
                                                        if (dragFrom == index && tileSize.width > 0) {
                                                            val cols = 3
                                                            val colDelta = (drag / (tileSize.width + 72)).roundToInt()
                                                            val rowDelta = (drag / (tileSize.height + 100)).roundToInt()
                                                            val target = (index + rowDelta * cols + colDelta).coerceIn(0, pages.size - 1)
                                                            if (target != index) onReorder(index, target)
                                                        }
                                                        dragFrom = -1; drag = 0f
                                                    },
                                                    onDragCancel = { dragFrom = -1; drag = 0f },
                                                    onDrag = { _, amount -> drag += amount.y },
                                                )
                                            },
                                    ) {
                                        PageThumb(
                                            spec = spec,
                                            heightOverWidth = spec.heightOverWidth(pageLayout),
                                            content = content(spec.id),
                                            darkTheme = darkTheme,
                                            current = index == currentIndex && mode != SorterMode.Edit,
                                            elevated = dragging,
                                            renderer = renderer,
                                            onOpen = {
                                                if (mode == SorterMode.Edit) selected = PageSorterModel.toggle(selected, spec.id)
                                                else onOpenPage(index)
                                            },
                                            onToggleBookmark = { onToggleBookmark(spec.id) },
                                            onDelete = { onDelete(spec.id) },
                                            onSize = { tileSize = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            number = index + 1,
                                            checked = if (mode == SorterMode.Edit) spec.id in selected else null,
                                            showMore = mode == SorterMode.Normal,
                                            canPaste = canPaste,
                                            onPaste = { onPaste(index) },
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.width(99.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                // Empty states: "No bookmarks" / "No results found" (reference strings), centred in the list area.
                val empty = when {
                    mode == SorterMode.Bookmark && visible.isEmpty() -> R.string.feature_composer_no_bookmarks
                    mode == SorterMode.Search && query.isNotEmpty() && visible.isEmpty() -> R.string.feature_composer_no_results
                    else -> null
                }
                if (empty != null) {
                    Text(stringResource(empty), color = Color(0xFF8C8C8C), fontSize = fixedSp(17f), modifier = Modifier.align(Alignment.Center))
                }
            }
            if (mode == SorterMode.Edit) {
                EditBottomBar(
                    enabled = selected.isNotEmpty(),
                    onCopy = { onCopySelected(selected); setMode(SorterMode.Normal) },
                    onShare = { onShareSelected(selected); setMode(SorterMode.Normal) },
                    onDelete = { onDeleteSelected(selected); setMode(SorterMode.Normal) },
                    onSave = { onSaveSelected(selected); setMode(SorterMode.Normal) },
                )
            }
        }
    }
}

/** Edit mode header: "All" checkbox then "Select pages" / "n selected". */
@Composable
private fun EditHeader(count: Int, allSelected: Boolean, onToggleAll: () -> Unit) {
    val colors = SameSameTheme.colors
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.padding(start = 12.dp).clip(RoundedCornerShape(12.dp)).clickable(role = androidx.compose.ui.semantics.Role.Checkbox, onClick = onToggleAll).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (allSelected) AppIcons.Check else AppIcons.RadioOff, null, Modifier.size(24.dp), tint = if (allSelected) colors.accent else colors.textPrimary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.feature_composer_select_all), color = colors.textPrimary, fontSize = fixedSp(16f))
        }
        Text(
            if (count == 0) stringResource(R.string.feature_composer_select_pages) else stringResource(R.string.feature_composer_n_selected, count),
            color = colors.textPrimary, fontSize = fixedSp(19.3f), modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** Search mode header: back arrow, the field (hint "Search…") and a clear button. */
@Composable
private fun SearchHeader(query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    val colors = SameSameTheme.colors
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, Modifier.padding(start = 6.dp).size(40.dp)) {
            Icon(AppIcons.Back, stringResource(R.string.feature_composer_back), Modifier.size(24.dp), tint = colors.textPrimary)
        }
        Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(stringResource(R.string.feature_composer_search_placeholder), color = colors.textPrimary.copy(alpha = 0.4f), fontSize = fixedSp(18f), maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = { onQuery(it.take(org.samesame.notes.core.richtext.TextSearch.MAX_QUERY_LENGTH)) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = fixedSp(18f), color = colors.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, Modifier.padding(end = 6.dp).size(40.dp)) {
            Icon(AppIcons.Close, stringResource(R.string.feature_composer_search_clear), Modifier.size(24.dp), tint = colors.textPrimary)
        }
    }
}

/** Recent page searches under an empty search field, with "Clear history" (or "No recent searches"). */
@Composable
private fun RecentSearches(recent: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    val colors = SameSameTheme.colors
    if (recent.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(top = 160.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.feature_composer_no_recent_searches), color = Color(0xFF8C8C8C), fontSize = fixedSp(17f))
        }
        return
    }
    recent.forEach { k ->
        Text(k, color = colors.textPrimary, fontSize = fixedSp(16f), modifier = Modifier.fillMaxWidth().clickable { onPick(k) }.padding(vertical = 14.dp, horizontal = 4.dp))
    }
    Text(
        stringResource(R.string.feature_composer_clear_history), color = colors.accent, fontSize = fixedSp(16f),
        modifier = Modifier.clickable(onClick = onClear).padding(vertical = 14.dp, horizontal = 4.dp),
    )
}

/** Edit-mode bottom bar: Copy, Share, Delete and a ⋮ with "Save as file" (reference bottom menu). */
@Composable
private fun EditBottomBar(enabled: Boolean, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onSave: () -> Unit) {
    val colors = SameSameTheme.colors
    var more by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        BarAction(AppIcons.Copy, R.string.feature_composer_sel_copy, enabled, onCopy)
        BarAction(AppIcons.Share, R.string.feature_composer_ov_share, enabled, onShare)
        BarAction(AppIcons.Trash, R.string.feature_composer_ov_delete, enabled, onDelete)
        Box {
            BarAction(AppIcons.More, R.string.feature_composer_more_options, enabled) { more = true }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.feature_composer_ov_save_as_file), color = colors.textPrimary) }, onClick = { more = false; onSave() })
            }
        }
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    val colors = SameSameTheme.colors
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (enabled) colors.textPrimary else colors.textPrimary.copy(alpha = 0.4f)
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(stringResource(label), color = tint, fontSize = fixedSp(12f), maxLines = 1)
    }
}

@Composable
private fun HeaderGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, fromEnd: androidx.compose.ui.unit.Dp, tint: Color,
    selected: Boolean = false, onClick: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.padding(end = fromEnd - 20.dp).size(40.dp).clip(CircleShape)
                .then(if (selected) Modifier.background(Color(0x33FFFFFF)) else Modifier) // selected pill, like the toolbar buttons
                .clickable(onClick = onClick, role = androidx.compose.ui.semantics.Role.Button),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, stringResource(label), Modifier.size(24.dp), tint = tint)
        }
    }
}

/** "+" tile (#57): 99 x 139.8 dp, #030303 with a 1 px #676767 outline. */
@Composable
private fun AddPageTile(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.aspectRatio(1f / PageRatio.A4Portrait.heightOverWidth)
            .background(Color(0xFF030303)).border(0.4.dp, Color(0xFF676767)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Add, stringResource(R.string.feature_composer_add_page), Modifier.size(24.dp), tint = Color(0xFF8C8C8C))
    }
}

/**
 * Page tile (#57): 99 x 139.8 dp, the current page ringed 1.9 dp in the accent colour; a grey bookmark flag top-right when
 * bookmarked, an overflow button bottom-right and the page number (grey, 14 dp) centred 17 dp under the tile.
 */
@Composable
private fun PageThumb(
    spec: PageSpec,
    heightOverWidth: Float,
    content: PageContent?,
    darkTheme: Boolean,
    current: Boolean,
    elevated: Boolean,
    renderer: CanvasStrokeRenderer,
    onOpen: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDelete: () -> Unit,
    onSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
    number: Int = 0,
    /** Edit mode: null = no checkbox, else its state. */
    checked: Boolean? = null,
    showMore: Boolean = true,
    /** Read when the menu opens, so a copy made in edit mode shows up. */
    canPaste: () -> Boolean = { false },
    onPaste: () -> Unit = {},
) {
    val colors = SameSameTheme.colors
    var menu by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f / PageRatio.A4Portrait.heightOverWidth)
                    .then(if (current) Modifier.border(1.9.dp, colors.accent) else Modifier)
                    .clickable(onClick = onOpen)
                    .onSizeChanged { onSize(it) },
            ) {
                PageThumbContent(spec, heightOverWidth, content, darkTheme, renderer)
            }
            if (checked == null) {
                // The flag in the top-right corner is the bookmark toggle (outline when off).
                Box(Modifier.align(Alignment.TopEnd).size(36.dp).clickable(onClick = onToggleBookmark, role = androidx.compose.ui.semantics.Role.Button), contentAlignment = Alignment.TopEnd) {
                    Icon(
                        if (spec.isBookmarked) AppIcons.BookmarkFilled else AppIcons.Bookmark,
                        stringResource(R.string.feature_composer_bookmark),
                        Modifier.padding(top = 3.dp, end = 11.dp).size(18.dp),
                        tint = if (spec.isBookmarked) Color(0xFF7A7A7A) else Color(0xFF7A7A7A).copy(alpha = 0.45f),
                    )
                }
            } else {
                Icon(
                    if (checked) AppIcons.Check else AppIcons.RadioOff, null,
                    Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(if (checked) colors.accent else Color(0x66000000)),
                    tint = Color.White,
                )
            }
            if (showMore) Box(Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp).size(28.dp)) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(Color(0x66000000)).clickable { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(AppIcons.More, stringResource(R.string.feature_composer_more_options), Modifier.size(20.dp), tint = colors.textPrimary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.feature_composer_bookmark)) }, onClick = { menu = false; onToggleBookmark() })
                    if (canPaste()) DropdownMenuItem(text = { Text(stringResource(R.string.feature_composer_sel_paste)) }, onClick = { menu = false; onPaste() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.feature_composer_delete_page)) }, onClick = { menu = false; onDelete() })
                }
            }
            if (elevated) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.15f)))
        }
        Text("$number", color = Color(0xFF8C8C8C), fontSize = fixedSp(14f), modifier = Modifier.padding(top = 9.dp))
    }
}

@Composable
private fun PageThumbContent(spec: PageSpec, heightOverWidth: Float, content: PageContent?, darkTheme: Boolean, renderer: CanvasStrokeRenderer) {
    val template = (spec.template as? TemplateRef.Builtin)?.template ?: PageTemplate.Blank
    Canvas(Modifier.fillMaxSize()) {
        val pageW = Limits.PAGE_WIDTH_PU
        val pageH = pageW * heightOverWidth
        // The tile keeps the A4 shape; a page of another shape (an imported PDF page) is fitted and centred in it.
        val f = minOf(size.width / pageW, size.height / pageH)
        val dx = (size.width - pageW * f) / 2f
        val dy = (size.height - pageH * f) / 2f
        val bg = if (darkTheme) Color(0xFF030303) else Color.White
        val line = if (darkTheme) Color(0x55FFFFFF) else Color(0x33000000)
        drawRect(bg, Offset(dx, dy), Size(pageW * f, pageH * f))
        withTransform({ translate(dx, dy); scale(f, f, Offset.Zero) }) {
            clipRect(0f, 0f, pageW, pageH) {
                TemplatePainter.draw(this, template, Size(pageW, pageH), line)
                content?.elements?.forEach { if (it is InkElement) drawThumbStroke(renderer, it.stroke) }
            }
        }
    }
}

private fun DrawScope.drawThumbStroke(renderer: CanvasStrokeRenderer, stroke: Stroke) {
    drawIntoCanvas { c -> renderer.draw(c.nativeCanvas, stroke, AffineTransform.IDENTITY) }
}
