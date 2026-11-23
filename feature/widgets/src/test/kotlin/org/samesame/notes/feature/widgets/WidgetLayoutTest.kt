package org.samesame.notes.feature.widgets

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.NoteFilter

class WidgetLayoutTest {
    @Test fun `creator picks its layout by size`() {
        assertThat(CreatorLayout.forSize(DpSize(120.dp, 60.dp))).isEqualTo(CreatorLayout.Small)
        assertThat(CreatorLayout.forSize(DpSize(320.dp, 60.dp))).isEqualTo(CreatorLayout.Strip)
        assertThat(CreatorLayout.forSize(DpSize(320.dp, 130.dp))).isEqualTo(CreatorLayout.Tall)
        assertThat(CreatorLayout.forSize(DpSize(320.dp, 200.dp))).isEqualTo(CreatorLayout.Full)
        // Narrow but tall: still the two buttons.
        assertThat(CreatorLayout.forSize(DpSize(150.dp, 200.dp))).isEqualTo(CreatorLayout.Small)
        assertThat(CreatorLayout.forSize(DpSize(40.dp, 40.dp))).isEqualTo(CreatorLayout.Small)
    }

    @Test fun `single note drops the page when low`() {
        assertThat(SingleNoteLayout.pageArea(DpSize(300.dp, 80.dp))).isNull()
        val area = SingleNoteLayout.pageArea(DpSize(300.dp, 180.dp))!!
        assertThat(area.width).isEqualTo(276.dp)
        assertThat(area.height.value).isWithin(0.01f).of(180f - 24f - 32f - 4f)
    }

    @Test fun `pages grid has two to four columns`() {
        assertThat(NoteGridLayout.columns(180.dp)).isEqualTo(2)
        assertThat(NoteGridLayout.columns(330.dp)).isEqualTo(3)
        assertThat(NoteGridLayout.columns(700.dp)).isEqualTo(4)
        val cell = NoteGridLayout.cellWidth(330.dp)
        assertThat(cell.value * 3 + NoteGridLayout.CELL_GAP.value * 2).isWithin(0.01f).of(306f)
    }

    @Test fun `automatic display shows pages only when there is room`() {
        assertThat(NotesDisplay.resolve(NotesDisplay.Auto, DpSize(320.dp, 300.dp))).isEqualTo(NotesDisplay.Pages)
        assertThat(NotesDisplay.resolve(NotesDisplay.Auto, DpSize(320.dp, 180.dp))).isEqualTo(NotesDisplay.List)
        assertThat(NotesDisplay.resolve(NotesDisplay.Auto, DpSize(200.dp, 400.dp))).isEqualTo(NotesDisplay.List)
        assertThat(NotesDisplay.resolve(NotesDisplay.List, DpSize(400.dp, 400.dp))).isEqualTo(NotesDisplay.List)
        assertThat(NotesDisplay.resolve(NotesDisplay.Pages, DpSize(200.dp, 120.dp))).isEqualTo(NotesDisplay.Pages)
        NotesDisplay.entries.forEach { assertThat(NotesDisplay.fromKey(it.key)).isEqualTo(it) }
        assertThat(NotesDisplay.fromKey("grid")).isNull()
    }

    @Test fun `quick capture is a bar when low and a bento when tall enough`() {
        assertThat(QuickCaptureLayout.forSize(DpSize(300.dp, 80.dp))).isEqualTo(CaptureShape.Row)
        assertThat(QuickCaptureLayout.forSize(DpSize(300.dp, 140.dp))).isEqualTo(CaptureShape.Bento)
    }

    @Test fun `page crop keeps the top of the page`() {
        assertThat(WidgetBitmaps.cropRect(400, 800, 0.5f)).isEqualTo(400 to 200)
        // A page shorter than the frame is kept whole.
        assertThat(WidgetBitmaps.cropRect(400, 100, 0.5f)).isEqualTo(400 to 100)
        assertThat(WidgetBitmaps.cropRect(0, 0, 1f)).isEqualTo(1 to 1)
    }

    @Test fun `thumbnails share one memory budget`() {
        val (w, h) = WidgetBitmaps.thumbSize(count = 30, desiredW = 400, aspect = 1f)
        assertThat(w.toLong() * h * 2 * 30).isAtMost(WidgetBitmaps.THUMB_BUDGET_BYTES.toLong())
        assertThat(WidgetBitmaps.thumbSize(count = 2, desiredW = 100, aspect = 1.2f)).isEqualTo(100 to 120)
        assertThat(WidgetBitmaps.thumbSize(count = 100_000, desiredW = 100, aspect = 1f).first).isEqualTo(48)
    }

    @Test fun `a large page is scaled under the bitmap limit`() {
        assertThat(WidgetBitmaps.scaleFor(100, 100)).isEqualTo(1f)
        val s = WidgetBitmaps.scaleFor(2000, 2000)
        assertThat((2000 * s).toLong() * (2000 * s).toLong() * 4).isAtMost(WidgetBitmaps.MAX_BITMAP_BYTES.toLong() + 8000)
    }

    @Test fun `list sources round-trip and map to catalog filters`() {
        val folder = ListSource.Folder(FolderId("f1"))
        listOf(ListSource.Recent, ListSource.Favorites, folder).forEach { assertThat(ListSource.fromKey(it.key())).isEqualTo(it) }
        assertThat(ListSource.fromKey(null)).isEqualTo(ListSource.Recent)
        assertThat(ListSource.fromKey("folder:")).isEqualTo(ListSource.Recent)
        assertThat(ListSource.Favorites.filter()).isEqualTo(NoteFilter.Favorites)
        assertThat(folder.filter()).isEqualTo(NoteFilter.InFolder(FolderId("f1")))
        assertThat(ListSource.Recent.filter()).isEqualTo(NoteFilter.All)
    }
}
