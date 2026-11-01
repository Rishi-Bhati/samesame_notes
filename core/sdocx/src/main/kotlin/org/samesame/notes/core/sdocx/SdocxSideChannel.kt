package org.samesame.notes.core.sdocx

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.samesame.notes.core.sdocx.record.EndTagRecord
import org.samesame.notes.core.sdocx.record.LayerRecord
import org.samesame.notes.core.sdocx.record.ObjectEntry
import org.samesame.notes.core.sdocx.record.PageRecord

/**
 * Round-trip side-channel for imported `.sdocx` notes, stored inside the `.ssnote` bundle per spec
 * §12.5. A note that came from `.sdocx` keeps its original file here so that import → edit → export
 * loses nothing, including object types SameSame cannot model.
 *
 * Layout written into the bundle:
 * ```
 * sdocx/origin.json          identity + a raw-content index (searchable metadata, spec §12.5)
 * sdocx/raw/origin.sdocx     the complete original `.sdocx` bytes (every raw record, verbatim)
 * ```
 *
 * The whole original file is kept as a single immutable blob rather than one file per raw unit
 * (spec's `sdocx/raw/<uuid>.bin` suggestion). That is deliberate: every unit's raw bytes live inside
 * it already, and re-emitting the blob cannot lose a field to a JSON re-encoding. [origin.json]
 * carries the page/layer/object index so a future edit-merging pass can locate units without parsing
 * the blob. When we do start merging edited fields back, the per-unit files can be split out without
 * changing the import/export contract.
 */
object SdocxSideChannel {

    /** Bundle directory that marks a note as `.sdocx`-sourced. */
    const val DIR = "sdocx"

    const val ORIGIN_PATH = "sdocx/origin.json"
    const val RAW_ARCHIVE_PATH = "sdocx/raw/origin.sdocx"

    fun isSideChannel(path: String): Boolean = path == DIR || path.startsWith("$DIR/")

    /**
     * Encodes [archive] into bundle-relative path → bytes. Importers pass the exact [originalBytes]
     * they parsed: reserializing would change ZIP metadata and potentially unknown record fields.
     * The default is for programmatically constructed archives and older callers.
     */
    fun encode(
        archive: SdocxArchive,
        originalBytes: ByteArray = SdocxWriter().writeToBytes(archive),
    ): Map<String, ByteArray> {
        return mapOf(
            ORIGIN_PATH to originJson(archive).toString().toByteArray(Charsets.UTF_8),
            RAW_ARCHIVE_PATH to originalBytes,
        )
    }

    /** Decodes the side-channel entries, or null when the note did not come from `.sdocx`. */
    fun decode(entries: Map<String, ByteArray>): SdocxArchive? {
        val raw = entries[RAW_ARCHIVE_PATH] ?: return null
        return SdocxReader().read(raw)
    }

    /** Reads the entries written by [encode] from any map of bundle paths. */
    fun entriesOf(all: Map<String, ByteArray>): Map<String, ByteArray> =
        all.filterKeys { isSideChannel(it) }

    private fun originJson(archive: SdocxArchive): JsonObject = buildJsonObject {
        put("sideChannelVersion", 1)
        put("noteId", archive.note.noteId)
        put("formatVersion", archive.note.formatVersion)
        put("minFormatVersion", archive.note.minFormatVersion)
        put("fileRevision", archive.note.fileRevision)
        put("createdTimeUs", archive.note.createdTime)
        put("modifiedTimeUs", archive.note.modifiedTime)
        put("locked", archive.isLocked)
        put("documentType", archive.endTag.documentType)
        put("ownerId", archive.ownerId)
        put("pageMode", archive.endTag.pageMode)
        put("rawArchive", RAW_ARCHIVE_PATH)
        put("note", noteIdentity(archive.note))
        put("endTag", endTagIdentity(archive.endTag))
        put("pages", buildJsonArray {
            archive.pages.forEach { page ->
                add(
                    buildJsonObject {
                        put("pageId", page.pageId)
                        put("modifiedTimeUs", page.modifiedTime)
                        put("layers", buildJsonArray {
                            page.layers.forEach { layer -> add(layerIdentity(layer)) }
                        })
                    },
                )
            }
        })
        put("media", buildJsonArray {
            archive.media.entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("bindNumber", entry.bindNumber)
                        put("fileName", entry.fileName)
                        put("fileHash", entry.fileHash)
                        put("refCount", entry.refCount)
                        put("modifiedTimeUs", entry.modifiedTime)
                    },
                )
            }
        })
        put("extraEntries", buildJsonArray {
            archive.extraEntries.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
        })
    }

    private fun noteIdentity(note: org.samesame.notes.core.sdocx.record.NoteRecord): JsonObject = buildJsonObject {
        put("noteFlags", note.noteFlags.value)
        put("propMask", note.propMask)
        put("width", note.width)
        put("height", note.height)
        put("pageHorizontalPadding", note.pageHorizontalPadding)
        put("pageVerticalPadding", note.pageVerticalPadding)
        put("adjustForDarkMode", note.adjustForDarkMode)
    }

    private fun endTagIdentity(tag: EndTagRecord): JsonObject = buildJsonObject {
        put("formatVersion", tag.formatVersion)
        put("appName", tag.appName)
        put("appMajorVersion", tag.appMajorVersion)
        put("appMinorVersion", tag.appMinorVersion)
        put("appPatchName", tag.appPatchName)
        put("lastViewedPageIndex", tag.lastViewedPageIndex)
        put("fixedFont", tag.fixedFont)
        put("fixedTextDirection", tag.fixedTextDirection)
        put("fixedBackgroundTheme", tag.fixedBackgroundTheme)
    }

    private fun layerIdentity(layer: LayerRecord): JsonObject = buildJsonObject {
        put("layerId", layer.layerId ?: "")
        put("layerNumber", layer.layerNumber)
        put("flags", layer.flags)
        put("optMask", layer.optMask)
        put("modifiedTimeUs", layer.modifiedTime ?: 0L)
        put("objects", buildJsonArray {
            layer.objects.forEach { add(objectIdentity(it)) }
        })
    }

    private fun objectIdentity(obj: ObjectEntry): JsonObject = buildJsonObject {
        put("objectType", obj.objectType)
        put("childCount", obj.childCount)
        put("uuid", obj.uuid ?: "")
        put("modifiedTimeUs", obj.modifiedTime ?: 0L)
        put("recordLen", obj.record.size)
        if (obj.children.isNotEmpty()) {
            put("children", buildJsonArray { obj.children.forEach { add(objectIdentity(it)) } })
        }
    }

}
