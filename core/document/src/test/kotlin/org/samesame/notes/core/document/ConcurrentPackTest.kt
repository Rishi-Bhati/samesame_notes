package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.PageMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

class ConcurrentPackTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun twoConcurrentPacksOfTheSameNoteLeaveAValidContainerWithDistinctTempNames() = runBlocking {
        val notesRoot = tmp.newFolder("notes")
        val workingRoot = tmp.newFolder("working")
        val store = NoteDocumentStore(notesRoot, workingRoot)

        val noteId = "note-concurrent"
        val doc = store.create(noteId, PageMode.Pages, title = "Initial Title")
        store.flush(noteId, close = false)

        store.writeDocument(doc.copy(title = "Edited Title"))

        val firstPackWaiting = CountDownLatch(1)
        val secondPackWaiting = CountDownLatch(1)
        val allowRename = CountDownLatch(1)
        var packCount = 0

        store.beforePackRename = {
            val count = synchronized(store) { ++packCount }
            if (count == 1) {
                firstPackWaiting.countDown()
                allowRename.await(5, TimeUnit.SECONDS)
            } else if (count == 2) {
                secondPackWaiting.countDown()
                allowRename.await(5, TimeUnit.SECONDS)
            }
        }

        val firstPack = async(Dispatchers.IO) {
            store.flush(noteId, close = false)
        }

        assertThat(firstPackWaiting.await(5, TimeUnit.SECONDS)).isTrue()

        // Trigger second pack concurrently while first pack is paused before rename
        val secondPack = async(Dispatchers.IO) {
            store.flush(noteId, close = false)
        }

        assertThat(secondPackWaiting.await(5, TimeUnit.SECONDS)).isTrue()

        // Both packs have written their temp files and are waiting before rename
        val tmps = notesRoot.listFiles()?.filter {
            it.name.startsWith("$noteId.ssnote") && it.name.endsWith(".tmp")
        }?.map { it.name } ?: emptyList()

        allowRename.countDown()

        firstPack.await()
        secondPack.await()

        store.beforePackRename = null

        // Both temp names were distinct and existed simultaneously
        assertThat(tmps).hasSize(2)
        assertThat(tmps[0]).isNotEqualTo(tmps[1])

        // Resulting container is a valid zip file and readable document
        val container = File(notesRoot, "$noteId.ssnote")
        assertThat(container.exists()).isTrue()
        ZipFile(container).use { zf ->
            assertThat(zf.getEntry("manifest.json")).isNotNull()
            assertThat(zf.getEntry("document.json")).isNotNull()
        }

        val restoredDoc = store.readDocument(noteId)
        assertThat(restoredDoc.title).isEqualTo("Edited Title")

        // No stale temp files remain
        val remainingTmps = notesRoot.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertThat(remainingTmps).isEmpty()
    }
}
