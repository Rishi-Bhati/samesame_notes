package org.samesame.notes.core.document

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.samesame.notes.core.model.Limits
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** One saved copy of a note: [id] is the time it was taken (epoch ms). [title] and [preview] are empty for locked notes. */
data class NoteVersion(
    val noteId: String,
    val id: Long,
    val sizeBytes: Long,
    val title: String,
    val preview: String,
    val pages: Int,
    val locked: Boolean,
    /** Size + CRC32 of the container bytes: an identical copy is skipped. */
    val crc32: Long,
)

/** What the list shows for a version; the store fills it from the working copy (never for a locked note). */
class VersionMeta(val title: String = "", val preview: String = "", val pages: Int = 0, val locked: Boolean = false)

/**
 * Version history on disk: `<root>/<noteId>/<epochMs>.ssnote` (a byte copy of the packed container) plus a small
 * `<epochMs>.json` sidecar for the list. Only file primitives live here; when to take a version is the caller's call.
 * Every method is synchronized: a prune or a delete never runs in the middle of a snapshot.
 *
 * The root sits outside `notes/`, so Auto Backup (which includes only `notes/`, the database and library blobs) never
 * takes versions.
 */
class NoteVersionStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Copies [from] to the new file [to]; replaceable so tests can make a snapshot fail. */
    private val copier: (from: File, to: File) -> Unit = { from, to ->
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    },
) {
    fun now(): Long = clock()

    @Synchronized
    fun list(noteId: String): List<NoteVersion> {
        val dir = dirOf(noteId)
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.name.endsWith(CONTAINER_SUFFIX) }
            .mapNotNull { f -> f.name.removeSuffix(CONTAINER_SUFFIX).toLongOrNull()?.let { id -> read(noteId, id, f) } }
            .sortedByDescending { it.id }
            .toList()
    }

    @Synchronized
    fun newest(noteId: String): NoteVersion? = list(noteId).firstOrNull()

    /** The container copy of version [id], or null when it does not exist. */
    @Synchronized
    fun file(noteId: String, id: Long): File? = File(dirOf(noteId), "$id$CONTAINER_SUFFIX").takeIf { it.isFile }

    /**
     * Copies [container] as a new version when [force] is set (close, restore) or the newest version is older than the
     * minimum interval, unless it is byte-identical to the newest one. Returns the new version, or null when nothing was taken. A failed
     * copy throws and leaves no partial file.
     */
    @Synchronized
    fun snapshot(noteId: String, container: File, meta: () -> VersionMeta, force: Boolean): NoteVersion? {
        val now = clock()
        val newest = list(noteId).firstOrNull()
        if (!force && newest != null && now - newest.id < Limits.VERSION_MIN_INTERVAL_MS) return null
        val size = container.length()
        val crc = crcOf(container)
        if (newest != null && newest.sizeBytes == size && newest.crc32 == crc) return null
        val dir = dirOf(noteId)
        val info = meta()
        dir.mkdirs()
        var id = now
        while (File(dir, "$id$CONTAINER_SUFFIX").exists()) id++
        val target = File(dir, "$id$CONTAINER_SUFFIX")
        val tmp = File(dir, "$id$CONTAINER_SUFFIX$TMP_SUFFIX")
        val sidecar = File(dir, "$id$SIDECAR_SUFFIX")
        try {
            copier(container, tmp)
            if (tmp.length() != size) throw IOException("Version copy has the wrong size")
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            writeFileAtomically(sidecar) { out ->
                out.write(
                    buildJsonObject {
                        put("title", info.title)
                        put("preview", info.preview.take(PREVIEW_CHARS))
                        put("size", size)
                        put("crc", crc)
                        put("pages", info.pages)
                        put("locked", info.locked)
                    }.toString().encodeToByteArray(),
                )
            }
        } catch (e: Throwable) {
            tmp.delete()
            target.delete()
            sidecar.delete()
            throw e
        }
        prune(noteId)
        pruneTotal()
        return NoteVersion(noteId, id, size, info.title, info.preview.take(PREVIEW_CHARS), info.pages, info.locked, crc)
    }

    /** Count, age and leftover-file rules for one note. The newest [Limits.VERSIONS_ALWAYS_KEPT] survive any age. */
    @Synchronized
    fun prune(noteId: String) {
        val now = clock()
        val cutoff = now - TimeUnit.DAYS.toMillis(Limits.VERSION_RETENTION_DAYS.toLong())
        val all = list(noteId) // newest first
        all.forEachIndexed { index, v ->
            val tooMany = index >= Limits.MAX_VERSIONS_PER_NOTE
            val tooOld = index >= Limits.VERSIONS_ALWAYS_KEPT && v.id < cutoff
            if (tooMany || tooOld) remove(noteId, v.id)
        }
        dirOf(noteId).listFiles()?.forEach { if (it.name.endsWith(TMP_SUFFIX)) it.delete() }
    }

    /** Beyond [Limits.MAX_VERSION_BYTES_TOTAL] the oldest versions of all notes go first; each note keeps its newest one. */
    @Synchronized
    fun pruneTotal(maxBytes: Long = Limits.MAX_VERSION_BYTES_TOTAL) {
        val noteDirs = root.listFiles { f -> f.isDirectory } ?: return
        val versionsByNote = noteDirs.map { d -> list(d.name) }
        val all = versionsByNote.flatMap { it.drop(1) }.sortedBy { it.id }
        var total = versionsByNote.sumOf { list -> list.sumOf { it.sizeBytes } }
        for (v in all) {
            if (total <= maxBytes) break
            remove(v.noteId, v.id)
            total -= v.sizeBytes
        }
    }

    /** Removes every version of the note (permanent delete, lock, unlock). */
    @Synchronized
    fun deleteAll(noteId: String) {
        dirOf(noteId).deleteRecursively()
    }

    @Synchronized
    fun delete(noteId: String, id: Long) = remove(noteId, id)

    private fun remove(noteId: String, id: Long) {
        File(dirOf(noteId), "$id$CONTAINER_SUFFIX").delete()
        File(dirOf(noteId), "$id$SIDECAR_SUFFIX").delete()
    }

    private fun read(noteId: String, id: Long, container: File): NoteVersion {
        val side = File(dirOf(noteId), "$id$SIDECAR_SUFFIX")
        val obj = runCatching { Json.parseToJsonElement(side.readText()) as JsonObject }.getOrNull()
        fun str(k: String) = (obj?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        return NoteVersion(
            noteId = noteId,
            id = id,
            sizeBytes = container.length(),
            title = str("title"),
            preview = str("preview"),
            pages = runCatching { obj?.get("pages")?.jsonPrimitive?.int }.getOrNull() ?: 0,
            locked = obj?.get("locked")?.jsonPrimitive?.content == "true",
            crc32 = runCatching { obj?.get("crc")?.jsonPrimitive?.long }.getOrNull() ?: -1L,
        )
    }

    private fun crcOf(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    private fun dirOf(noteId: String): File {
        require(noteId.isNotEmpty() && noteId != "." && noteId != ".." && noteId.none { it == '/' || it == '\\' || it == '\u0000' }) { "Bad note id" }
        return File(root, noteId)
    }

    private companion object {
        const val CONTAINER_SUFFIX = ".$NOTE_CONTAINER_EXTENSION"
        const val SIDECAR_SUFFIX = ".json"
        const val TMP_SUFFIX = ".tmp"
        const val PREVIEW_CHARS = 200
    }
}
