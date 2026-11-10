package org.samesame.notes.core.document

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream

/** Container v3: optional section with the library files a note links to (`files/files.json` + `files/<sha256>`). */
const val LINKED_FILES_DIR = "files"
const val LINKED_FILES_INDEX = "$LINKED_FILES_DIR/files.json"

/** One library file inside a v3 container. Several entries may share one [sha256] (and so one `files/<sha256>` blob). */
data class LinkedFileEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    /** Names of the Files folders the file was in on the exporting device, top level first. */
    val folderPath: List<String> = emptyList(),
)

/** A file to bundle on export: its entry and a way to read its bytes. */
class LinkedFileSource(val entry: LinkedFileEntry, val open: () -> InputStream)

/**
 * Puts bundled files back into the Files library on import. Returns old id to new id for every file that had to get
 * a new id (its original id belongs to a different file); files restored under their own id are not listed.
 * [open] reads one entry's content (each call opens a fresh stream; a size cap is enforced by the caller).
 */
fun interface LinkedFilesRestorer {
    suspend fun restore(noteTitle: String, files: List<LinkedFileEntry>, open: (LinkedFileEntry) -> InputStream): Map<String, String>
}

/** What a container import produced. [restoredFiles] counts bundled files offered to the restorer. */
class ContainerImport(
    val document: org.samesame.notes.core.model.document.NoteDocument,
    val legacyViewState: org.samesame.notes.core.model.document.ViewState?,
    val bundledFiles: Int,
)

internal object LinkedFilesJson {
    private const val VERSION = 1
    private val SHA = Regex("[0-9a-f]{64}")

    fun encode(entries: List<LinkedFileEntry>): ByteArray = buildJsonObject {
        put("version", VERSION)
        put(
            "files",
            buildJsonArray {
                for (e in entries) add(
                    buildJsonObject {
                        put("id", e.id)
                        put("name", e.name)
                        put("mime", e.mime)
                        put("size", e.size)
                        put("sha256", e.sha256)
                        put("folderPath", buildJsonArray { e.folderPath.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            },
        )
    }.toString().encodeToByteArray()

    /** Parses leniently: entries with a missing or malformed field are dropped, unknown fields are ignored. */
    fun decode(bytes: ByteArray): List<LinkedFileEntry> {
        val obj = try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            throw IOException("Bad files/files.json", e)
        } ?: throw IOException("Bad files/files.json")
        val arr = obj["files"] as? JsonArray ?: return emptyList()
        val out = ArrayList<LinkedFileEntry>()
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val sha = o["sha256"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: continue
            if (!SHA.matches(sha)) continue
            val size = runCatching { o["size"]!!.jsonPrimitive.long }.getOrNull() ?: continue
            if (size < 0) continue
            out += LinkedFileEntry(
                id = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                mime = o["mime"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                size = size,
                sha256 = sha,
                folderPath = runCatching { o["folderPath"]!!.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()),
            )
        }
        return out
    }
}
