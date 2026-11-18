package org.samesame.notes.core.data.backup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.Clock
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.database.dao.FolderDao
import org.samesame.notes.core.database.dao.LibraryDao
import org.samesame.notes.core.database.dao.NoteDao
import org.samesame.notes.core.database.dao.SearchDao
import org.samesame.notes.core.database.entity.FileFolderEntity
import org.samesame.notes.core.database.entity.FolderEntity
import org.samesame.notes.core.database.entity.LibraryFileEntity
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.database.entity.NoteSearchEntity
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.files.BlobStore
import org.samesame.notes.core.model.BackupSchedule
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackupRunResult {
    data class Success(val folderName: String, val noteCount: Int, val libraryFileCount: Int, val createdTime: Long) : BackupRunResult
    data class Failure(val error: String) : BackupRunResult
}

data class BackupProgress(val done: Int, val total: Int, val name: String)

data class RestoreResult(
    val restoredNotes: Int,
    val skippedNotes: Int,
    val restoredLibraryFiles: Int,
    val skippedLibraryFiles: Int,
    val problems: List<String> = emptyList(),
)

data class RestoreProgress(val done: Int, val total: Int, val name: String)

data class BackupInfo(val folderName: String, val createdTime: Long, val noteCount: Int)

private const val FOLDER_UNAVAILABLE = "FOLDER_UNAVAILABLE"

