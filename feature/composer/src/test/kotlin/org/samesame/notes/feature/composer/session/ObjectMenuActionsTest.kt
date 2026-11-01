package org.samesame.notes.feature.composer.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.PageElement
import org.samesame.notes.core.ink.page.ObjectElement
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.box
import org.samesame.notes.core.ink.page.floating
import org.samesame.notes.core.model.document.FloatingObject
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageSpec
import org.samesame.notes.core.model.document.isFittedToWidth
import org.samesame.notes.core.model.document.RectPu
import org.samesame.notes.core.model.document.TextWrap
import org.samesame.notes.core.model.document.TextBoxObject
import org.samesame.notes.core.model.document.VoiceObject
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.feature.composer.FakePrefs
import org.samesame.notes.feature.composer.FakeStorage
import org.samesame.notes.feature.composer.fakeElement

/** The object more-menu actions on a session: each one must be a single undo step. */
@OptIn(ExperimentalCoroutinesApi::class)
class ObjectMenuActionsTest {
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
        mediaSink = { _, ext -> "media/new.$ext" },
        prefs = FakePrefs(),
        clipboard = EditorClipboard(),
        clock = { testScheduler.currentTime },
    )

    private fun object_(id: Long, f: FloatingObject) = ObjectElement(ElementId(id), f.toJson(), box(0f, 0f, 1f, 1f))

    /** A 400 x 200 image shown at 200 x 100 in the middle of the page. */
    private val image = FloatingObject(ImageObject("img", "media/a.png", 400f, 200f), RectPu(100f, 100f, 300f, 200f))

    private fun EditorSession.floatingOf(id: Long): FloatingObject =
        (content("p1")!!.elements.single { it.id == ElementId(id) } as ObjectElement).floating()!!

    @Test fun resizeToDefaultIsOneUndoStep() = runTest {
        storage.pageFiles["p1"] = PageContent("p1", listOf(object_(1, image)), 0)
        val s = session()
        runCurrent()
        s.select("p1", listOf(s.content("p1")!!.elements.single()))

        s.resizeSelectionToDefault()
        // Default size 400 x 200 around the same centre (200, 150).
        assertThat(s.floatingOf(1).rect).isEqualTo(RectPu(0f, 50f, 400f, 250f))

        assertThat(s.undo()).isTrue()
        assertThat(s.floatingOf(1).rect).isEqualTo(image.rect)
        assertThat(s.undo()).isFalse()  // nothing else from the session: the select did not record

        assertThat(s.redo()).isTrue()
        assertThat(s.floatingOf(1).rect).isEqualTo(RectPu(0f, 50f, 400f, 250f))
    }

    @Test fun fitToPageWidthIsOneUndoStepAndKeepsTheTop() = runTest {
        storage.pageFiles["p1"] = PageContent("p1", listOf(object_(1, image)), 0)
        val s = session()
        runCurrent()
        s.select("p1", listOf(s.content("p1")!!.elements.single()))

        s.fitSelectionToPageWidth(1080f)
        val fit = s.floatingOf(1)
        assertThat(fit.rect.left).isEqualTo(0f)
        assertThat(fit.rect.width).isEqualTo(1080f)
        assertThat(fit.rect.height).isEqualTo(540f)
        assertThat(fit.rect.top).isEqualTo(100f)
        assertThat(fit.isFittedToWidth(1080f)).isTrue()

        assertThat(s.undo()).isTrue()
        assertThat(s.floatingOf(1).rect).isEqualTo(image.rect)
    }

    @Test fun wrapAppliesToImagesAndTextBoxesOnly() = runTest {
        val voice = FloatingObject(VoiceObject("v", "media/v.m4a", 1L), RectPu(0f, 300f, 480f, 420f))
        storage.pageFiles["p1"] = PageContent("p1", listOf(object_(1, image), object_(2, voice)), 0)
        val s = session()
        runCurrent()
        s.select("p1", s.content("p1")!!.elements)

        s.setSelectionWrap(TextWrap.Behind)
        assertThat(s.floatingOf(1).wrap).isEqualTo(TextWrap.Behind)
        assertThat(s.floatingOf(2).wrap).isEqualTo(TextWrap.InFront)

        assertThat(s.undo()).isTrue()
        assertThat(s.floatingOf(1).wrap).isEqualTo(TextWrap.InFront)
        assertThat(s.undo()).isFalse()
    }

    private fun TestScope.selectedSession(vararg elements: PageElement): EditorSession {
        storage.pageFiles["p1"] = PageContent("p1", elements.toList(), 0)
        val s = session()
        runCurrent()
        s.select("p1", s.content("p1")!!.elements)
        return s
    }

    @Test fun resizeToDefaultOnlyForAFittedSingleImage() = runTest {
        val fitted = FloatingObject(ImageObject("img", "media/a.png", 400f, 200f), RectPu(0f, 100f, 1080f, 640f))
        assertThat(selectedSession(object_(1, fitted)).objectMenuState(1080f)).isEqualTo(
            ObjectMenuState(
                isObjects = true, showFitWidth = false, showResize = true, showWrap = true, behind = false,
                showCrop = true, showShare = true, showSaveImage = true, showLassoCrop = true, showEditImage = true, showAnchor = true,
            ),
        )
        assertThat(selectedSession(object_(1, image)).objectMenuState(1080f)).isEqualTo(
            ObjectMenuState(
                isObjects = true, showFitWidth = true, showResize = false, showWrap = true, behind = false,
                showCrop = true, showShare = true, showSaveImage = true, showLassoCrop = true, showEditImage = true, showAnchor = true,
            ),
        )
    }

    @Test fun anyOtherItemInTheSelectionHidesFitAndResizeLikeReference() = runTest {
        // the reference counts every selected item (ink too) for its single-image test.
        val fitted = FloatingObject(ImageObject("img", "media/a.png", 400f, 200f), RectPu(0f, 100f, 1080f, 640f))
        val s = selectedSession(object_(1, fitted), fakeElement(9))
        val state = s.objectMenuState(1080f)
        assertThat(state.showFitWidth).isFalse()
        assertThat(state.showResize).isFalse()
    }

    @Test fun textBoxHasWrapButNoFitOrResize() = runTest {
        val box = FloatingObject(TextBoxObject("t", RichText.of("x")), RectPu(0f, 0f, 540f, 200f), wrap = TextWrap.Behind)
        assertThat(selectedSession(object_(1, box)).objectMenuState(1080f)).isEqualTo(
            ObjectMenuState(isObjects = true, showFitWidth = false, showResize = false, showWrap = true, behind = true, showShare = true),
        )
    }

    @Test fun moreMenuFlagsFollowTheReferenceRules() = runTest {
        val gif = FloatingObject(ImageObject("g", "media/a.gif", 100f, 100f), RectPu(0f, 0f, 100f, 100f))
        assertThat(selectedSession(object_(1, gif)).objectMenuState(1080f).showCrop).isFalse()
        // Objects mixed with ink: Change style yes; Share / Save image no (strokes can't be shared as files).
        val mixed = selectedSession(object_(1, image), fakeElement(9)).objectMenuState(1080f)
        assertThat(mixed.showChangeStyle).isTrue()
        assertThat(mixed.showShare).isFalse()
        assertThat(mixed.showSaveImage).isFalse()
        assertThat(mixed.showCrop).isFalse()
        // An empty text box has nothing to share.
        val empty = FloatingObject(TextBoxObject("t", RichText.of("  ")), RectPu(0f, 0f, 540f, 200f))
        assertThat(selectedSession(object_(1, empty)).objectMenuState(1080f).showShare).isFalse()
    }

    @Test fun voiceHasNoWrapAndNoStrokeOnlyMenu() = runTest {
        val voice = FloatingObject(VoiceObject("v", "media/v.m4a", 1L), RectPu(0f, 300f, 480f, 420f))
        assertThat(selectedSession(object_(1, voice)).objectMenuState(1080f).showWrap).isFalse()
        // A selection of ink only (no readable object) keeps the stroke menu.
        assertThat(selectedSession(fakeElement(5)).objectMenuState(1080f).isObjects).isFalse()
    }
}
