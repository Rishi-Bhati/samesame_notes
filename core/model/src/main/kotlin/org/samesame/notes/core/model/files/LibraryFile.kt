package org.samesame.notes.core.model.files

import kotlinx.serialization.Serializable
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.NoteId

/** A file in the Files library. The value is a random UUID v4; it is also the id inside a `samesame://file/` link. */
@JvmInline
@Serializable
value class FileId(val value: String) {
    /** True when [value] is a canonical UUID (the only ids the provider and deep link accept). */
    val isWellFormed: Boolean get() = UUID_PATTERN.matches(value)

    companion object {
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}

/** A user folder in Files. This tree is separate from the note folders. */
@JvmInline
@Serializable
value class FileFolderId(val value: String)

/** Broad type of a library file: picks the viewer, the type tile and the Storage bar segment. */
enum class FileCategory { Image, Pdf, Text, Markdown, Audio, Video, Archive, Document, Other }

/** A file stored in Files: a local copy, never a reference to the original. */
data class LibraryFile(
    val id: FileId,
    /** Null means the Files root. */
    val folderId: FileFolderId?,
    /** Sanitised (see [FileNames.sanitize]); never empty, never a path. */
    val displayName: String,
    /** Lower-case extension without the dot, or "" when the file has none. */
    val ext: String,
    val mimeType: String,
    val category: FileCategory,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the content; several files with the same content share one stored copy. */
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val pageCount: Int? = null,
    /** Package that shared the file, when known. */
    val sourceApp: String? = null,
    /** Import time, epoch millis. */
    val createdAt: Long,
    val modifiedAt: Long,
    val sortIndex: Long = 0,
    val isFavorite: Boolean = false,
    /** Epoch millis the file was moved to the Files Recycle bin, or null. */
    val trashedAt: Long? = null,
) {
    val isInTrash: Boolean get() = trashedAt != null

    /** Images a note can show in place ("Show in note"): png, jpeg, webp, gif (first frame), bmp, heif/heic. */
    val isRenderableImage: Boolean get() = mimeType.lowercase() in RENDERABLE_IMAGE_MIMES

    /** Whole days left before the Recycle bin purges this file, or null when it isn't in the Recycle bin. */
    fun daysLeftInTrash(now: Long): Int? {
        val trashedAt = trashedAt ?: return null
        val elapsedDays = ((now - trashedAt) / DAY_MILLIS).toInt()
        return (Limits.TRASH_RETENTION_DAYS - elapsedDays).coerceAtLeast(0)
    }

    companion object {
        val RENDERABLE_IMAGE_MIMES = setOf(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/heif", "image/heic",
        )
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** A Files folder. [depth] is 0 for top-level folders; [fileCount] counts the files directly inside, outside the Recycle bin. */
data class FileFolder(
    val id: FileFolderId,
    val parentId: FileFolderId?,
    val name: String,
    /** ARGB, or null for the default color. */
    val color: Long?,
    val sortIndex: Long,
    val depth: Int,
    val fileCount: Int,
    val createdAt: Long,
    val modifiedAt: Long,
    val trashedAt: Long? = null,
) {
    val isInTrash: Boolean get() = trashedAt != null
}

enum class FileSortField { Name, DateAdded, Size, Type }

/** Order of the file list. Folders always come first; the UI shows them separately. */
data class FileSort(val field: FileSortField = FileSortField.DateAdded, val ascending: Boolean = false)

/** How a note uses a library file: a link run, or an image shown in place ("Show in note"). */
enum class FileRefKind { Link, Inline }

/** One note that uses a file (the Info sheet's "Used in N notes"). */
data class FileUsage(
    val noteId: NoteId,
    /** The note's visible title; titles of locked notes stay visible (owner decision). */
    val noteTitle: String,
    val isLocked: Boolean,
    /** The note is in the Recycle bin ("(in Recycle bin)"); it still counts. */
    val noteInTrash: Boolean,
    val kinds: Set<FileRefKind>,
)

/** Totals for the Files subtitle ("12 files, 340 MB"), the drawer badge and the Storage page. */
data class FilesSummary(
    val fileCount: Int = 0,
    /** Sum of the files' sizes (what the user sees; shared copies count once per file). */
    val totalBytes: Long = 0,
    val trashedCount: Int = 0,
    val trashedBytes: Long = 0,
)

/** The Recycle bin of Files: trashed folders (top of each trashed subtree) and trashed files not inside one of them. */
data class FilesTrash(val folders: List<FileFolder>, val files: List<LibraryFile>)

/** What an import does when the same content is already in the target folder. */
enum class DuplicatePolicy {
    /** Return [ImportResult.Duplicate] and let the user choose "Keep both" or "Skip". */
    Ask,
    KeepBoth,
    Skip,
}

sealed interface ImportResult {
    data class Imported(val file: LibraryFile) : ImportResult

    /**
     * The same content is already in this folder ("Already in this folder: Keep both / Skip"). Nothing was added; the
     * content is already stored, so [pending] can still be added later without reading the source again.
     */
    data class Duplicate(val existing: LibraryFile, val pending: PendingImport) : ImportResult

    /** [DuplicatePolicy.Skip] found the content already in the folder. */
    data class Skipped(val existing: LibraryFile) : ImportResult
}

/** An import held back by [ImportResult.Duplicate]; pass it to the repository's `resolveDuplicate`. */
data class PendingImport(
    val folderId: FileFolderId?,
    val displayName: String,
    val ext: String,
    val mimeType: String,
    val category: FileCategory,
    val sizeBytes: Long,
    val sha256: String,
    val sourceApp: String?,
)

/** The file is larger than [Limits.MAX_LIBRARY_FILE_BYTES]. */
class LibraryFileTooLargeException(message: String) : java.io.IOException(message)

/** Importing would leave less than [Limits.LIBRARY_FREE_SPACE_RESERVE_BYTES] free. */
class NotEnoughSpaceException(message: String) : java.io.IOException(message)
