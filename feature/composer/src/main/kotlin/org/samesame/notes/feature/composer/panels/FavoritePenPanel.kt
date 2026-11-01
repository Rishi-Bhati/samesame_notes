package org.samesame.notes.feature.composer.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.samesame.notes.core.data.repository.FavoritePen
import org.samesame.notes.core.designsystem.icon.AppIcons
import org.samesame.notes.core.designsystem.theme.SameSameTheme
import org.samesame.notes.core.ink.brush.PenCatalog
import org.samesame.notes.feature.composer.R

/**
 * Favourite pen mode (the pen-with-star button of the pen panel): the saved pens as a grid of 70 x 74 dp cells (a pen picture
 * over a 60 dp disc, with a stroke sample in the pen colour), the selected one outlined. Panel 300 dp wide, 298 dp tall in view
 * mode and 358 dp in edit mode ("Remove pen from favourites": a delete badge on each cell, Cancel | Done). Header: back to the
 * pen panel at the left, overflow menu (Add / Delete) and close. An empty list shows a hint. Dimensions from the reference
 * app's favourite layout resources.
 */
@Composable
internal fun FavoritePenPanel(
    pens: List<FavoritePen>,
    selectedIndex: Int,
    canAdd: Boolean,
    onApply: (index: Int) -> Unit,
    onAdd: () -> Unit,
    onRemoveMany: (removed: Set<Int>) -> Unit,
    onBackToPen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf(emptySet<Int>()) }
    var menuOpen by remember { mutableStateOf(false) }
    PanelCard(modifier, stroked = true) {
        Column {
            PanelHeader(
                title = if (editing) stringResource(R.string.feature_composer_favourite_remove_title) else null,
                onClose = if (editing) null else onClose,
                modifier = Modifier.padding(top = 4.dp),
                leading = {
                    if (!editing) HeaderIcon(AppIcons.Pen, stringResource(R.string.feature_composer_favourite_pen_mode), onBackToPen)
                },
                actions = {
                    if (!editing) Box {
                        HeaderIcon(AppIcons.More, stringResource(R.string.feature_composer_more_options)) { menuOpen = true }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feature_composer_add)) },
                                enabled = canAdd,
                                onClick = { menuOpen = false; onAdd() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.feature_composer_delete)) },
                                enabled = pens.isNotEmpty(),
                                onClick = { menuOpen = false; pendingRemoval = emptySet(); editing = true },
                            )
                        }
                    }
                },
            )
            val body = (if (editing) 358.dp else 298.dp) - 60.dp - (if (editing) 58.dp else 0.dp)
            Box(Modifier.fillMaxWidth().height(body)) {
                if (pens.isEmpty()) {
                    Text(
                        stringResource(R.string.feature_composer_favourite_empty),
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    LazyVerticalGrid(
                        GridCells.Fixed(4), Modifier.fillMaxSize().padding(horizontal = 10.dp),
                    ) {
                        itemsIndexed(pens) { i, pen ->
                            val removed = i in pendingRemoval
                            if (!removed) FavouriteCell(
                                pen, selected = !editing && i == selectedIndex, editing = editing,
                                onClick = { if (!editing) onApply(i) },
                                onDelete = { pendingRemoval = pendingRemoval + i },
                            )
                        }
                        if (!editing && canAdd) item {
                            Box(
                                Modifier.size(70.dp, 74.dp).clickable(role = Role.Button, onClick = onAdd)
                                    .semantics { contentDescription = "Add favourite pen" },
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(AppIcons.Add, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
            if (editing) DialogActions(
                onCancel = { pendingRemoval = emptySet(); editing = false },
                onDone = { onRemoveMany(pendingRemoval); pendingRemoval = emptySet(); editing = false },
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

@Composable
private fun FavouriteCell(pen: FavoritePen, selected: Boolean, editing: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val color = Color(pen.color.toInt()).copy(alpha = if (pen.highlighter) pen.opacity / 100f else 1f)
    val accent = SameSameTheme.colors.accent
    Box(Modifier.size(70.dp, 74.dp).clickable(enabled = !editing, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = if (pen.highlighter) "Highlighter ${pen.level}" else "${pen.penType.name} ${pen.level}" }) {
        Box(
            Modifier.align(Alignment.BottomCenter).size(60.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .then(if (selected) Modifier.androidBorder(accent) else Modifier),
        )
        Canvas(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 20.dp, height = 56.dp)) {
            if (pen.highlighter) drawMarkerTip(pen.tip.coerceIn(0, 3))
            else drawPenTip(PenCatalog.pens.indexOfFirst { it.type == pen.penType }.coerceAtLeast(0))
        }
        Canvas(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 3.dp).size(width = 40.dp, height = 27.dp)) {
            val p = Path().apply {
                moveTo(0f, size.height * 0.9f)
                cubicTo(size.width * 0.25f, 0f, size.width * 0.6f, size.height * 0.1f, size.width * 0.6f, size.height * 0.45f)
                cubicTo(size.width * 0.6f, size.height * 0.8f, size.width * 0.85f, size.height * 0.6f, size.width, size.height * 0.4f)
            }
            val w = if (pen.highlighter) 8.dp.toPx() else (1.5f + pen.level / 100f * 5f).dp.toPx()
            drawPath(p, color, style = Stroke(width = w, cap = if (pen.highlighter) StrokeCap.Butt else StrokeCap.Round))
        }
        if (editing) Box(
            Modifier.padding(top = 14.dp).size(20.dp).clip(CircleShape).background(Color(0xFFE5484D)).clickable(onClick = onDelete)
                .semantics { contentDescription = "Remove" },
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Remove, null, Modifier.size(14.dp), tint = Color.White) }
    }
}

private fun Modifier.androidBorder(c: Color): Modifier = this.then(Modifier.border(BorderStroke(2.dp, c), CircleShape))
