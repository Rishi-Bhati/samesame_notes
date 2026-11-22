package org.samesame.notes.feature.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.samesame.notes.core.model.saveas.SaveSourceKind

class ReceiveRoutingTest {
    @Test fun kinds() {
        assertThat(ReceiveRouting.kindOf("n.ssnote", "application/octet-stream")).isEqualTo(ReceiveKind.NoteFile)
        assertThat(ReceiveRouting.kindOf("n.sdocx", null)).isEqualTo(ReceiveKind.NoteFile)
        assertThat(ReceiveRouting.kindOf("a.PDF", null)).isEqualTo(ReceiveKind.Pdf)
        assertThat(ReceiveRouting.kindOf("x", "application/pdf")).isEqualTo(ReceiveKind.Pdf)
        assertThat(ReceiveRouting.kindOf("a.jpeg", "application/octet-stream")).isEqualTo(ReceiveKind.Image)
        assertThat(ReceiveRouting.kindOf("a.TXT", null)).isEqualTo(ReceiveKind.Text)
        assertThat(ReceiveRouting.kindOf("a.md", "application/octet-stream")).isEqualTo(ReceiveKind.Text)
        assertThat(ReceiveRouting.kindOf("notes", "text/csv")).isEqualTo(ReceiveKind.Text)
        assertThat(ReceiveRouting.kindOf("a.mp4", "video/mp4")).isEqualTo(ReceiveKind.Other)
        assertThat(ReceiveRouting.kindOf("a.apk", null)).isEqualTo(ReceiveKind.Other)
    }

    @Test fun defaults() {
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.NoteFile, ReceiveKind.NoteFile))).isTrue()
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.Text, ReceiveKind.NoteFile))).isTrue()
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.Text, ReceiveKind.Other))).isFalse()
        assertThat(ReceiveRouting.canBecomeNote(ReceiveKind.Text)).isTrue()
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.Pdf))).isFalse()
        assertThat(ReceiveRouting.defaultToNewNote(emptyList())).isFalse()
        assertThat(ReceiveRouting.canBecomeNote(ReceiveKind.Image)).isTrue()
        assertThat(ReceiveRouting.canBecomeNote(ReceiveKind.Audio)).isTrue()
        assertThat(ReceiveRouting.canBecomeNote(ReceiveKind.Other)).isFalse()
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.Image, ReceiveKind.Image, ReceiveKind.Audio))).isTrue()
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.Image, ReceiveKind.Other))).isFalse()
    }

    @Test fun audioKinds() {
        assertThat(ReceiveRouting.kindOf("a.MP3", null)).isEqualTo(ReceiveKind.Audio)
        assertThat(ReceiveRouting.kindOf("rec", "audio/mp4")).isEqualTo(ReceiveKind.Audio)
        assertThat(ReceiveRouting.kindOf("clip.3gp", "video/3gpp")).isEqualTo(ReceiveKind.Other)
        assertThat(ReceiveRouting.kindOf("a.jpg", "image/jpeg")).isEqualTo(ReceiveKind.Image)
    }

    @Test fun extensions() {
        assertThat(ReceiveRouting.imageExtension("a.JPEG", null)).isEqualTo("jpg")
        assertThat(ReceiveRouting.imageExtension("noext", "image/heic")).isEqualTo("heic")
        assertThat(ReceiveRouting.audioExtension("x.opus", null)).isEqualTo("opus")
        assertThat(ReceiveRouting.audioExtension("x", "audio/mpeg")).isEqualTo("mp3")
        assertThat(ReceiveRouting.audioExtension("x", null)).isEqualTo("m4a")
    }

    @Test fun saveKinds() {
        assertThat(ReceiveRouting.saveKindOf(ReceiveKind.Text, "a.md", "application/octet-stream")).isEqualTo(SaveSourceKind.Markdown)
        assertThat(ReceiveRouting.saveKindOf(ReceiveKind.Text, "notes", "text/markdown")).isEqualTo(SaveSourceKind.Markdown)
        assertThat(ReceiveRouting.saveKindOf(ReceiveKind.Text, "a.txt", "text/markdown")).isEqualTo(SaveSourceKind.Text)
        assertThat(ReceiveRouting.saveKindOf(ReceiveKind.Image, "a.png", null)).isEqualTo(SaveSourceKind.Image)
        assertThat(ReceiveRouting.saveKindOf(ReceiveKind.Other, "a.apk", null)).isEqualTo(SaveSourceKind.Other)
        assertThat(ReceiveRouting.saveKindOf(ReceiveKind.NoteFile, "a.sdocx", null)).isEqualTo(SaveSourceKind.NoteFile)
    }
}
