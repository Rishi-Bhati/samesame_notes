package org.samesame.notes.core.files

import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.files.FileNames

/** Why a tree node was not planned for upload. */
enum class UploadSkipReason { EmptyName, TooDeep, TooManyFiles }

data class UploadSkip(val name: String, val reason: UploadSkipReason)

/** A folder to create under [parentPath] (empty = Files root). [path] includes the folder itself. */
data class PlannedFolder(val parentPath: List<String>, val name: String) {
    val path: List<String> get() = parentPath + name
}

/** A file to import into [folderPath] (empty = Files root). */
data class PlannedFile<out T>(val folderPath: List<String>, val name: String, val value: T)

data class UploadPlan<out T>(
    val folders: List<PlannedFolder> = emptyList(),
    val files: List<PlannedFile<T>> = emptyList(),
    val skipped: List<UploadSkip> = emptyList(),
) {
    val fileCount: Int get() = files.size
}

/**
 * Maps a materialised tree to Files folders and imports. Names are sanitised with [FileNames], sibling folders get
 * unique names (also against the names already present, from [existingNames]), depth is capped by
 * [Limits.MAX_FOLDER_DEPTH] and the file count by [maxFiles]. Everything skipped is reported for the summary.
 *
 * This is pure: the caller maps [PlannedFolder.path] to the created folder id and imports each [PlannedFile.value]
 * through the existing atomic pipeline (`FilesRepository.importFromUri`).
 */
fun <T> planFolderUpload(
    roots: List<UploadNode<T>>,
    existingNames: Map<List<String>, Set<String>> = emptyMap(),
    maxFiles: Int = Limits.MAX_FILES_PER_UPLOAD,
): UploadPlan<T> {
    val folders = ArrayList<PlannedFolder>()
    val files = ArrayList<PlannedFile<T>>()
    val skipped = ArrayList<UploadSkip>()
    val used = HashMap<List<String>, HashSet<String>>()
    var fileCount = 0

    fun visit(nodes: List<UploadNode<T>>, parentPath: List<String>) {
        for (node in nodes) {
            if (node.isDirectory) {
                if (parentPath.size >= Limits.MAX_FOLDER_DEPTH) {
                    skipped += UploadSkip(node.name, UploadSkipReason.TooDeep)
                    continue
                }
                val clean = try {
                    FileNames.sanitizeFolderName(node.name)
                } catch (e: IllegalArgumentException) {
                    skipped += UploadSkip(node.name, UploadSkipReason.EmptyName)
                    continue
                }
                val taken = HashSet(existingNames[parentPath].orEmpty())
                taken += used[parentPath].orEmpty()
                val name = FileNames.unique(clean, taken)
                used.getOrPut(parentPath) { HashSet() } += name
                val folder = PlannedFolder(parentPath, name)
                folders += folder
                visit(node.children, folder.path)
            } else {
                if (fileCount >= maxFiles) {
                    skipped += UploadSkip(node.name, UploadSkipReason.TooManyFiles)
                    continue
                }
                fileCount++
                files += PlannedFile(parentPath, FileNames.sanitize(node.name), node.value)
            }
        }
    }
    visit(roots, emptyList())
    return UploadPlan(folders, files, skipped)
}
