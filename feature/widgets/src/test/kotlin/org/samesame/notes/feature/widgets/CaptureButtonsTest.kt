package org.samesame.notes.feature.widgets

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureButtonsTest {
    @Test fun `defaults are new note with checklist, voice, handwriting and camera`() {
        val d = CaptureButtons()
        assertThat(d.centre).isEqualTo(CaptureButton.NewNote)
        assertThat(d.corners).containsExactly(
            CaptureButton.Checklist, CaptureButton.Dictate, CaptureButton.Handwriting, CaptureButton.Camera,
        ).inOrder()
        assertThat(d.shown).isEqualTo(d.corners)
    }

    @Test fun `round trip`() {
        val b = CaptureButtons(CaptureButton.Camera, listOf(CaptureButton.None, CaptureButton.Markdown, CaptureButton.Picture, CaptureButton.OpenApp))
        assertThat(CaptureButtons.decode(b.encode())).isEqualTo(b)
        assertThat(b.encode()).isEqualTo("camera,none,markdown,image,app")
        assertThat(b.shown).containsExactly(CaptureButton.Markdown, CaptureButton.Picture, CaptureButton.OpenApp).inOrder()
        CaptureButton.entries.forEach { assertThat(CaptureButton.fromKey(it.key)).isEqualTo(it) }
    }

    @Test fun `nothing saved means defaults`() {
        assertThat(CaptureButtons.decode(null)).isEqualTo(CaptureButtons())
        assertThat(CaptureButtons.decode("")).isEqualTo(CaptureButtons())
        assertThat(CaptureButtons.decode("  ")).isEqualTo(CaptureButtons())
    }

    @Test fun `missing or unknown keys fall back per slot`() {
        // Only the centre and one corner saved.
        assertThat(CaptureButtons.decode("brush,none")).isEqualTo(
            CaptureButtons(CaptureButton.Drawing, listOf(CaptureButton.None, CaptureButton.Dictate, CaptureButton.Handwriting, CaptureButton.Camera)),
        )
        // A newer version's action, an empty centre and extra keys.
        assertThat(CaptureButtons.decode("none,scan,image,,camera,extra")).isEqualTo(
            CaptureButtons(CaptureButton.NewNote, listOf(CaptureButton.Checklist, CaptureButton.Picture, CaptureButton.Handwriting, CaptureButton.Camera)),
        )
        assertThat(CaptureButtons.decode("garbage")).isEqualTo(CaptureButtons())
    }

    @Test fun `slot edits`() {
        val b = CaptureButtons().withCorner(CaptureButtons.TOP_RIGHT, CaptureButton.None).withCentre(CaptureButton.Checklist)
        assertThat(b.centre).isEqualTo(CaptureButton.Checklist)
        assertThat(b.corners[CaptureButtons.TOP_RIGHT]).isEqualTo(CaptureButton.None)
        // The centre is never emptied.
        assertThat(b.withCentre(CaptureButton.None)).isEqualTo(b)
        assertThat(CaptureButton.centreChoices).doesNotContain(CaptureButton.None)
        assertThat(CaptureButton.cornerChoices.first()).isEqualTo(CaptureButton.None)
    }

    @Test fun `camera and markdown start actions`() {
        assertThat(StartAction.fromKey("camera")).isEqualTo(StartAction.Camera)
        assertThat(StartAction.fromKey("markdown")).isEqualTo(StartAction.Markdown)
        assertThat(StartAction.fromKey("image")).isEqualTo(StartAction.Image)
        assertThat(StartAction.fromKey("nope")).isNull()
    }
}
