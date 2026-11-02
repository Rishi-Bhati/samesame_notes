package org.samesame.notes.core.data.repository

import java.io.File
import java.security.MessageDigest

/**
 * First-page previews of the home cards on disk (`cache/previews-v1/<hash of note id>/<revision>-<width>-<variant>.png`),
 * so a cold start or a card scrolled back into view decodes a small PNG instead of reading the note and rendering
 * its page again. One revision is kept per note; the files live in the app's cache directory (the system may clear
 * it, the next render fills it again). Locked notes are never written here, and locking a note forgets its files.
 * [VERSION] in the directory name is bumped when the renderer's output changes, so old previews are not reused.
 */
class PreviewDiskCache(root: File) {
    private val dir = File(root, "previews-v$VERSION")

    /** The cached PNG of this revision, or null. */
    fun get(noteId: String, revision: Long, widthPx: Int, variant: String): ByteArray? =
        runCatching { file(noteId, revision, widthPx, variant).takeIf { it.isFile }?.readBytes() }.getOrNull()

    /** Stores [png] and drops the note's previews of other revisions. Best effort: a failed write only costs a render. */
    fun put(noteId: String, revision: Long, widthPx: Int, variant: String, png: ByteArray) {
        runCatching {
            val target = file(noteId, revision, widthPx, variant)
            val noteDir = target.parentFile ?: return
            noteDir.mkdirs()
            noteDir.listFiles()?.forEach { if (!it.name.startsWith("$revision-")) it.delete() }
            val tmp = File(noteDir, target.name + ".tmp")
            tmp.writeBytes(png)
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    /** Deletes every preview of the note (locked, deleted, or its content can no longer be trusted). */
    fun forget(noteId: String) {
        runCatching { noteDir(noteId).deleteRecursively() }
    }

    private fun file(noteId: String, revision: Long, widthPx: Int, variant: String): File {
        require(variant.all { it.isLetterOrDigit() }) { "Bad variant" }
        return File(noteDir(noteId), "$revision-$widthPx-$variant.png")
    }

    private fun noteDir(noteId: String): File {
        // Note ids may come from imported files: never use them as a path directly.
        val digest = MessageDigest.getInstance("SHA-256").digest(noteId.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        const val VERSION = 1
    }
}
