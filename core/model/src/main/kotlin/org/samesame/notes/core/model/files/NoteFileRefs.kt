package org.samesame.notes.core.model.files

import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.OBJECT_CHAR
import org.samesame.notes.core.model.richtext.RichText

/**
 * The library files a note uses, for the Files backlinks (`file_refs`, rebuilt on every save):
 * - [FileRefKind.Link]: each stored link run (`CharStyle.link`, adjacent runs with the same URI are one link) and each raw
 *   `samesame://file/...` text outside such a run;
 * - [FileRefKind.Inline]: each picture in the body that was shown from a library file (`ImageObject.libraryFileId`).
 *
 * Only the body is scanned: floating objects live in the page files, which a save doesn't always rewrite. A picture moved
 * off the text (Remove anchor) therefore stops counting as a use; the file itself is never affected.
 */
object NoteFileRefs {
    fun of(doc: NoteDocument): Map<FileId, Map<FileRefKind, Int>> {
        val counts = HashMap<FileId, HashMap<FileRefKind, Int>>()
        fun add(id: FileId, kind: FileRefKind) {
            val m = counts.getOrPut(id) { HashMap() }
            m[kind] = (m[kind] ?: 0) + 1
        }
        val body = doc.body
        val linked = linkRuns(body)
        for ((_, _, uri) in linked) FileLinks.parse(uri)?.let { add(it.id, FileRefKind.Link) }
        if (body.text.contains("://")) {
            for (m in FileLinks.PATTERN.findAll(body.text)) {
                val s = m.range.first
                if (linked.any { s >= it.first && s < it.second }) continue
                FileLinks.parse(m.value)?.let { add(it.id, FileRefKind.Link) }
            }
        }
        val images = doc.inlineObjects.filterIsInstance<ImageObject>().filter { it.libraryFileId != null }.associateBy { it.id }
        if (images.isNotEmpty()) {
            var pos = 0
            for (run in body.runs) {
                val img = run.style.objectId?.let(images::get)
                if (img != null) {
                    val id = FileId(img.libraryFileId!!.lowercase())
                    for (i in pos until pos + run.length) if (body.text.getOrNull(i) == OBJECT_CHAR) add(id, FileRefKind.Inline)
                }
                pos += run.length
            }
        }
        return counts
    }

    /** Stored link runs `[start, end)` with their URI, adjacent runs with the same URI joined. */
    fun linkRuns(rt: RichText): List<Triple<Int, Int, String>> {
        val out = ArrayList<Triple<Int, Int, String>>()
        var pos = 0
        for (run in rt.runs) {
            val uri = run.style.link
            if (uri != null && run.length > 0) {
                val last = out.lastOrNull()
                if (last != null && last.third == uri && last.second == pos) out[out.lastIndex] = Triple(last.first, pos + run.length, uri)
                else out += Triple(pos, pos + run.length, uri)
            }
            pos += run.length
        }
        return out
    }
}
