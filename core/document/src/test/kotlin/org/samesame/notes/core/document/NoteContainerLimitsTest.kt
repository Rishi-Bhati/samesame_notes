package org.samesame.notes.core.document

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.PageMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Zip bombs and oversized entries in a `.ssnote` are refused, and a failed unpack leaves nothing behind (review M2). */
class NoteContainerLimitsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun exported(): ByteArray = runBlocking {
        val store = NoteDocumentStore(tmp.newFolder(), tmp.newFolder())
        store.create("src", PageMode.Pages, "T")
        ByteArrayOutputStream().also { store.export("src", it) }.toByteArray()
    }

    /** The exported container plus [extra] entries (name to uncompressed size of zeros), or a replaced manifest. */
    private fun tampered(extra: Map<String, Long> = emptyMap(), manifest: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(exported().inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val bytes = if (e.name == "manifest.json" && manifest != null) manifest else zin.readBytes()
                    zos.putNextEntry(ZipEntry(e.name)); zos.write(bytes); zos.closeEntry()
                }
            }
            val zeros = ByteArray(1 shl 20)
            for ((name, size) in extra) {
                zos.putNextEntry(ZipEntry(name))
                var left = size
                while (left > 0) { val n = minOf(left, zeros.size.toLong()).toInt(); zos.write(zeros, 0, n); left -= n }
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val bombSize = Limits.MAX_ARCHIVE_INFLATE_SLACK_BYTES + 32L * 1024 * 1024

    @Test fun `import refuses a zip bomb ink entry`() {
        val root = tmp.newFolder("notes")
        val store = NoteDocumentStore(root, tmp.newFolder("working"))
        val bomb = tampered(mapOf("ink/p.ink" to bombSize))
        assertThat(bomb.size).isLessThan(1024 * 1024)
        assertThrows(IOException::class.java) { runBlocking { store.import(bomb.inputStream()) } }
        assertThat(root.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `import refuses an oversized manifest`() {
        val store = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        val manifest = ByteArray(Limits.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }
        assertThrows(IOException::class.java) { runBlocking { store.import(tampered(manifest = manifest).inputStream()) } }
    }

    @Test fun `failed unpack of a stored bomb removes its staging directory`() = runBlocking<Unit> {
        val root = tmp.newFolder("notes")
        val working = tmp.newFolder("working")
        File(root, "n1.ssnote").writeBytes(tampered(mapOf("media/a.png" to bombSize)))
        val store = NoteDocumentStore(root, working)
        assertThrows(IOException::class.java) { runBlocking { store.readDocument("n1") } }
        assertThat(working.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `ordinary notes still import and open`() = runBlocking<Unit> {
        val store = NoteDocumentStore(tmp.newFolder("notes"), tmp.newFolder("working"))
        val doc = store.import(tampered(mapOf("media/b.png" to 1024L * 1024)).inputStream())
        assertThat(store.readDocument(doc.id).title).isEqualTo("T")
    }
}
