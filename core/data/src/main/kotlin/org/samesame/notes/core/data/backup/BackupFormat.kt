package org.samesame.notes.core.data.backup

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_FOLDER_PREFIX = "SameSame backup "
const val BACKUP_PARTIAL_SUFFIX = ".partial"
const val BACKUP_MANIFEST_FILE = "manifest.json"
const val BACKUP_FOLDERS_FILE = "folders.json"
const val BACKUP_NOTES_DIR = "notes"
const val BACKUP_LIBRARY_DIR = "library"
const val BACKUP_LIBRARY_META_FILE = "library/library.json"

private val folderNamePattern = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.US)
private val folderNameFormat: DateTimeFormatter
    get() = folderNamePattern.withZone(ZoneId.systemDefault())

fun backupFolderName(timeMs: Long): String = BACKUP_FOLDER_PREFIX + folderNameFormat.format(Instant.ofEpochMilli(timeMs))

/** True for a completed backup folder name created by this app (not `.partial`). */
fun isCompleteBackupFolderName(name: String): Boolean =
    name.startsWith(BACKUP_FOLDER_PREFIX) && !name.endsWith(BACKUP_PARTIAL_SUFFIX)

/** True for a partial backup folder name created by this app. */
fun isPartialBackupFolderName(name: String): Boolean =
    name.startsWith(BACKUP_FOLDER_PREFIX) && name.endsWith(BACKUP_PARTIAL_SUFFIX)

/** Parses the timestamp encoded in a backup folder name, or null when not a backup folder name. */
fun parseBackupFolderTime(name: String): Long? {
    val base = when {
        isPartialBackupFolderName(name) -> name.removeSuffix(BACKUP_PARTIAL_SUFFIX)
        isCompleteBackupFolderName(name) -> name
        else -> return null
    }
    val stamp = base.removePrefix(BACKUP_FOLDER_PREFIX)
    return runCatching {
        val ldt = LocalDateTime.parse(stamp, folderNameFormat)
        ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
}

fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

data class BackupNoteEntry(
    val id: String,
    val title: String,
    val modifiedTime: Long,
    val folderPath: List<String>,
    val favourite: Boolean,
    val locked: Boolean,
    val fileName: String,
    val size: Long,
    val sha256: String,
)

data class BackupLibraryFileEntry(
    val id: String,
    val folderPath: List<String>,
    val displayName: String,
    val ext: String,
    val mimeType: String,
    val category: String,
    val sizeBytes: Long,
    val sha256: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val pageCount: Int?,
    val sourceApp: String?,
    val createdAt: Long,
    val modifiedAt: Long,
    val sortIndex: Long,
    val isFavorite: Boolean,
    val trashedAt: Long?,
    val description: String,
    val tags: List<String>,
    val blobFileName: String,
)

data class BackupLibraryFolderEntry(
    val id: String,
    val path: List<String>,
    val color: Long?,
    val sortIndex: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val trashedAt: Long?,
)

data class BackupNoteFolderEntry(
    val id: String,
    val path: List<String>,
    val color: Long?,
    val sortOrder: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val trashedAt: Long?,
)

data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val appVersion: String,
    val createdTime: Long,
    val notes: List<BackupNoteEntry>,
    val libraryFiles: List<BackupLibraryFileEntry>,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("formatVersion", formatVersion)
        root.put("appVersion", appVersion)
        root.put("createdTime", createdTime)
        val notesArr = JSONArray()
        for (n in notes) {
            val o = JSONObject()
            o.put("id", n.id)
            o.put("title", n.title)
            o.put("modifiedTime", n.modifiedTime)
            o.put("folderPath", JSONArray(n.folderPath))
            o.put("favourite", n.favourite)
            o.put("locked", n.locked)
            o.put("fileName", n.fileName)
            o.put("size", n.size)
            o.put("sha256", n.sha256)
            notesArr.put(o)
        }
        root.put("notes", notesArr)
        val libArr = JSONArray()
        for (f in libraryFiles) {
            val o = JSONObject()
            o.put("id", f.id)
            o.put("folderPath", JSONArray(f.folderPath))
            o.put("displayName", f.displayName)
            o.put("ext", f.ext)
            o.put("mimeType", f.mimeType)
            o.put("category", f.category)
            o.put("sizeBytes", f.sizeBytes)
            o.put("sha256", f.sha256)
            o.putOpt("width", f.width)
            o.putOpt("height", f.height)
            o.putOpt("durationMs", f.durationMs)
            o.putOpt("pageCount", f.pageCount)
            o.putOpt("sourceApp", f.sourceApp)
            o.put("createdAt", f.createdAt)
            o.put("modifiedAt", f.modifiedAt)
            o.put("sortIndex", f.sortIndex)
            o.put("isFavorite", f.isFavorite)
            o.putOpt("trashedAt", f.trashedAt)
            o.put("description", f.description)
            o.put("tags", JSONArray(f.tags))
            o.put("blobFileName", f.blobFileName)
            libArr.put(o)
        }
        root.put("libraryFiles", libArr)
        return root.toString()
    }

    companion object {
        fun fromJson(text: String): BackupManifest {
            val root = JSONObject(text)
            val notes = mutableListOf<BackupNoteEntry>()
            val notesArr = root.optJSONArray("notes") ?: JSONArray()
            for (i in 0 until notesArr.length()) {
                val o = notesArr.getJSONObject(i)
                notes += BackupNoteEntry(
                    id = o.getString("id"),
                    title = o.optString("title", ""),
                    modifiedTime = o.optLong("modifiedTime", 0L),
                    folderPath = o.optJSONArray("folderPath").toStringList(),
                    favourite = o.optBoolean("favourite", false),
                    locked = o.optBoolean("locked", false),
                    fileName = o.getString("fileName"),
                    size = o.optLong("size", 0L),
                    sha256 = o.optString("sha256", ""),
                )
            }
            val libs = mutableListOf<BackupLibraryFileEntry>()
            val libArr = root.optJSONArray("libraryFiles") ?: JSONArray()
            for (i in 0 until libArr.length()) {
                val o = libArr.getJSONObject(i)
                libs += BackupLibraryFileEntry(
                    id = o.getString("id"),
                    folderPath = o.optJSONArray("folderPath").toStringList(),
                    displayName = o.optString("displayName", "file"),
                    ext = o.optString("ext", ""),
                    mimeType = o.optString("mimeType", "application/octet-stream"),
                    category = o.optString("category", "Other"),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    sha256 = o.optString("sha256", ""),
                    width = if (o.has("width") && !o.isNull("width")) o.optInt("width") else null,
                    height = if (o.has("height") && !o.isNull("height")) o.optInt("height") else null,
                    durationMs = if (o.has("durationMs") && !o.isNull("durationMs")) o.optLong("durationMs") else null,
                    pageCount = if (o.has("pageCount") && !o.isNull("pageCount")) o.optInt("pageCount") else null,
                    sourceApp = if (o.has("sourceApp") && !o.isNull("sourceApp")) o.optString("sourceApp") else null,
                    createdAt = o.optLong("createdAt", 0L),
                    modifiedAt = o.optLong("modifiedAt", 0L),
                    sortIndex = o.optLong("sortIndex", 0L),
                    isFavorite = o.optBoolean("isFavorite", false),
                    trashedAt = if (o.has("trashedAt") && !o.isNull("trashedAt")) o.optLong("trashedAt") else null,
                    description = o.optString("description", ""),
                    tags = o.optJSONArray("tags").toStringList(),
                    blobFileName = o.optString("blobFileName", o.optString("sha256", "")),
                )
            }
            return BackupManifest(
                formatVersion = root.optInt("formatVersion", BACKUP_FORMAT_VERSION),
                appVersion = root.optString("appVersion", ""),
                createdTime = root.optLong("createdTime", 0L),
                notes = notes,
                libraryFiles = libs,
            )
        }
    }
}

