package org.samesame.notes.feature.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReceiveRoutingTest {
    @Test fun kinds() {
        assertThat(ReceiveRouting.kindOf("n.ssnote", "application/octet-stream")).isEqualTo(ReceiveKind.NoteFile)
        assertThat(ReceiveRouting.kindOf("n.sdocx", null)).isEqualTo(ReceiveKind.NoteFile)
        assertThat(ReceiveRouting.kindOf("a.PDF", null)).isEqualTo(ReceiveKind.Pdf)
        assertThat(ReceiveRouting.kindOf("x", "application/pdf")).isEqualTo(ReceiveKind.Pdf)
        assertThat(ReceiveRouting.kindOf("a.jpeg", "application/octet-stream")).isEqualTo(ReceiveKind.Image)
        assertThat(ReceiveRouting.kindOf("a.mp4", "video/mp4")).isEqualTo(ReceiveKind.Other)
        assertThat(ReceiveRouting.kindOf("a.apk", null)).isEqualTo(ReceiveKind.Other)
    }

    @Test fun defaults() {
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.NoteFile, ReceiveKind.NoteFile))).isTrue()
        assertThat(ReceiveRouting.defaultToNewNote(listOf(ReceiveKind.Pdf))).isFalse()
        assertThat(ReceiveRouting.defaultToNewNote(emptyList())).isFalse()
        assertThat(ReceiveRouting.canBecomeNote(ReceiveKind.Image)).isFalse()
    }
}
