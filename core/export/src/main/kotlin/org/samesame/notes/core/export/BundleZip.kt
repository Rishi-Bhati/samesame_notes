package org.samesame.notes.core.export

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.samesame.notes.core.model.files.FileLinks
import org.samesame.notes.core.model.files.ZipEntryNames
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streaming zip for "export with files": a `note.md|html` entry plus one `files/<name>` entry per linked file. */
object BundleZip {
    /** Ids of library-file links in already-rendered [text]. */
    fun linkedIds(text: String): Set<String> =
        FileLinks.PATTERN.findAll(text).mapTo(HashSet()) { it.groupValues[1].lowercase() }

    /**
     * Rewrites library-file links whose id has a bundled [path] (values of [paths] are zip entry names) to a relative
     * `files/<name>` destination. Each segment is percent-encoded so spaces and parentheses in a file name cannot
     * break a markdown or HTML link. Other links stay untouched.
     */
    fun rewriteLinks(text: String, paths: Map<String, String>): String =
        WRITABLE_LINK.replace(text) { match ->
            paths[match.groupValues[1].lowercase()]?.let { path ->
                path.split('/').joinToString("/") { segment -> FileLinks.percentEncode(segment) }
            } ?: match.value
        }

    /** Like [FileLinks.PATTERN], but the query stops at a link delimiter so `)` / `"` are not swallowed. */
    private val WRITABLE_LINK = Regex(
        "(?i:samesame)://file/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})" +
            "(?:\\?[^\\s#)\"<>]+)?",
    )

    /**
     * Writes [noteText] as [noteEntry], then [media] and one entry per linked file. Each file is copied straight from
     * its stream into the zip (no whole-file buffering); a cancelled coroutine stops between entries. Entry names are
     * made zip-slip-safe and unique by [ZipEntryNames].
     */
    suspend fun write(
        out: OutputStream,
        noteEntry: String,
        noteText: String,
        media: Map<String, ByteArray> = emptyMap(),
        files: List<ExportLinkedFile> = emptyList(),
        paths: Map<String, String> = ZipEntryNames.allocate(files.map { it.id.lowercase() to it.name }),
    ) {
        val used = HashSet<String>()
        val zip = ZipOutputStream(out.buffered())
        fun begin(name: String) {
            val unique = ZipEntryNames.unique(name, used)
            used += unique
            zip.putNextEntry(ZipEntry(unique))
        }
        begin(noteEntry)
        zip.write(noteText.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for ((name, bytes) in media) {
            currentCoroutineContext().ensureActive()
            begin(name)
            zip.write(bytes)
            zip.closeEntry()
        }
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val path = paths[file.id.lowercase()] ?: continue
            begin(path)
            file.open().use { it.copyTo(zip, BUFFER_BYTES) }
            zip.closeEntry()
        }
        zip.close()
    }

    private const val BUFFER_BYTES = 64 * 1024
}
