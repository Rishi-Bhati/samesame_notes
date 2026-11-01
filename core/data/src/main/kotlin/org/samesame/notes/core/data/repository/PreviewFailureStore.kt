package org.samesame.notes.core.data.repository

import java.io.File
import java.security.MessageDigest

/**
 * Remembers, across process deaths, which note revision's preview render never finished.
 *
 * A marker holding the note's `modifiedAt` is written before a render and deleted when the render returns.
 * If the process dies mid-render (a hostile note exhausting memory, or a native crash in the ink decoder),
 * the marker survives and that revision is not rendered again on the next launch, so the notes list cannot
 * crash on every start. Editing the note changes `modifiedAt` and allows one new attempt.
 */
class PreviewFailureStore(private val dir: File) {

    /** True when a render of this revision started earlier and never returned. */
    fun isBlocked(noteId: String, modifiedAt: Long): Boolean =
        runCatching { file(noteId).takeIf { it.isFile }?.readText()?.trim() == modifiedAt.toString() }.getOrDefault(false)

    fun begin(noteId: String, modifiedAt: Long) {
        runCatching {
            dir.mkdirs()
            file(noteId).writeText(modifiedAt.toString())
        }
    }

    fun end(noteId: String) {
        runCatching { file(noteId).delete() }
    }

    fun forget(noteId: String) = end(noteId)

    private fun file(noteId: String): File {
        // Note ids may come from imported files: never use them as a path directly.
        val digest = MessageDigest.getInstance("SHA-256").digest(noteId.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }
}
