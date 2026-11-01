package org.samesame.notes.feature.composer.session

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.FrameHandle
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.frame
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage

@OptIn(ExperimentalCoroutinesApi::class)
class ImageCropTest {

    @Test fun handlesMoveTheirEdgesAndRespectTheMinimum() {
        val full = CropFraction()
        val c = ImageCrop.drag(full, FrameHandle.TL, 0.2f, 0.1f, 0.1f, 0.1f)
        assertThat(c).isEqualTo(CropFraction(0.2f, 0.1f, 1f, 1f))
        val edge = ImageCrop.drag(full, FrameHandle.R, -0.95f, 0.5f, 0.1f, 0.1f)
        assertThat(edge.right).isWithin(1e-6f).of(0.1f) // clamped at the minimum width
        assertThat(edge.top).isEqualTo(0f) // an edge handle leaves the other axis alone
        assertThat(ImageCrop.drag(full, FrameHandle.BL, -0.3f, 0.3f, 0.1f, 0.1f)).isEqualTo(CropFraction(0f, 0f, 1f, 1f))
    }

    @Test fun movingKeepsTheSizeInsideTheImage() {
        val c = CropFraction(0.2f, 0.2f, 0.6f, 0.5f)
        val moved = ImageCrop.drag(c, null, 0.9f, -0.9f, 0.1f, 0.1f)
        assertThat(moved.right).isWithin(1e-6f).of(1f)
        assertThat(moved.width).isWithin(1e-6f).of(0.4f)
        assertThat(moved.top).isWithin(1e-6f).of(0f)
        assertThat(moved.height).isWithin(1e-6f).of(0.3f)
    }

    @Test fun pixelsAreInsideTheBitmap() {
        assertThat(CropFraction(0.25f, 0.5f, 0.75f, 1f).pixels(400, 200).toList()).containsExactly(100, 100, 200, 100).inOrder()
        assertThat(CropFraction(0.999f, 0f, 1f, 1f).pixels(10, 10)[2]).isAtLeast(1)
    }

    @Test fun applyKeepsTheKeptPartInPlace() {
        val f = FloatingObject(ImageObject("i", "media/a.png", 400f, 200f), RectPu(100f, 100f, 500f, 300f))
        val out = ImageCrop.apply(f, CropFraction(0.5f, 0f, 1f, 0.5f), "media/b.png")
        assertThat(out.rect).isEqualTo(RectPu(300f, 100f, 500f, 200f))
        val img = out.obj as ImageObject
        assertThat(img.media).isEqualTo("media/b.png")
        assertThat(img.widthPu).isEqualTo(200f)
        assertThat(img.heightPu).isEqualTo(100f)
    }

    @Test fun applyFollowsTheRotation() {
        // Rotated 90 degrees clockwise: the image's right half ends up below the centre.
        val f = FloatingObject(ImageObject("i", "media/a.png", 400f, 200f), RectPu(100f, 100f, 500f, 300f), rotationDeg = 90f)
        val out = ImageCrop.apply(f, CropFraction(0.5f, 0f, 1f, 1f), "media/b.png")
        val fr = out.frame
        assertThat(fr.cx).isWithin(0.01f).of(300f)
        assertThat(fr.cy).isWithin(0.01f).of(300f)
        assertThat(fr.w).isWithin(0.01f).of(200f)
        assertThat(fr.h).isWithin(0.01f).of(200f)
        assertThat(out.rotationDeg).isEqualTo(90f)
    }

    private val storage = FakeStorage()

    private fun TestScope.session() = EditorSession(
        noteId = "n1",
        initialDocument = NoteDocument(id = "n1", pages = listOf(PageSpec("p1"))),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        pageLoader = storage::load,
        saveSink = storage::save,
        packContainer = storage::pack,
        mediaLoader = { null },
        mediaSink = { _, ext -> "media/cropped.$ext" },
        prefs = FakePrefs(),
        clipboard = EditorClipboard(),
        clock = { testScheduler.currentTime },
    )

    @Test fun cropIsOneUndoStepAndKeepsTheImageSelected() = runTest {
        val s = session(); runCurrent(); s.pages.await("p1")
        s.insertImage("p1", ByteArray(4), "png", 400f, 200f, Offset(300f, 200f))
        val el = s.content("p1")!!.elements.single()
        val before = (el as ObjectElement).floating()!!
        assertThat(s.cropImage("p1", el.id, CropFraction(0f, 0f, 0.5f, 1f), ByteArray(2), "png")).isTrue()
        val after = (s.content("p1")!!.elements.single() as ObjectElement).floating()!!
        assertThat((after.obj as ImageObject).media).isEqualTo("media/cropped.png")
        assertThat(after.rect.width).isWithin(0.01f).of(before.rect.width / 2f)
        assertThat(s.selection.current.ids).containsExactly(el.id)
        assertThat(s.undo()).isTrue()
        assertThat((s.content("p1")!!.elements.single() as ObjectElement).floating()).isEqualTo(before)
        // A full crop changes nothing.
        assertThat(s.cropImage("p1", el.id, CropFraction(), ByteArray(2), "png")).isFalse()
    }
}
