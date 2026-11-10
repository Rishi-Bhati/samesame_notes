package org.samesame.notes.core.sdocx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.samesame.notes.core.model.document.ImageObject
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.richtext.CharStyle
import org.samesame.notes.core.model.richtext.RichText
import org.samesame.notes.core.model.richtext.TextRun
import java.security.MessageDigest

/**
 * Side channel for what `.sdocx` cannot express: link runs (`CharStyle.link`) and `ImageObject.libraryFileId`.
 * The `.sdocx` carries the link label as plain text; this extra ZIP entry (kept verbatim by [SdocxArchive.extraEntries],
 * so it also survives an import -> export round trip) records where each link was:
 * `{"version":1,"links":[{"start","end","uri","labelSha"}],"images":[{"sha","libraryFileId"}]}`.
 * On import a link is restored only where the text at its offsets still hashes to `labelSha`, so an edit made
 * elsewhere never attaches a link to the wrong words. Images are matched by the sha-256 of their bytes.
 */
object SdocxLinkChannel {
    const val ENTRY = "samesame/links.json"
    private const val VERSION = 1

    /** The entry bytes for [doc], or null when it has no link runs and no library-backed images. */
    fun encode(doc: NoteDocument, imageSha: (media: String) -> String?): ByteArray? {
        val links = linkRuns(doc.body)
        val images = doc.inlineObjects.filterIsInstance<ImageObject>().mapNotNull { img ->
            val id = img.libraryFileId ?: return@mapNotNull null
            val sha = imageSha(img.media) ?: return@mapNotNull null
            sha to id
        }.distinct()
        if (links.isEmpty() && images.isEmpty()) return null
        return buildJsonObject {
            put("version", VERSION)
            put("links", buildJsonArray {
                for ((s, e, uri) in links) add(buildJsonObject {
                    put("start", s); put("end", e); put("uri", uri); put("labelSha", sha(doc.body.text.substring(s, e)))
                })
            })
            put("images", buildJsonArray {
                for ((sha, id) in images) add(buildJsonObject { put("sha", sha); put("libraryFileId", id) })
            })
        }.toString().toByteArray(Charsets.UTF_8)
    }

    /** [doc] with the links and library ids from [entry] put back where they still apply; never throws. */
    fun apply(doc: NoteDocument, entry: ByteArray?, imageSha: (media: String) -> String?): NoteDocument {
        if (entry == null) return doc
        return try {
            val obj = Json.parseToJsonElement(entry.decodeToString()) as? JsonObject ?: return doc
            var body = doc.body
            val marks = (obj["links"] as? JsonArray).orEmpty()
            if (marks.isNotEmpty()) {
                val styles = styleAt(body)
                var changed = false
                for (m in marks) {
                    val o = m as? JsonObject ?: continue
                    val s = o["start"]?.jsonPrimitive?.int ?: continue
                    val e = o["end"]?.jsonPrimitive?.int ?: continue
                    val uri = o["uri"]?.jsonPrimitive?.content ?: continue
                    val labelSha = o["labelSha"]?.jsonPrimitive?.content ?: continue
                    if (s < 0 || e <= s || e > body.text.length) continue
                    if (sha(body.text.substring(s, e)) != labelSha) continue
                    for (i in s until e) if (styles[i].link == null) { styles[i] = styles[i].copy(link = uri); changed = true }
                }
                if (changed) body = body.copy(runs = regroup(styles))
            }
            val ids = (obj["images"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val sha = o["sha"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val id = o["libraryFileId"]?.jsonPrimitive?.content ?: return@mapNotNull null
                sha to id
            }.toMap()
            val objects = if (ids.isEmpty()) doc.inlineObjects else doc.inlineObjects.map { o ->
                if (o is ImageObject && o.libraryFileId == null) {
                    val id = imageSha(o.media)?.let(ids::get)
                    if (id != null) o.copy(libraryFileId = id) else o
                } else o
            }
            doc.copy(body = body, inlineObjects = objects)
        } catch (e: Exception) {
            doc
        }
    }

    /** Stored link runs `[start, end)` with their URI (adjacent runs with the same URI joined). */
    private fun linkRuns(rt: RichText): List<Triple<Int, Int, String>> {
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

    private fun styleAt(rt: RichText): MutableList<CharStyle> {
        val list = ArrayList<CharStyle>(rt.text.length)
        for (run in rt.runs) repeat(run.length) { list.add(run.style) }
        while (list.size < rt.text.length) list.add(CharStyle())
        return list
    }

    private fun regroup(styles: List<CharStyle>): List<TextRun> {
        val out = ArrayList<TextRun>()
        for (s in styles) {
            val last = out.lastOrNull()
            if (last != null && last.style == s) out[out.lastIndex] = TextRun(last.length + 1, s) else out += TextRun(1, s)
        }
        return out
    }

    fun sha(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** [archive] with [entry] set (or removed when null) in its extra entries; the same instance when nothing changes. */
    fun withEntry(archive: SdocxArchive, entry: ByteArray?): SdocxArchive {
        val current = archive.extraEntries[ENTRY]
        if (entry == null && current == null) return archive
        if (entry != null && current != null && entry.contentEquals(current)) return archive
        val extra = LinkedHashMap(archive.extraEntries)
        if (entry == null) extra.remove(ENTRY) else extra[ENTRY] = entry
        return SdocxArchive(archive.endTag, archive.note, archive.pageIdInfo, archive.pages, archive.media, archive.mediaFiles, extra)
    }
}
