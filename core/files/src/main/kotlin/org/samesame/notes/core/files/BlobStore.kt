package org.samesame.notes.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.LibraryFileTooLargeException
import org.samesame.notes.core.model.files.NotEnoughSpaceException
import org.samesame.notes.core.model.io.InputTooLargeException
import org.samesame.notes.core.model.io.LimitedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Free bytes on the volume holding a directory (a seam for tests). */
fun interface FreeSpace {
    fun availableBytes(dir: File): Long

    companion object {
        /** `statvfs` free blocks available to the app, the same figure as `StatFs.availableBytes`. */
        val Default = FreeSpace { it.usableSpace }
    }
}

/**
 * Content-addressed storage of library files: `<root>/blobs/<sha256>` (no name, no extension, so a display name can
 * never become a path) and `<root>/tmp/<uuid>` while a copy is in flight.
 *
 * An import is [stage] (stream into tmp, hashing and enforcing the size limit and free-space reserve, then fsync)
 * followed by [commit] (atomic rename to `blobs/<sha>`, or dropping the temp copy when that content is already stored).
 * The caller writes the database row after [commit]. A process killed at any point leaves either a temp file or an
 * unreferenced blob, which [sweepTmp] and [sweepOrphans] remove at the next start; a referenced blob is never touched.
 */
class BlobStore(
    val root: File,
    private val freeSpace: FreeSpace = FreeSpace.Default,
    private val maxBytes: Long = Limits.MAX_LIBRARY_FILE_BYTES,
    private val reserveBytes: Long = Limits.LIBRARY_FREE_SPACE_RESERVE_BYTES,
) {
    val blobsDir = File(root, "blobs")
    val tmpDir = File(root, "tmp")

    /** Temp files of copies in progress in this process; [sweepTmp] leaves them alone. */
    private val active: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** A fully written, fsynced temp copy, not yet in the store. [head] is the first bytes, for MIME sniffing. */
    class Staged internal constructor(
        internal val tmp: File,
        val sha256: String,
        val sizeBytes: Long,
        val head: ByteArray,
    )

    /**
     * Copies [input] (closed afterwards) into a temp file. [expectedSize] (if the source declares one) is checked
     * up front against the limit and the free space. Fails with [LibraryFileTooLargeException],
     * [NotEnoughSpaceException], an [IOException] from the source, or cancellation; the temp file is then deleted.
     */
    suspend fun stage(
        input: InputStream,
        expectedSize: Long? = null,
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    ): Staged {
        if (expectedSize != null && expectedSize > maxBytes) {
            input.close()
            throw LibraryFileTooLargeException("File is larger than $maxBytes bytes")
        }
        tmpDir.mkdirs()
        if (expectedSize != null) checkSpace(expectedSize)
        val tmp = File(tmpDir, UUID.randomUUID().toString())
        active += tmp.name
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val head = java.io.ByteArrayOutputStream(HEAD_BYTES)
            var copied = 0L
            var sinceSpaceCheck = 0L
            LimitedInputStream(input, maxBytes, "File").use { src ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buf, 0, n)
                        if (head.size() < HEAD_BYTES) head.write(buf, 0, minOf(n, HEAD_BYTES - head.size()))
                        out.write(buf, 0, n)
                        copied += n
                        sinceSpaceCheck += n
                        if (sinceSpaceCheck >= SPACE_CHECK_BYTES) {
                            sinceSpaceCheck = 0
                            checkSpace(0)
                        }
                        onProgress(copied, expectedSize)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            return Staged(tmp, digest.digest().toHex(), copied, head.toByteArray())
        } catch (e: InputTooLargeException) {
            discardFile(tmp)
            throw LibraryFileTooLargeException("File is larger than $maxBytes bytes")
        } catch (t: Throwable) {
            discardFile(tmp)
            throw t
        }
    }

    /**
     * Moves [staged] into the store as `blobs/<sha>` (atomic rename) or, when that content is already stored, deletes
     * the temp copy. Returns the blob file. Idempotent for an already committed or discarded [staged].
     */
    fun commit(staged: Staged): File {
        val target = file(staged.sha256)
        try {
            if (!staged.tmp.exists()) {
                if (target.exists()) return target
                throw IOException("Staged copy is gone")
            }
            blobsDir.mkdirs()
            if (target.exists() && target.length() == staged.sizeBytes) {
                staged.tmp.delete()
            } else {
                try {
                    Files.move(staged.tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(staged.tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                syncDir(blobsDir)
            }
            return target
        } finally {
            active -= staged.tmp.name
        }
    }

    /** Drops a staged copy that will not be committed. */
    fun discard(staged: Staged) = discardFile(staged.tmp)

    fun file(sha256: String): File {
        require(SHA_PATTERN.matches(sha256)) { "Not a SHA-256: $sha256" }
        return File(blobsDir, sha256)
    }

    fun exists(sha256: String): Boolean = SHA_PATTERN.matches(sha256) && File(blobsDir, sha256).isFile

    fun delete(sha256: String): Boolean = SHA_PATTERN.matches(sha256) && File(blobsDir, sha256).delete()

    /** Names of every stored blob. */
    fun list(): List<String> = blobsDir.list()?.filter { SHA_PATTERN.matches(it) }.orEmpty()

    /** Deletes temp files left by earlier runs (copies in progress now are kept). Returns how many were deleted. */
    fun sweepTmp(): Int {
        var n = 0
        tmpDir.listFiles()?.forEach { f ->
            if (f.name !in active && f.deleteRecursively()) n++
        }
        return n
    }

    /**
     * Deletes blobs not in [referenced] (and stray non-blob files in `blobs/`). The caller must hold its commit lock so
     * that no import is between [commit] and writing its row. Returns how many were deleted.
     */
    fun sweepOrphans(referenced: Set<String>): Int {
        var n = 0
        blobsDir.listFiles()?.forEach { f ->
            val keep = SHA_PATTERN.matches(f.name) && f.name in referenced
            if (!keep && f.deleteRecursively()) n++
        }
        return n
    }

    private fun checkSpace(incoming: Long) {
        val free = freeSpace.availableBytes(root.takeIf { it.exists() } ?: tmpDir)
        if (free - incoming < reserveBytes) throw NotEnoughSpaceException("Not enough free space")
    }

    private fun discardFile(tmp: File) {
        tmp.delete()
        active -= tmp.name
    }

    private fun syncDir(dir: File) {
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }

    companion object {
        const val HEAD_BYTES = 512
        private const val BUFFER_BYTES = 64 * 1024
        private const val SPACE_CHECK_BYTES = 8L * 1024 * 1024
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")

        private fun ByteArray.toHex(): String {
            val hex = "0123456789abcdef"
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                sb.append(hex[v shr 4]).append(hex[v and 0xF])
            }
            return sb.toString()
        }
    }
}
