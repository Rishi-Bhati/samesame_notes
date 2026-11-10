package org.samesame.notes.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.Clock
import org.samesame.notes.core.database.dao.LibraryDao
import org.samesame.notes.core.database.entity.FileFolderEntity
import org.samesame.notes.core.database.entity.FileRefEntity
import org.samesame.notes.core.database.entity.LibraryFileEntity
import org.samesame.notes.core.database.entity.asExternalModel
import org.samesame.notes.core.files.BlobStore
import org.samesame.notes.core.files.MimeSniffer
import org.samesame.notes.core.files.provider.LibraryFileLookup
import org.samesame.notes.core.files.provider.LibraryProviderContract
import org.samesame.notes.core.files.provider.ProvidedFile
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.files.DuplicatePolicy
import org.samesame.notes.core.model.files.FileCategory
import org.samesame.notes.core.model.files.FileFolder
import org.samesame.notes.core.model.files.FileFolderId
import org.samesame.notes.core.model.files.FileId
import org.samesame.notes.core.model.files.FileNames
import org.samesame.notes.core.model.files.FileRefKind
import org.samesame.notes.core.model.files.FileSort
import org.samesame.notes.core.model.files.FileSortField
import org.samesame.notes.core.model.files.FileUsage
import org.samesame.notes.core.model.files.FilesSummary
import org.samesame.notes.core.model.files.FilesTrash
import org.samesame.notes.core.model.files.ImportResult
import org.samesame.notes.core.model.files.LibraryFile
import org.samesame.notes.core.model.files.PendingImport
import java.io.FileNotFoundException
import java.io.InputStream
import java.text.Collator
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FilesRepository] over Room ([LibraryDao]) and the [BlobStore].
 *
 * Atomicity: the blob is written first (temp copy, fsync, atomic rename), then the row. [commitLock] serialises every
 * step that links blobs and rows (commit + insert, delete + blob removal, the orphan sweep), so a sweep never sees a
 * committed blob whose row is still being written. A crash in between leaves an orphan blob that [runMaintenance]
 * removes at the next start; a blob some row references is never deleted.
 */