fun foldersToJson(noteFolders: List<BackupNoteFolderEntry>, libraryFolders: List<BackupLibraryFolderEntry>): String {
    val root = JSONObject()
    val nf = JSONArray()
    for (f in noteFolders) {
        val o = JSONObject()
        o.put("id", f.id)
        o.put("path", JSONArray(f.path))
        o.putOpt("color", f.color)
        o.put("sortOrder", f.sortOrder)
        o.put("createdAt", f.createdAt)
        o.put("modifiedAt", f.modifiedAt)
        o.putOpt("trashedAt", f.trashedAt)
        nf.put(o)
    }
    root.put("noteFolders", nf)
    val lf = JSONArray()
    for (f in libraryFolders) {
        val o = JSONObject()
        o.put("id", f.id)
        o.put("path", JSONArray(f.path))
        o.putOpt("color", f.color)
        o.put("sortIndex", f.sortIndex)
        o.put("createdAt", f.createdAt)
        o.put("modifiedAt", f.modifiedAt)
        o.putOpt("trashedAt", f.trashedAt)
        lf.put(o)
    }
    root.put("libraryFolders", lf)
    return root.toString()
}

data class BackupFoldersData(
    val noteFolders: List<BackupNoteFolderEntry>,
    val libraryFolders: List<BackupLibraryFolderEntry>,
)

fun foldersFromJson(text: String): BackupFoldersData {
    val root = JSONObject(text)
    val nf = mutableListOf<BackupNoteFolderEntry>()
    val nfa = root.optJSONArray("noteFolders") ?: JSONArray()
    for (i in 0 until nfa.length()) {
        val o = nfa.getJSONObject(i)
        nf += BackupNoteFolderEntry(
            id = o.optString("id", ""),
            path = o.optJSONArray("path").toStringList(),
            color = if (o.has("color") && !o.isNull("color")) o.optLong("color") else null,
            sortOrder = o.optInt("sortOrder", 0),
            createdAt = o.optLong("createdAt", 0L),
            modifiedAt = o.optLong("modifiedAt", 0L),
            trashedAt = if (o.has("trashedAt") && !o.isNull("trashedAt")) o.optLong("trashedAt") else null,
        )
    }
    val lf = mutableListOf<BackupLibraryFolderEntry>()
    val lfa = root.optJSONArray("libraryFolders") ?: JSONArray()
    for (i in 0 until lfa.length()) {
        val o = lfa.getJSONObject(i)
        lf += BackupLibraryFolderEntry(
            id = o.optString("id", ""),
            path = o.optJSONArray("path").toStringList(),
            color = if (o.has("color") && !o.isNull("color")) o.optLong("color") else null,
            sortIndex = o.optLong("sortIndex", 0L),
            createdAt = o.optLong("createdAt", 0L),
            modifiedAt = o.optLong("modifiedAt", 0L),
            trashedAt = if (o.has("trashedAt") && !o.isNull("trashedAt")) o.optLong("trashedAt") else null,
        )
    }
    return BackupFoldersData(nf, lf)
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out += optString(i, "")
    return out
}