@Singleton
class BackupRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val noteDao: NoteDao,
    private val folderDao: FolderDao,
    private val libraryDao: LibraryDao,
    private val searchDao: SearchDao,
    private val documents: NoteDocumentStore,
    private val blobStore: BlobStore,
    private val settings: AppSettingsRepository,
    private val clock: Clock,
) {
    private val appVersion: String by lazy {
        runCatching {
            val pm = context.packageManager
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0)
            }
            pi.versionName.orEmpty()
        }.getOrDefault("")
    }

    fun folderFromSettingsUri(uriString: String?): BackupFolder? =
        if (uriString.isNullOrBlank()) null else SafBackupFolder.fromUriString(context, uriString)

    fun listBackups(folder: BackupFolder): List<BackupInfo> {
        val infos = mutableListOf<BackupInfo>()
        for (e in folder.list("")) {
            if (!e.isDirectory || !isCompleteBackupFolderName(e.name)) continue
            val manifestPath = e.name + "/" + BACKUP_MANIFEST_FILE
            val info = runCatching {
                val text = folder.openInput(manifestPath).bufferedReader().use { it.readText() }
                val m = BackupManifest.fromJson(text)
                if (m.formatVersion != BACKUP_FORMAT_VERSION) return@runCatching null
                BackupInfo(e.name, m.createdTime.takeIf { it > 0 } ?: (parseBackupFolderTime(e.name) ?: 0L), m.notes.size)
            }.getOrNull() ?: continue
            infos += info
        }
        return infos.sortedByDescending { it.createdTime }
    }

    suspend fun backupNow(onProgress: (BackupProgress) -> Unit = {}): BackupRunResult {
        val uri = settings.settings.first().backupFolderUri
        val folder = folderFromSettingsUri(uri)
        return if (folder == null) {
            recordFailure("No backup folder selected").let { BackupRunResult.Failure(it) }
        } else {
            runBackup(folder, onProgress)
        }
    }

    suspend fun runScheduledIfDue(): BackupRunResult? {
        val s = settings.settings.first()
        if (s.backupSchedule == BackupSchedule.Off) return null
        val folder = folderFromSettingsUri(s.backupFolderUri) ?: return BackupRunResult.Failure(recordFailure(FOLDER_UNAVAILABLE))
        return runBackup(folder) {}
    }

    suspend fun runBackup(folder: BackupFolder, onProgress: (BackupProgress) -> Unit = {}): BackupRunResult = withContext(Dispatchers.IO) {
        try {
            if (!folder.exists() || !folder.canWrite()) {
                return@withContext BackupRunResult.Failure(recordFailure(FOLDER_UNAVAILABLE))
            }
            cleanupPartialFolders(folder)
            val now = clock.now()
            val finalName = uniqueFolderName(folder, backupFolderName(now))
            val partialName = finalName + BACKUP_PARTIAL_SUFFIX
            folder.delete(partialName)
            check(folder.createDirectory(partialName)) { "Could not create backup folder" }
            val result = try {
                writeBackup(folder, partialName, now, onProgress)
            } catch (t: Throwable) {
                runCatching { folder.delete(partialName) }
                throw t
            }
            check(folder.rename(partialName, finalName)) { "Could not finalize backup folder" }
            pruneBackups(folder, settings.settings.first().backupKeepCount.coerceIn(1, 10))
            settings.update {
                it.copy(backupLastTime = result.createdTime, backupLastNoteCount = result.noteCount, backupLastError = null)
            }
            BackupRunResult.Success(finalName, result.noteCount, result.libraryFileCount, result.createdTime)
        } catch (t: Throwable) {
            val msg = if (t is BackupFolderUnavailableException) FOLDER_UNAVAILABLE else (t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName)
            BackupRunResult.Failure(recordFailure(msg))
        }
    }

    private suspend fun recordFailure(msg: String): String {
        settings.update { it.copy(backupLastTime = clock.now(), backupLastNoteCount = null, backupLastError = msg) }
        return msg
    }

    private data class WriteResult(val createdTime: Long, val noteCount: Int, val libraryFileCount: Int)

    private suspend fun writeBackup(folder: BackupFolder, partialName: String, now: Long, onProgress: (BackupProgress) -> Unit): WriteResult {
        val noteFolders = folderDao.allFolders()
        val notePathById = buildNoteFolderPaths(noteFolders)
        val notes = noteDao.getAll()
        val libraryFolders = libraryDao.allFolders()
        val libraryFolderPaths = buildLibraryFolderPaths(libraryFolders)
        val libraryFiles = libraryDao.allFiles()
        val libraryTags = libraryDao.observeAllTags().first().groupBy({ it.fileId }, { it.tag })

        val total = notes.size + libraryFiles.map { it.sha256 }.distinct().size + 3
        var done = 0
        fun step(name: String) { onProgress(BackupProgress(done, total, name)); done++ }

        folder.createDirectory("$partialName/$BACKUP_NOTES_DIR")
        folder.createDirectory("$partialName/$BACKUP_LIBRARY_DIR")
        folder.createDirectory("$partialName/$BACKUP_LIBRARY_DIR/blobs")

        val noteEntries = mutableListOf<BackupNoteEntry>()
        for (note in notes) {
            step(note.title.ifBlank { note.id })
            documents.prepareForBackup(note.id)
            val fileName = "${note.id}.ssnote"
            val outPath = "$partialName/$BACKUP_NOTES_DIR/$fileName"
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesSize = 0L
            folder.openOutput(outPath).use { raw ->
                val out = DigestOutputStream(raw, digest)
                val counting = object : OutputStream() {
                    override fun write(b: Int) { out.write(b); bytesSize++ }
                    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); bytesSize += len }
                    override fun flush() = out.flush()
                    override fun close() = out.close()
                }
                documents.exportContainerForBackup(note.id, counting)
                counting.flush()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            noteEntries += BackupNoteEntry(
                id = note.id,
                title = if (note.isLocked) "" else note.title,
                modifiedTime = note.modifiedAt,
                folderPath = note.folderId?.let { notePathById[it] }.orEmpty(),
                favourite = note.isFavorite,
                locked = note.isLocked,
                fileName = fileName,
                size = bytesSize,
                sha256 = sha,
            )
        }

        val libEntries = mutableListOf<BackupLibraryFileEntry>()
        val writtenBlobs = HashSet<String>()
        for (file in libraryFiles) {
            val path = file.folderId?.let { libraryFolderPaths[it] }.orEmpty()
            val tags = libraryTags[file.id].orEmpty().sorted()
            libEntries += BackupLibraryFileEntry(
                id = file.id,
                folderPath = path,
                displayName = file.displayName,
                ext = file.ext,
                mimeType = file.mimeType,
                category = file.category.name,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                width = file.width,
                height = file.height,
                durationMs = file.durationMs,
                pageCount = file.pageCount,
                sourceApp = file.sourceApp,
                createdAt = file.createdAt,
                modifiedAt = file.modifiedAt,
                sortIndex = file.sortIndex,
                isFavorite = file.isFavorite,
                trashedAt = file.trashedAt,
                description = file.description,
                tags = tags,
                blobFileName = file.sha256,
            )
            if (!writtenBlobs.add(file.sha256)) continue
            step(file.displayName)
            val blob = blobStore.file(file.sha256)
            val blobPath = "$partialName/$BACKUP_LIBRARY_DIR/blobs/${file.sha256}"
            if (blob.isFile) {
                folder.openOutput(blobPath).use { out -> blob.inputStream().use { it.copyTo(out) } }
            }
        }

        step(BACKUP_FOLDERS_FILE)
        val foldersJson = foldersToJson(
            noteFolders = noteFolders.map { BackupNoteFolderEntry(it.id, notePathById[it.id].orEmpty(), it.color, it.sortOrder, it.createdAt, it.modifiedAt, it.trashedAt) },
            libraryFolders = libraryFolders.map { BackupLibraryFolderEntry(it.id, libraryFolderPaths[it.id].orEmpty(), it.color, it.sortIndex, it.createdAt, it.modifiedAt, it.trashedAt) },
        )
        folder.openOutput("$partialName/$BACKUP_FOLDERS_FILE", "application/json").bufferedWriter().use { it.write(foldersJson) }

        step(BACKUP_MANIFEST_FILE)
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = appVersion,
            createdTime = now,
            notes = noteEntries.sortedBy { it.id },
            libraryFiles = libEntries.sortedBy { it.id },
        )
        folder.openOutput("$partialName/$BACKUP_MANIFEST_FILE", "application/json").bufferedWriter().use { it.write(manifest.toJson()) }
        onProgress(BackupProgress(total, total, ""))
        return WriteResult(now, noteEntries.size, libEntries.size)
    }

    private fun tryReadSize(folder: BackupFolder, path: String): Long =
        folder.list(path.substringBeforeLast('/')).firstOrNull { path.endsWith("/" + it.name) }?.size ?: 0L

    fun buildNoteFolderPaths(folders: List<FolderEntity>): Map<String, List<String>> {
        val byId = folders.associateBy { it.id }
        val memo = HashMap<String, List<String>>()
        fun path(id: String): List<String> = memo.getOrPut(id) {
            val f = byId[id] ?: return@getOrPut emptyList()
            val parent = f.parentId?.let { path(it) }.orEmpty()
            parent + f.name
        }
        folders.forEach { path(it.id) }
        return memo
    }

    fun buildLibraryFolderPaths(folders: List<FileFolderEntity>): Map<String, List<String>> {
        val byId = folders.associateBy { it.id }
        val memo = HashMap<String, List<String>>()
        fun path(id: String): List<String> = memo.getOrPut(id) {
            val f = byId[id] ?: return@getOrPut emptyList()
            val parent = f.parentId?.let { path(it) }.orEmpty()
            parent + f.name
        }
        folders.forEach { path(it.id) }
        return memo
    }

    private fun cleanupPartialFolders(folder: BackupFolder) {
        folder.list("").forEach { e ->
            if (e.isDirectory && isPartialBackupFolderName(e.name)) runCatching { folder.delete(e.name) }
        }
    }

    private fun uniqueFolderName(folder: BackupFolder, base: String): String {
        if (!folder.exists(base) && !folder.exists(base + BACKUP_PARTIAL_SUFFIX)) return base
        var n = 1
        while (true) {
            val candidate = "$base ($n)"
            if (!folder.exists(candidate) && !folder.exists(candidate + BACKUP_PARTIAL_SUFFIX)) return candidate
            n++
        }
    }

    fun pruneBackups(folder: BackupFolder, keep: Int) {
        cleanupPartialFolders(folder)
        val backups = folder.list("").filter { it.isDirectory && isCompleteBackupFolderName(it.name) }
            .mapNotNull { e ->
                val mPath = e.name + "/" + BACKUP_MANIFEST_FILE
                if (!folder.exists(mPath)) return@mapNotNull null
                val created = runCatching {
                    BackupManifest.fromJson(folder.openInput(mPath).bufferedReader().use { it.readText() }).createdTime
                }.getOrNull() ?: parseBackupFolderTime(e.name) ?: 0L
                e.name to created
            }
            .sortedByDescending { it.second }
        backups.drop(keep.coerceAtLeast(1)).forEach { (name, _) -> runCatching { folder.delete(name) } }
    }

    suspend fun restoreFromFolder(folder: BackupFolder, backupFolderName: String, onProgress: (RestoreProgress) -> Unit = {}): RestoreResult = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()
        val manifestPath = "$backupFolderName/$BACKUP_MANIFEST_FILE"
        val manifest = BackupManifest.fromJson(folder.openInput(manifestPath).bufferedReader().use { it.readText() })
        val foldersData = runCatching {
            foldersFromJson(folder.openInput("$backupFolderName/$BACKUP_FOLDERS_FILE").bufferedReader().use { it.readText() })
        }.getOrDefault(BackupFoldersData(emptyList(), emptyList()))

        val noteFolderPathIds = restoreNoteFolderTree(foldersData.noteFolders)
        val libraryFolderPathIds = restoreLibraryFolderTree(foldersData.libraryFolders)

        val total = manifest.notes.size + manifest.libraryFiles.size
        var done = 0
        fun step(name: String) { onProgress(RestoreProgress(done, total, name)); done++ }

        var restoredNotes = 0
        var skippedNotes = 0
        for (entry in manifest.notes) {
            step(entry.fileName)
            val notePath = "$backupFolderName/$BACKUP_NOTES_DIR/${entry.fileName}"
            val expected = entry.sha256.lowercase()
            val actual = hashOf(folder, notePath)
            if (actual == null || actual != expected) {
                problems += "SHA mismatch: ${entry.fileName}"
                skippedNotes++
                continue
            }
            val existing = noteDao.get(entry.id)
            val decision = when {
                existing == null -> RestoreNoteAction.InstallSame
                entry.modifiedTime > existing.modifiedAt -> RestoreNoteAction.CopyAsNewer
                else -> RestoreNoteAction.Skip
            }
            when (decision) {
                RestoreNoteAction.Skip -> skippedNotes++
                RestoreNoteAction.CopyAsNewer -> {
                    if (entry.locked) {
                        problems += "Locked note newer but cannot be copied: ${entry.id}"
                        skippedNotes++
                    } else {
                        val ok = installUnlockedNoteCopy(folder, notePath, entry, noteFolderPathIds)
                        if (ok) restoredNotes++ else { problems += "Failed to restore: ${entry.fileName}"; skippedNotes++ }
                    }
                }
                RestoreNoteAction.InstallSame -> {
                    val ok = installNote(folder, notePath, entry, noteFolderPathIds)
                    if (ok) restoredNotes++ else { problems += "Failed to restore: ${entry.fileName}"; skippedNotes++ }
                }
            }
        }

        var restoredLibrary = 0
        var skippedLibrary = 0
        for (entry in manifest.libraryFiles) {
            step(entry.displayName)
            val blobPath = "$backupFolderName/$BACKUP_LIBRARY_DIR/blobs/${entry.blobFileName.ifBlank { entry.sha256 }}"
            val actual = hashOf(folder, blobPath)
            if (actual == null || actual.lowercase() != entry.sha256.lowercase()) {
                problems += "SHA mismatch: ${entry.displayName}"
                skippedLibrary++
                continue
            }
            val ok = restoreLibraryFile(folder, blobPath, entry, libraryFolderPathIds)
            if (ok.first) restoredLibrary++ else skippedLibrary++
            ok.second?.let { problems += it }
        }

        onProgress(RestoreProgress(total, total, ""))
        RestoreResult(restoredNotes, skippedNotes, restoredLibrary, skippedLibrary, problems)
    }

    private enum class RestoreNoteAction { InstallSame, CopyAsNewer, Skip }

    private suspend fun installNote(folder: BackupFolder, notePath: String, entry: BackupNoteEntry, folderPathIds: Map<List<String>, String>): Boolean {
        return try {
            val tmp = File(context.cacheDir, "restore-${entry.id}.ssnote")
            try {
                folder.openInput(notePath).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val (locked, doc) = documents.installBackupContainer(tmp, entry.id, rewriteNoteId = false)
                val folderId = entry.folderPath.let { folderPathIds[it] }
                val plain = if (locked || doc == null) "" else doc.body.plainText()
                val title = if (locked) "" else (doc?.title?.ifBlank { entry.title } ?: entry.title)
                val pageMode = doc?.layout?.mode ?: org.samesame.notes.core.model.PageMode.Pages
                val backgroundColor = doc?.style?.backgroundColor ?: 0L
                val entity = NoteEntity(
                    id = entry.id,
                    title = title,
                    previewText = plain.take(300),
                    folderId = folderId,
                    isFavorite = entry.favourite,
                    isLocked = locked,
                    pageMode = pageMode,
                    backgroundColor = backgroundColor,
                    createdAt = entry.modifiedTime,
                    modifiedAt = entry.modifiedTime,
                    lastOpenedAt = entry.modifiedTime,
                    trashedAt = null,
                    isDirty = true,
                )
                noteDao.upsert(entity)
                if (!locked) {
                    searchDao.replace(NoteSearchEntity(noteId = entry.id, title = title, body = plain, inkText = ""))
                } else {
                    searchDao.replace(NoteSearchEntity(noteId = entry.id, title = "", body = "", inkText = ""))
                }
                true
            } finally {
                tmp.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun installUnlockedNoteCopy(folder: BackupFolder, notePath: String, entry: BackupNoteEntry, folderPathIds: Map<List<String>, String>): Boolean {
        return try {
            val newId = java.util.UUID.randomUUID().toString()
            val tmp = File(context.cacheDir, "restore-$newId.ssnote")
            try {
                folder.openInput(notePath).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val (_, doc) = documents.installBackupContainer(tmp, newId, rewriteNoteId = true)
                val folderId = entry.folderPath.let { folderPathIds[it] }
                val plain = doc?.body?.plainText().orEmpty()
                val baseTitle = (doc?.title ?: entry.title).ifBlank { "" }
                val title = if (baseTitle.isBlank()) "(restored)" else "$baseTitle (restored)"
                if (doc != null && doc.title != title) {
                    documents.writeDocument(doc.copy(title = title))
                }
                val entity = NoteEntity(
                    id = newId,
                    title = title,
                    previewText = plain.take(300),
                    folderId = folderId,
                    isFavorite = entry.favourite,
                    isLocked = false,
                    pageMode = doc?.layout?.mode ?: org.samesame.notes.core.model.PageMode.Pages,
                    backgroundColor = doc?.style?.backgroundColor ?: 0L,
                    createdAt = entry.modifiedTime,
                    modifiedAt = entry.modifiedTime,
                    lastOpenedAt = entry.modifiedTime,
                    trashedAt = null,
                    isDirty = true,
                )
                noteDao.upsert(entity)
                searchDao.replace(NoteSearchEntity(noteId = newId, title = title, body = plain, inkText = ""))
                true
            } finally {
                tmp.delete()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun restoreNoteFolderTree(folders: List<BackupNoteFolderEntry>): Map<List<String>, String> {
        val existing = folderDao.allFolders()
        val byPath = HashMap<List<String>, String>()
        // Index existing by path.
        val existingPaths = buildNoteFolderPaths(existing)
        existing.forEach { byPath[existingPaths[it.id].orEmpty()] = it.id }
        val sorted = folders.sortedBy { it.path.size }
        for (f in sorted) {
            if (f.path.isEmpty()) continue
            if (byPath.containsKey(f.path)) continue
            val parentPath = f.path.dropLast(1)
            val parentId = if (parentPath.isEmpty()) null else byPath[parentPath] ?: createNoteFoldersForPath(parentPath, byPath)
            val name = f.path.last()
            val id = java.util.UUID.randomUUID().toString()
            val entity = FolderEntity(id, parentId, name, f.color, f.sortOrder, f.createdAt, f.modifiedAt, f.trashedAt)
            runCatching { folderDao.insert(entity) }.onFailure {
                // fallback: try to find by name under parent
            }
            val found = folderDao.allFolders().firstOrNull { it.name == name && it.parentId == parentId }
            byPath[f.path] = found?.id ?: id
        }
        return byPath
    }

    private suspend fun createNoteFoldersForPath(path: List<String>, byPath: MutableMap<List<String>, String>): String? {
        if (path.isEmpty()) return null
        byPath[path]?.let { return it }
        val parent = createNoteFoldersForPath(path.dropLast(1), byPath)
        val name = path.last()
        val existing = folderDao.allFolders().firstOrNull { it.name == name && it.parentId == parent }
        if (existing != null) { byPath[path] = existing.id; return existing.id }
        val id = java.util.UUID.randomUUID().toString()
        val now = clock.now()
        runCatching { folderDao.insert(FolderEntity(id, parent, name, null, 0, now, now, null)) }
        val created = folderDao.allFolders().firstOrNull { it.name == name && it.parentId == parent }?.id ?: id
        byPath[path] = created
        return created
    }

    private suspend fun restoreLibraryFolderTree(folders: List<BackupLibraryFolderEntry>): Map<List<String>, String> {
        val existing = libraryDao.allFolders()
        val byPath = HashMap<List<String>, String>()
        val existingPaths = buildLibraryFolderPaths(existing)
        existing.forEach { byPath[existingPaths[it.id].orEmpty()] = it.id }
        val sorted = folders.sortedBy { it.path.size }
        for (f in sorted) {
            if (f.path.isEmpty() || byPath.containsKey(f.path)) continue
            val parentPath = f.path.dropLast(1)
            val parentId = if (parentPath.isEmpty()) null else byPath[parentPath] ?: createLibraryFoldersForPath(parentPath, byPath)
            val name = f.path.last()
            val now = clock.now()
            val id = java.util.UUID.randomUUID().toString()
            runCatching {
                libraryDao.insertFolder(FileFolderEntity(id, name, parentId, f.color, f.sortIndex, f.createdAt.takeIf { it > 0 } ?: now, f.modifiedAt.takeIf { it > 0 } ?: now, f.trashedAt))
            }
            val found = libraryDao.allFolders().firstOrNull { it.name == name && it.parentId == parentId }
            byPath[f.path] = found?.id ?: id
        }
        return byPath
    }

    private suspend fun createLibraryFoldersForPath(path: List<String>, byPath: MutableMap<List<String>, String>): String? {
        if (path.isEmpty()) return null
        byPath[path]?.let { return it }
        val parent = createLibraryFoldersForPath(path.dropLast(1), byPath)
        val name = path.last()
        val existing = libraryDao.allFolders().firstOrNull { it.name == name && it.parentId == parent }
        if (existing != null) { byPath[path] = existing.id; return existing.id }
        val now = clock.now()
        val id = java.util.UUID.randomUUID().toString()
        runCatching { libraryDao.insertFolder(FileFolderEntity(id, name, parent, null, 0L, now, now, null)) }
        val created = libraryDao.allFolders().firstOrNull { it.name == name && it.parentId == parent }?.id ?: id
        byPath[path] = created
        return created
    }

    private suspend fun restoreLibraryFile(folder: BackupFolder, blobPath: String, entry: BackupLibraryFileEntry, folderPathIds: Map<List<String>, String>): Pair<Boolean, String?> {
        try {
            val existingSame = libraryDao.allFiles().firstOrNull { it.sha256.equals(entry.sha256, ignoreCase = true) }
            if (existingSame != null && libraryDao.file(existingSame.id) != null) {
                // Identical content exists; skip file (and ensure blob present).
                ensureBlob(folder, blobPath, entry.sha256)
                return false to null
            }
            // Also if any file with same sha exists by query count logic? spec: "skip identical (sha256) ones"
            if (libraryDao.countBySha(entry.sha256) > 0) {
                ensureBlob(folder, blobPath, entry.sha256)
                return false to null
            }
            ensureBlob(folder, blobPath, entry.sha256)
            val folderId = entry.folderPath.let { folderPathIds[it] }
            val existingById = libraryDao.file(entry.id)
            val id = if (existingById == null) entry.id else java.util.UUID.randomUUID().toString()
            val name = if (existingById == null) entry.displayName else entry.displayName
            val entity = LibraryFileEntity(
                id = id,
                folderId = folderId,
                displayName = name,
                ext = entry.ext,
                mimeType = entry.mimeType.ifBlank { "application/octet-stream" },
                category = runCatching { org.samesame.notes.core.model.files.FileCategory.valueOf(entry.category) }.getOrDefault(org.samesame.notes.core.model.files.FileCategory.Other),
                sizeBytes = entry.sizeBytes,
                sha256 = entry.sha256.lowercase(),
                width = entry.width,
                height = entry.height,
                durationMs = entry.durationMs,
                pageCount = entry.pageCount,
                sourceApp = entry.sourceApp,
                createdAt = entry.createdAt,
                modifiedAt = entry.modifiedAt,
                sortIndex = entry.sortIndex,
                isFavorite = entry.isFavorite,
                trashedAt = entry.trashedAt,
                description = entry.description,
            )
            libraryDao.insertFile(entity)
            if (entry.tags.isNotEmpty()) {
                libraryDao.replaceTags(id, entry.tags)
            }
            return true to null
        } catch (t: Throwable) {
            return false to "Failed to restore library file: ${entry.displayName}"
        }
    }

    private fun ensureBlob(folder: BackupFolder, blobPath: String, sha256: String) {
        val dest = blobStore.file(sha256)
        if (dest.isFile && dest.length() > 0) return
        dest.parentFile?.mkdirs()
        folder.openInput(blobPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    private fun hashOf(folder: BackupFolder, path: String): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        folder.openInput(path).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        null
    }

    suspend fun restoreFromSettingsBackup(backupFolderName: String, onProgress: (RestoreProgress) -> Unit = {}): RestoreResult {
        val folder = folderFromSettingsUri(settings.settings.first().backupFolderUri)
            ?: throw BackupFolderUnavailableException()
        return restoreFromFolder(folder, backupFolderName, onProgress)
    }
}
