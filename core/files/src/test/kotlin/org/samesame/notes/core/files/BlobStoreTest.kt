package org.samesame.notes.core.files

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.files.LibraryFileTooLargeException
import org.samesame.notes.core.model.files.NotEnoughSpaceException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class BlobStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(free: Long = Long.MAX_VALUE / 2, max: Long = 1L shl 30, reserve: Long = 0) =
        BlobStore(tmp.root, freeSpace = { free }, maxBytes = max, reserveBytes = reserve)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A source that fails after [failAfter] bytes, like a share stream whose sender died (or our process being killed). */
    private class FailingStream(private val total: Int, private val failAfter: Int) : InputStream() {
        var pos = 0
        override fun read(): Int {
            if (pos >= failAfter) throw IOException("source died")
            if (pos >= total) return -1
            pos++
            return 7
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= failAfter) throw IOException("source died")
            if (pos >= total) return -1
            val n = minOf(len, failAfter - pos, total - pos)
            java.util.Arrays.fill(b, off, off + n, 7)
            pos += n
            return n
        }
    }

    @Test fun `stage and commit store the content under its hash`() = runBlocking {
        val s = store()
        val bytes = "hello library".toByteArray()
        val staged = s.stage(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertThat(staged.sha256).isEqualTo(sha(bytes))
        assertThat(staged.sizeBytes).isEqualTo(bytes.size.toLong())
        assertThat(String(staged.head)).isEqualTo("hello library")
        val blob = s.commit(staged)
        assertThat(blob.name).isEqualTo(sha(bytes))
        assertThat(blob.readBytes()).isEqualTo(bytes)
        assertThat(s.tmpDir.list()).isEmpty()
    }

    @Test fun `same content twice keeps one blob`() = runBlocking {
        val s = store()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        s.commit(s.stage(ByteArrayInputStream(bytes)))
        s.commit(s.stage(ByteArrayInputStream(bytes)))
        assertThat(s.list()).containsExactly(sha(bytes))
        assertThat(s.tmpDir.list()).isEmpty()
    }

    @Test fun `a source failing mid-copy leaves no temp file and no blob`() = runBlocking {
        val s = store()
        assertThrows(IOException::class.java) {
            runBlocking { s.stage(FailingStream(total = 1_000_000, failAfter = 300_000)) }
        }
        assertThat(s.tmpDir.list()).isEmpty()
        assertThat(s.list()).isEmpty()
    }

    @Test fun `cancellation mid-copy leaves nothing behind`() = runBlocking {
        val s = store()
        val job = Job()
        var copied = 0L
        val result = runCatching {
            withContext(job) {
                s.stage(FailingStream(total = 5_000_000, failAfter = Int.MAX_VALUE), onProgress = { c, _ ->
                    copied = c
                    if (c > 1_000_000) job.cancel()
                })
            }
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(copied).isLessThan(5_000_000)
        assertThat(s.tmpDir.list()).isEmpty()
    }

    @Test fun `process killed mid-copy - the leftover temp file is swept at the next start, stored blobs are kept`() = runBlocking {
        val s = store()
        val kept = "keep me".toByteArray()
        s.commit(s.stage(ByteArrayInputStream(kept)))
        // What a kill during the copy leaves: a partial temp file and no row; and a kill between rename and row: a blob.
        s.tmpDir.mkdirs()
        java.io.File(s.tmpDir, "partial-copy").writeBytes(ByteArray(4096))
        val orphan = "orphan".toByteArray()
        s.commit(s.stage(ByteArrayInputStream(orphan)))

        val next = store() // the next process
        assertThat(next.sweepTmp()).isEqualTo(1)
        assertThat(next.sweepOrphans(referenced = setOf(sha(kept)))).isEqualTo(1)
        assertThat(next.list()).containsExactly(sha(kept))
        assertThat(next.file(sha(kept)).readBytes()).isEqualTo(kept)
    }

    @Test fun `the sweep leaves copies in progress alone`() = runBlocking {
        val s = store()
        var swept = -1
        val staged = s.stage(ByteArrayInputStream(ByteArray(10)), onProgress = { _, _ -> swept = s.sweepTmp() })
        assertThat(swept).isEqualTo(0)
        assertThat(s.sweepTmp()).isEqualTo(0) // staged, not committed yet: still protected
        s.commit(staged)
        assertThat(s.list()).hasSize(1)
    }

    @Test fun `files over the limit are refused, declared or not`() = runBlocking {
        val s = store(max = 1000)
        assertThrows(LibraryFileTooLargeException::class.java) {
            runBlocking { s.stage(ByteArrayInputStream(ByteArray(10)), expectedSize = 5000) }
        }
        assertThrows(LibraryFileTooLargeException::class.java) {
            runBlocking { s.stage(ByteArrayInputStream(ByteArray(1001))) }
        }
        assertThat(s.stage(ByteArrayInputStream(ByteArray(1000))).sizeBytes).isEqualTo(1000)
        assertThat(s.tmpDir.list()!!.size).isEqualTo(1)
    }

    @Test fun `imports stop at the free-space reserve`() = runBlocking {
        val s = store(free = 300, reserve = 200)
        assertThrows(NotEnoughSpaceException::class.java) {
            runBlocking { s.stage(ByteArrayInputStream(ByteArray(150)), expectedSize = 150) }
        }
        assertThat(s.tmpDir.list()).isEmpty()
        val low = store(free = 100, reserve = 200)
        assertThrows(NotEnoughSpaceException::class.java) {
            runBlocking { low.stage(FailingStream(total = 20_000_000, failAfter = Int.MAX_VALUE)) }
        }
        assertThat(low.tmpDir.list()).isEmpty()
    }

    @Test fun `blob names are hashes only`() {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) { s.file("../../databases/samesame.db") }
        assertThat(s.exists("../x")).isFalse()
        assertThat(s.delete("../x")).isFalse()
    }
}