@Singleton
class DefaultFilesRepository @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: LibraryDao,
    private val blobs: BlobStore,
    private val clock: Clock,
) : FilesRepository, LibraryFileLookup {

    private val commitLock = Mutex()

    // ---------------------------------------------------------------- folders

    override fun observeFolders(): Flow<List<FileFolder>> =
        combine(dao.observeLiveFolders(), dao.observeFolderFileCounts()) { folders, counts ->
            val byFolder = counts.associate { it.folderId to it.count }
            treeOrder(folders).map { (f, depth) -> f.toModel(depth, byFolder[f.id] ?: 0) }
        }.flowOn(Dispatchers.Default)

    override suspend fun createFolder(name: String, parentId: FileFolderId?, color: Long?): FileFolderId {
        val clean = FileNames.sanitizeFolderName(name)
        val all = dao.allFolders()
        if (parentId != null) {
            val parent = all.firstOrNull { it.id == parentId.value }
            require(parent != null && parent.trashedAt == null) { "Unknown parent folder" }
            require(depthOf(parent.id, all) + 1 < Limits.MAX_FOLDER_DEPTH) { "Folders can nest ${Limits.MAX_FOLDER_DEPTH} levels deep" }
        }
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        dao.insertFolder(
            FileFolderEntity(
                id = id, name = clean, parentId = parentId?.value, color = color,
                sortIndex = dao.maxFolderSortIndex(parentId?.value) + SORT_STEP,
                createdAt = now, modifiedAt = now, trashedAt = null,
            ),
        )
        return FileFolderId(id)
    }

    override suspend fun renameFolder(id: FileFolderId, name: String) =
        dao.renameFolder(id.value, FileNames.sanitizeFolderName(name), clock.now())

    override suspend fun setFolderColor(id: FileFolderId, color: Long?) = dao.setFolderColor(id.value, color, clock.now())

    override suspend fun moveFolder(id: FileFolderId, newParentId: FileFolderId?) {
        val all = dao.allFolders()
        val folder = all.firstOrNull { it.id == id.value }
        require(folder != null) { "Unknown folder" }
        if (folder.parentId == newParentId?.value) return
        val subtree = subtreeOf(listOf(id.value), all)
        if (newParentId != null) {
            val parent = all.firstOrNull { it.id == newParentId.value }
            require(parent != null && parent.trashedAt == null) { "Unknown parent folder" }
            require(newParentId.value !in subtree) { "A folder can't move into itself" }
            val height = heightOf(id.value, all)
            require(depthOf(parent.id, all) + 1 + height < Limits.MAX_FOLDER_DEPTH) {
                "Folders can nest ${Limits.MAX_FOLDER_DEPTH} levels deep"
            }
        }
        dao.setFolderParent(id.value, newParentId?.value, dao.maxFolderSortIndex(newParentId?.value) + SORT_STEP, clock.now())
    }

    override suspend fun folderPath(id: FileFolderId): List<FileFolder> {
        val all = dao.allFolders()
        val byId = all.associateBy { it.id }
        val chain = ArrayList<FileFolderEntity>()
        var cur = byId[id.value]
        while (cur != null && chain.size <= Limits.MAX_FOLDER_DEPTH) {
            chain += cur
            cur = cur.parentId?.let(byId::get)
        }
        chain.reverse()
        return chain.mapIndexed { depth, f -> f.toModel(depth, 0) }
    }

    // ---------------------------------------------------------------- files

    override fun observeFiles(folderId: FileFolderId?, sort: FileSort): Flow<List<LibraryFile>> =
        dao.observeLiveFilesIn(folderId?.value).map { rows -> rows.map { it.asExternalModel() }.sortedWith(sort.comparator()) }
            .flowOn(Dispatchers.Default)

    override fun observeAllFiles(sort: FileSort, categories: Set<FileCategory>?): Flow<List<LibraryFile>> =
        dao.observeLiveFiles().map { rows ->
            rows.asSequence().map { it.asExternalModel() }
                .filter { categories == null || it.category in categories }
                .sortedWith(sort.comparator()).toList()
        }.flowOn(Dispatchers.Default)

    override fun observeFile(id: FileId): Flow<LibraryFile?> = dao.observeFile(id.value).map { it?.asExternalModel() }

    override suspend fun getFile(id: FileId): LibraryFile? = dao.file(id.value)?.asExternalModel()

    override suspend fun getFiles(ids: List<FileId>): List<LibraryFile> =
        ids.map(FileId::value).distinct().chunked(CHUNK).flatMap { dao.files(it) }.map { it.asExternalModel() }

    override fun search(query: String): Flow<List<LibraryFile>> {
        val fts = toFtsQuery(query.take(Limits.MAX_SEARCH_QUERY_LENGTH))
        if (fts.isEmpty()) return flowOf(emptyList())
        return dao.search(fts).map { rows -> rows.map { it.asExternalModel() }.sortedWith(FileSort(FileSortField.Name, true).comparator()) }
            .flowOn(Dispatchers.Default)
    }

    override fun observeSummary(): Flow<FilesSummary> = dao.observeSummary().map {
        FilesSummary(it.fileCount, it.totalBytes, it.trashedCount, it.trashedBytes)
    }

    // ---------------------------------------------------------------- import

    override suspend fun importFromUri(
        uri: Uri,
        folderId: FileFolderId?,
        displayName: String?,
        sourceApp: String?,
        onDuplicate: DuplicatePolicy,
        onProgress: (copied: Long, total: Long?) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name: String? = displayName
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (name == null && n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0 }
                }
            }
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        importStream(
            open = { resolver.openInputStream(uri) ?: throw FileNotFoundException("Can't open $uri") },
            displayName = name ?: uri.lastPathSegment.orEmpty(),
            folderId = folderId,
            declaredMime = mime,
            declaredSize = size,
            sourceApp = sourceApp,
            onDuplicate = onDuplicate,
            onProgress = onProgress,
        )
    }

    override suspend fun importStream(
        open: () -> InputStream,
        displayName: String,
        folderId: FileFolderId?,
        declaredMime: String?,
        declaredSize: Long?,
        sourceApp: String?,
        onDuplicate: DuplicatePolicy,
        onProgress: (copied: Long, total: Long?) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        requireLiveFolder(folderId)
        val staged = blobs.stage(open(), declaredSize, onProgress)
        try {
            val rawName = FileNames.sanitize(displayName)
            val sniff = MimeSniffer.sniff(staged.head, FileNames.extensionOf(rawName), declaredMime)
            val name = FileNames.withExtension(rawName, sniff.suggestedExt)
            val pending = PendingImport(
                folderId = folderId, displayName = name, ext = FileNames.extensionOf(name), mimeType = sniff.mimeType,
                category = MimeSniffer.categoryOf(sniff.mimeType, FileNames.extensionOf(name)), sizeBytes = staged.sizeBytes,
                sha256 = staged.sha256, sourceApp = sourceApp,
            )
            commitLock.withLock {
                // Commit first in every case: when a duplicate exists its blob is already there (or this heals it).
                blobs.commit(staged)
                val dup = dao.liveDuplicate(folderId?.value, staged.sha256)
                when {
                    dup != null && onDuplicate == DuplicatePolicy.Ask -> ImportResult.Duplicate(dup.asExternalModel(), pending)
                    dup != null && onDuplicate == DuplicatePolicy.Skip -> ImportResult.Skipped(dup.asExternalModel())
                    else -> ImportResult.Imported(insertLocked(pending, uniqueName = dup != null))
                }
            }
        } catch (t: Throwable) {
            blobs.discard(staged)
            throw t
        }
    }

    override suspend fun resolveDuplicate(pending: PendingImport, keepBoth: Boolean): LibraryFile? {
        if (!keepBoth) return null
        return withContext(Dispatchers.IO) {
            requireLiveFolder(pending.folderId)
            commitLock.withLock {
                if (!blobs.exists(pending.sha256)) throw FileNotFoundException("The file's content is no longer stored")
                insertLocked(pending, uniqueName = true)
            }
        }
    }

    /** Inserts the row for a committed blob; on failure removes the blob again if nothing else uses it. */
    private suspend fun insertLocked(p: PendingImport, uniqueName: Boolean): LibraryFile {
        val name = if (uniqueName) FileNames.unique(p.displayName, dao.liveNamesIn(p.folderId?.value)) else p.displayName
        val now = clock.now()
        val row = LibraryFileEntity(
            id = UUID.randomUUID().toString(), folderId = p.folderId?.value, displayName = name, ext = FileNames.extensionOf(name),
            mimeType = p.mimeType, category = p.category, sizeBytes = p.sizeBytes, sha256 = p.sha256, sourceApp = p.sourceApp,
            createdAt = now, modifiedAt = now, sortIndex = dao.maxFileSortIndex(p.folderId?.value) + SORT_STEP,
        )
        try {
            dao.insertFile(row)
        } catch (t: Throwable) {
            if (dao.countBySha(p.sha256) == 0) blobs.delete(p.sha256)
            throw t
        }
        return row.asExternalModel()
    }

    private suspend fun requireLiveFolder(folderId: FileFolderId?) {
        if (folderId == null) return
        val f = dao.folder(folderId.value)
        require(f != null && f.trashedAt == null) { "Unknown folder" }
    }

    // ---------------------------------------------------------------- edit

    override suspend fun rename(id: FileId, name: String): String {
        val current = dao.file(id.value) ?: throw FileNotFoundException("No such file")
        val clean = FileNames.withExtension(FileNames.sanitize(name, fallback = FileNames.baseName(current.displayName)), current.ext)
        dao.renameFile(id.value, clean, FileNames.extensionOf(clean), clock.now())
        return clean
    }

    override suspend fun moveFiles(ids: List<FileId>, folderId: FileFolderId?) {
        if (ids.isEmpty()) return
        requireLiveFolder(folderId)
        val now = clock.now()
        ids.map(FileId::value).chunked(CHUNK).forEach { dao.moveFiles(it, folderId?.value, now) }
    }

    override suspend fun setFavorite(ids: List<FileId>, favorite: Boolean) =
        ids.map(FileId::value).chunked(CHUNK).forEach { dao.setFavorite(it, favorite) }

    override suspend fun updateMetadata(id: FileId, width: Int?, height: Int?, durationMs: Long?, pageCount: Int?) =
        dao.updateMetadata(id.value, width, height, durationMs, pageCount)

    // ---------------------------------------------------------------- recycle bin

    override fun observeTrash(): Flow<FilesTrash> =
        combine(dao.observeAllFolders(), dao.observeTrashedFiles(), dao.observeFolderFileCounts()) { folders, files, _ ->
            val byId = folders.associateBy { it.id }
            // The top of each trashed subtree: its parent is live, gone, or was trashed at another time.
            val roots = folders.filter { f ->
                f.trashedAt != null && f.parentId?.let(byId::get)?.trashedAt != f.trashedAt
            }.sortedByDescending { it.trashedAt }
            val trashedFolderStamp = folders.filter { it.trashedAt != null }.associate { it.id to it.trashedAt }
            // Files trashed with their folder are shown inside it; the rest are loose.
            val loose = files.filter { file -> file.folderId?.let(trashedFolderStamp::get) != file.trashedAt }
            FilesTrash(
                folders = roots.map { it.toModel(0, files.count { f -> f.folderId == it.id }) },
                files = loose.map { it.asExternalModel() },
            )
        }.flowOn(Dispatchers.Default)

    override suspend fun trash(fileIds: List<FileId>, folderIds: List<FileFolderId>) {
        if (fileIds.isEmpty() && folderIds.isEmpty()) return
        val subtree = if (folderIds.isEmpty()) emptyList() else subtreeOf(folderIds.map(FileFolderId::value), dao.allFolders()).toList()
        dao.trash(fileIds.map(FileId::value), subtree, clock.now())
    }

    override suspend fun restore(fileIds: List<FileId>, folderIds: List<FileFolderId>) {
        if (folderIds.isNotEmpty()) {
            val all = dao.allFolders()
            for (id in folderIds) {
                val root = all.firstOrNull { it.id == id.value } ?: continue
                val stamp = root.trashedAt ?: continue
                dao.restoreFolderSubtree(root.id, subtreeOf(listOf(root.id), all).toList(), stamp)
            }
        }
        if (fileIds.isNotEmpty()) fileIds.map(FileId::value).chunked(CHUNK).forEach { dao.restoreFilesToLiveFolders(it) }
    }

    override suspend fun deletePermanently(fileIds: List<FileId>, folderIds: List<FileFolderId>) =
        deleteRaw(fileIds.map(FileId::value), folderIds.map(FileFolderId::value))

    override suspend fun emptyTrash() = deleteRaw(dao.trashedFileIds(), dao.trashedFolderIds())

    override suspend fun purgeExpiredTrash() {
        val cutoff = clock.now() - TimeUnit.DAYS.toMillis(Limits.TRASH_RETENTION_DAYS.toLong())
        deleteRaw(dao.trashedFileIdsBefore(cutoff), dao.trashedFolderIdsBefore(cutoff))
    }

    private suspend fun deleteRaw(fileIds: List<String>, folderIds: List<String>) {
        if (fileIds.isEmpty() && folderIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            commitLock.withLock {
                val subtree = if (folderIds.isEmpty()) emptyList() else subtreeOf(folderIds, dao.allFolders()).toList()
                val shas = dao.deleteForever(fileIds, subtree)
                for (sha in shas) if (dao.countBySha(sha) == 0) blobs.delete(sha)
            }
        }
    }

    // ---------------------------------------------------------------- sharing, backlinks

    override fun contentUri(file: LibraryFile): Uri = LibraryProviderContract.uriFor(context, file.id, file.displayName)

    override fun observeUsage(id: FileId): Flow<List<FileUsage>> = dao.observeUsage(id.value).map { rows ->
        rows.groupBy { it.noteId }.map { (noteId, refs) ->
            val first = refs.first()
            FileUsage(NoteId(noteId), first.title, first.isLocked, first.trashedAt != null, refs.mapTo(HashSet()) { it.kind })
        }.sortedWith(compareBy(Collator.getInstance()) { it.noteTitle })
    }

    override suspend fun usageCount(ids: List<FileId>): Int =
        if (ids.isEmpty()) 0 else dao.usageCount(ids.map(FileId::value))

    /**
     * Replaces the backlinks of [noteId] (phase F-3 calls this on every note save, like the search index).
     * [refs] maps a file to how often the note uses it, per kind.
     */
    suspend fun replaceNoteRefs(noteId: NoteId, refs: Map<FileId, Map<FileRefKind, Int>>) {
        val rows = refs.flatMap { (file, kinds) ->
            kinds.filterValues { it > 0 }.map { (kind, count) -> FileRefEntity(noteId.value, file.value, kind, count) }
        }
        dao.replaceRefs(noteId.value, rows)
    }

    // ---------------------------------------------------------------- provider, maintenance

    /** [LibraryFileLookup] for the content provider: live files whose content is stored. */
    override fun find(fileId: String): ProvidedFile? {
        val row = runBlocking(Dispatchers.IO) { dao.file(fileId) } ?: return null
        if (row.trashedAt != null || !blobs.exists(row.sha256)) return null
        return ProvidedFile(row.displayName, row.mimeType, row.sizeBytes, blobs.file(row.sha256))
    }

    /**
     * Start-up clean-up: temp copies of interrupted imports, blobs no row references (an import killed between the
     * rename and the row), and the Recycle bin's expired items.
     */
    suspend fun runMaintenance() = withContext(Dispatchers.IO) {
        val tmp = blobs.sweepTmp()
        val orphans = commitLock.withLock { blobs.sweepOrphans(dao.allShas().toHashSet()) }
        runCatching { purgeExpiredTrash() }.onFailure { log.log(Level.WARNING, "Files trash purge failed", it) }
        if (tmp + orphans > 0) log.info("Files maintenance: removed $tmp temp copies, $orphans orphaned blobs")
    }

    // ---------------------------------------------------------------- helpers

    private fun FileFolderEntity.toModel(depth: Int, fileCount: Int) = FileFolder(
        id = FileFolderId(id), parentId = parentId?.let(::FileFolderId), name = name, color = color, sortIndex = sortIndex,
        depth = depth, fileCount = fileCount, createdAt = createdAt, modifiedAt = modifiedAt, trashedAt = trashedAt,
    )

    internal companion object {
        /** Gap between neighbours' sortIndex (custom order, full version, renumbers when gaps run out). */
        const val SORT_STEP = 1024L
        const val CHUNK = 500
        private val log: Logger = Logger.getLogger(DefaultFilesRepository::class.java.name)

        /** [rootIds] and every folder below them. */
        fun subtreeOf(rootIds: List<String>, all: List<FileFolderEntity>): Set<String> {
            val children = all.groupBy { it.parentId }
            val out = LinkedHashSet<String>()
            val stack = ArrayDeque(rootIds)
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (out.add(id)) children[id]?.forEach { stack.addLast(it.id) }
            }
            return out
        }

        fun depthOf(id: String, all: List<FileFolderEntity>): Int {
            val byId = all.associateBy { it.id }
            var depth = 0
            var cur = byId[id]?.parentId
            while (cur != null && depth <= Limits.MAX_FOLDER_DEPTH) {
                depth++
                cur = byId[cur]?.parentId
            }
            return depth
        }

        /** Levels below [id] (0 for a folder without subfolders). */
        fun heightOf(id: String, all: List<FileFolderEntity>): Int {
            val children = all.groupBy { it.parentId }
            fun h(x: String, guard: Int): Int =
                if (guard > Limits.MAX_FOLDER_DEPTH) 0 else children[x].orEmpty().maxOfOrNull { 1 + h(it.id, guard + 1) } ?: 0
            return h(id, 0)
        }

        /**
         * Live folders depth-first (siblings by sortIndex, then name) with their depth. A folder whose parent is not
         * live (should not happen) is shown at the top level rather than hidden.
         */
        fun treeOrder(live: List<FileFolderEntity>): List<Pair<FileFolderEntity, Int>> {
            val ids = live.mapTo(HashSet()) { it.id }
            val collator = Collator.getInstance()
            val order = compareBy<FileFolderEntity> { it.sortIndex }.thenBy(collator) { it.name }
            val children = live.groupBy { if (it.parentId != null && it.parentId in ids) it.parentId else null }
            val out = ArrayList<Pair<FileFolderEntity, Int>>(live.size)
            fun visit(parent: String?, depth: Int) {
                for (f in children[parent].orEmpty().sortedWith(order)) {
                    out += f to depth
                    if (depth < Limits.MAX_FOLDER_DEPTH) visit(f.id, depth + 1)
                }
            }
            visit(null, 0)
            return out
        }
    }
}

/** Orders files by [FileSort]; ties are broken by name, then id, so the order is stable. */
internal fun FileSort.comparator(): Comparator<LibraryFile> {
    val collator = Collator.getInstance()
    val byName = compareBy(collator) { f: LibraryFile -> f.displayName }
    val byField: Comparator<LibraryFile> = when (field) {
        FileSortField.Name -> byName
        FileSortField.DateAdded -> compareBy { it.createdAt }
        FileSortField.Size -> compareBy { it.sizeBytes }
        FileSortField.Type -> compareBy<LibraryFile> { it.ext }.thenBy { it.mimeType }
    }
    val directed = if (ascending) byField else byField.reversed()
    return directed.then(byName).thenBy { it.id.value }
}
