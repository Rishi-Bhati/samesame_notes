package org.samesame.notes.core.model.files

import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun

/**
 * Rewrites library file ids inside a note: link runs (`CharStyle.link`), raw `samesame://file/<id>` text in the body and
 * `ImageObject.libraryFileId`. Used when an imported container's file got a new id because its old id was taken by a
 * different file. Ids have a fixed length, so text offsets and run lengths never change.
 */
object FileIdRemap {
    fun apply(doc: NoteDocument, map: Map<String, String>): NoteDocument {
        if (map.isEmpty()) return doc
        val lower = map.entries.associate { it.key.lowercase() to it.value }
        val body = remapText(doc.body, lower)
        val objects = doc.inlineObjects.map { o ->
            if (o is ImageObject) {
                val id = o.libraryFileId?.lowercase()
                if (id != null && id in lower) o.copy(libraryFileId = lower.getValue(id)) else o
            } else o
        }
        return doc.copy(body = body, inlineObjects = objects)
    }

    fun remapText(rt: RichText, map: Map<String, String>): RichText {
        var text = rt.text
        if (text.contains(FileLinks.PREFIX, ignoreCase = true)) text = replaceIds(text, map)
        val runs = rt.runs.map { run ->
            val link = run.style.link
            if (link == null) run else {
                val replaced = replaceIds(link, map)
                if (replaced == link) run else TextRun(run.length, run.style.copy(link = replaced))
            }
        }
        return if (text == rt.text && runs == rt.runs) rt else rt.copy(text = text, runs = runs)
    }

    /** Replaces the id of every `samesame://file/<id>` occurrence in [s]. */
    fun replaceIds(s: String, map: Map<String, String>): String =
        FileLinks.PATTERN.replace(s) { m ->
            val old = m.groupValues[1]
            val new = map[old.lowercase()] ?: return@replace m.value
            val start = m.groups[1]!!.range.first - m.range.first
            m.value.substring(0, start) + new + m.value.substring(start + old.length)
        }
}
