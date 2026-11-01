package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.PageMode
import java.io.File

/** Streamed media (voice recordings): same content-addressed path as the byte variant, readable as a file, survives pack and reopen. */
class MediaFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))

    @Test fun writeMediaFileMatchesWriteMediaAndIsPlayableFromAFile() = runBlocking<Unit> {
        val s = store()
        s.create("n1", PageMode.Pages)
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val src = File(tmp.root, "rec.m4a").apply { writeBytes(bytes) }

        val path = s.writeMediaFile("n1", src, "m4a")
        assertThat(path).startsWith("media/")
        assertThat(path).endsWith(".m4a")
        assertThat(s.writeMedia("n1", bytes, "m4a")).isEqualTo(path)
        assertThat(s.writeMediaFile("n1", src, "m4a")).isEqualTo(path) // same bytes reuse one file
        assertThat(s.mediaFile("n1", path)!!.readBytes()).isEqualTo(bytes)
        assertThat(s.readMedia("n1", path)).isEqualTo(bytes)
    }

    @Test fun mediaFileRefusesUnsafeAndMissingPaths() = runBlocking<Unit> {
        val s = store()
        s.create("n1", PageMode.Pages)
        assertThat(s.mediaFile("n1", "media/../document.json")).isNull()
        assertThat(s.mediaFile("n1", "document.json")).isNull()
        assertThat(s.mediaFile("n1", "media/nope.m4a")).isNull()
    }

    @Test fun recordingSurvivesPackAndReopen() = runBlocking<Unit> {
        val s = store()
        s.create("n1", PageMode.Pages)
        val bytes = ByteArray(5_000) { it.toByte() }
        val path = s.writeMediaFile("n1", File(tmp.root, "r.m4a").apply { writeBytes(bytes) }, "m4a")
        s.flush("n1", close = true)
        assertThat(s.readMedia("n1", path)).isEqualTo(bytes)
    }
}
