package org.samesame.notes.core.data.repository

import androidx.ink.strokes.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.data.Clock
import org.samesame.notes.core.database.dao.NoteDao
import org.samesame.notes.core.database.dao.SearchDao
import org.samesame.notes.core.database.entity.NoteEntity
import org.samesame.notes.core.database.entity.NoteSearchEntity
import org.samesame.notes.core.database.entity.asExternalModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import org.samesame.notes.core.document.NoteDocument as NoteDocumentV1View
import org.samesame.notes.core.document.NoteDocumentStore
import org.samesame.notes.core.document.NoteSnapshot
import org.samesame.notes.core.document.PageSpec as PageSpecV1View
import org.samesame.notes.core.ink.page.InkElement
import org.samesame.notes.core.ink.page.ElementId
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.TemplateRef
import org.samesame.notes.core.model.richtext.RichText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.io.readBytesLimited
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.PageTemplate
import org.samesame.notes.core.model.SortDirection
import org.samesame.notes.core.model.SortField
import org.samesame.notes.core.model.SortOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal class DefaultNoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val searchDao: SearchDao,
    private val documents: NoteDocumentStore,
    private val clock: Clock,
    private val exporter: org.samesame.notes.core.export.NoteExporter,
    private val appSettings: AppSettingsRepository,
    private val previewFailures: PreviewFailureStore,
    private val maintenance: org.samesame.notes.core.database.DatabaseMaintenance,
    private val shareCache: org.samesame.notes.core.data.transfer.ShareCache,
    /** Rendered first pages on disk; null in tests that don't need one. */
    private val previewCache: PreviewDiskCache? = null,
) : NoteRepository {

    override fun observeNotes(filter: NoteFilter, sort: SortOrder): Flow<List<Note>> {
        val rows = when (filter) {
            NoteFilter.All -> noteDao.observeAll()
            NoteFilter.Uncategorized -> noteDao.observeUncategorized()
            NoteFilter.Favorites -> noteDao.observeFavorites()
            NoteFilter.Locked -> noteDao.observeLocked()
            NoteFilter.Trash -> return noteDao.observeTrash().map { it.map(NoteEntity::asExternalModel) }
            is NoteFilter.InFolder ->
                if (filter.includeSubfolders) noteDao.observeInFolderTree(filter.folderId.value)
                else noteDao.observeInFolder(filter.folderId.value)
            is NoteFilter.WithTag -> noteDao.observeWithTag(normalizeTag(filter.tag))
        }
        // Sorting a full catalog is not free: keep it off the main thread that collects the UI state.
        return rows.map { entities -> entities.map(NoteEntity::asExternalModel).sortedWith(sort.comparator()) }
            .flowOn(Dispatchers.Default)
    }

    override fun observeNoteCounts(): Flow<Pair<Int, Int>> =
        noteDao.observeCounts().map { it.allCount to it.rootCount }.flowOn(Dispatchers.Default)

    override fun observeNote(id: NoteId): Flow<Note?> = noteDao.observe(id.value).map { it?.asExternalModel() }

    override suspend fun renderPreview(id: NoteId, targetWidthPx: Int, darkTheme: Boolean): ByteArray? =
        lockFor(id.value).withLock {
            val row = noteDao.get(id.value) ?: return@withLock null
            if (row.isLocked || documents.isLocked(id.value)) return@withLock null
            // Same rule as the composer: app theme + app setting + the note's own adjustForDarkMode.
            val adjust = darkTheme && appSettings.settings.first().adjustColourInDarkMode
            // The render only depends on the revision, the width and whether dark colours may be adjusted.
            val variant = if (adjust) "adjusted" else "plain"
            previewCache?.get(id.value, row.modifiedAt, targetWidthPx, variant)?.let { return@withLock it }
            if (previewFailures.isBlocked(id.value, row.modifiedAt)) return@withLock null
            previewFailures.begin(id.value, row.modifiedAt)
            val png = try {
                // Read straight from the container: nothing is unpacked to disk just to draw a card.
                documents.read(id.value, titleFromDb = row.title) { reader ->
                    val source = object : org.samesame.notes.core.export.ExportSource {
                        override suspend fun document(): NoteDocument {
                            val doc = reader.document()
                            return if (doc.title != row.title) doc.copy(title = row.title) else doc
                        }
                        override suspend fun page(pageId: String): PageContent = reader.page(pageId)
                        override suspend fun media(path: String): ByteArray? = reader.media(path)
                    }
                    org.samesame.notes.core.export.render.renderFirstPagePreview(source, targetWidthPx, adjust)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null // unreadable note: the card keeps its text preview (never throws, like the render itself)
            } finally {
                previewFailures.end(id.value) // only a process death mid-render leaves the marker behind
            }
            if (png != null) previewCache?.put(id.value, row.modifiedAt, targetWidthPx, variant, png)
            png
        }

    override fun search(query: String): Flow<List<Note>> {
        val ftsQuery = toFtsQuery(query.take(Limits.MAX_SEARCH_QUERY_LENGTH))
        if (ftsQuery.isEmpty()) return flowOf(emptyList())
        return searchDao.search(ftsQuery).map { it.map(NoteEntity::asExternalModel) }
    }

    override suspend fun createNote(folderId: FolderId?, pageMode: PageMode?): NoteId {
        val id = UUID.randomUUID().toString()
        val now = clock.now()
        val settings = appSettings.settings.first()
        val style = settings.newNote
        val mode = pageMode ?: style.pageMode
        documents.create(
            id, mode, ratio = style.ratio, template = style.template,
            backgroundColor = style.backgroundColor, adjustForDarkMode = settings.adjustColourInDarkMode,
        )
        NewNoteRegistry.noteCreated(id)
        noteDao.upsert(
            NoteEntity(
                id = id,
                title = "",
                previewText = "",
                folderId = folderId?.value,
                isFavorite = false,
                isLocked = false,
                pageMode = mode,
                backgroundColor = style.backgroundColor ?: 0,
                createdAt = now,
                modifiedAt = now,
                lastOpenedAt = now,
                trashedAt = null,
            ),
        )
        return NoteId(id)
    }

    override fun takeNewNote(id: NoteId): Boolean = NewNoteRegistry.take(id.value)

    override suspend fun duplicateNote(id: NoteId): NoteId? {
        val source = noteDao.get(id.value) ?: return null
        if (source.isLocked) return null // locked notes are not duplicated (unlock first)
        val newId = UUID.randomUUID().toString()
        val now = clock.now()
        documents.duplicate(id.value, newId, source.title)
        noteDao.upsert(
            source.copy(
                id = newId,
                isFavorite = false,
                createdAt = now,
                modifiedAt = now,
                lastOpenedAt = now,
                trashedAt = null,
                isDirty = true,
            ),
        )
        reindex(newId)
        return NoteId(newId)
    }

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(id: String) = locks.getOrPut(id) { Mutex() }

    override suspend fun openContent(id: NoteId): NoteDocument = lockFor(id.value).withLock {
        val row = noteDao.get(id.value)
        noteDao.markOpened(id.value, clock.now())
        val doc = documents.readDocument(id.value, titleFromDb = row?.title.orEmpty())
        documents.sweepOrphans(id.value, doc)
        // The catalog is authoritative for titles; a locked note is read from its decrypted document (its title is
        // restored to the catalog when the note is opened).
        if (row != null && !row.isLocked && doc.title != row.title) doc.copy(title = row.title) else doc
    }

    override suspend fun flushToContainer(id: NoteId, close: Boolean) =
        lockFor(id.value).withLock {
            // A note deleted meanwhile (e.g. an empty composer that just closed) must not be packed again.
            if (noteDao.get(id.value) == null) return@withLock
            documents.flush(id.value, close)
        }

    override suspend fun exportNote(id: NoteId, out: java.io.OutputStream) =
        lockFor(id.value).withLock { documents.export(id.value, out) }

    override suspend fun exportNote(
        id: NoteId,
        format: org.samesame.notes.core.export.ExportFormat,
        options: org.samesame.notes.core.export.ExportOptions,
        out: java.io.OutputStream,
    ): org.samesame.notes.core.export.ExportTarget {
        // Locked notes are never exported (the container also refuses).
        if (documents.isLocked(id.value)) throw org.samesame.notes.core.document.NoteLockedException(id.value)
        if (format == org.samesame.notes.core.export.ExportFormat.SSNOTE) {
            exportNote(id, out)
            return org.samesame.notes.core.export.expectedTarget(format, options, false, 1)
        }
        if (format == org.samesame.notes.core.export.ExportFormat.SDOCX) {
            val sideChannel = runCatching { documents.readExtras(id.value) }.getOrDefault(emptyMap())
            val original = runCatching { org.samesame.notes.core.sdocx.SdocxSideChannel.decode(sideChannel) }.getOrNull()
            val target = org.samesame.notes.core.export.ExportTarget(SdocxExport.EXTENSION, SdocxExport.MIME)
            if (original != null) {
                val document = currentDocument(id.value)
                // Only recordings made in SameSame (no sdocx source id) need their audio shipped.
                val audio = document.recordings.filter { it.sourceFileId == null }
                    .mapNotNull { r -> documents.readMedia(id.value, r.media)?.let { r.id to it } }.toMap()
                SdocxExport.exportImported(
                    original, document, out,
                    sideChannel[org.samesame.notes.core.sdocx.SdocxSideChannel.RAW_ARCHIVE_PATH],
                    audio,
                )
            } else {
                val source = object : org.samesame.notes.core.export.ExportSource {
                    override suspend fun document(): NoteDocument = currentDocument(id.value)
                    override suspend fun page(pageId: String): PageContent = documents.readPage(id.value, pageId)
                    override suspend fun media(path: String): ByteArray? = documents.readMedia(id.value, path)
                }
                SdocxExport.exportNative(source, out)
            }
            return target
        }
        val source = object : org.samesame.notes.core.export.ExportSource {
            override suspend fun document(): NoteDocument {
                val row = noteDao.get(id.value)
                if (row?.isLocked == true) throw org.samesame.notes.core.document.NoteLockedException(id.value)
                val doc = documents.readDocument(id.value, titleFromDb = row?.title.orEmpty())
                return if (row != null && doc.title != row.title) doc.copy(title = row.title) else doc
            }
            override suspend fun page(pageId: String): PageContent = documents.readPage(id.value, pageId)
            override suspend fun media(path: String): ByteArray? = documents.readMedia(id.value, path)
        }
        return exporter.export(source, format, options, out)
    }

    override suspend fun importSdocx(input: java.io.InputStream, folderId: FolderId?): NoteId {
        val bytes = try {
            input.readBytesLimited(Limits.MAX_SDOCX_BYTES, ".sdocx file")
        } catch (e: org.samesame.notes.core.model.io.InputTooLargeException) {
            throw SdocxImportException(SdocxImportError.INVALID, e)
        } catch (e: Exception) {
            throw SdocxImportException(SdocxImportError.IO, e)
        }
        val archive = try {
            org.samesame.notes.core.sdocx.SdocxReader().read(bytes)
        } catch (e: org.samesame.notes.core.sdocx.SdocxLegacyFormatException) {
            throw SdocxImportException(SdocxImportError.LEGACY_UNSUPPORTED, e)
        } catch (e: Exception) {
            throw SdocxImportException(sdocxImportErrorOf(e), e)
        } catch (e: OutOfMemoryError) {
            throw SdocxImportException(SdocxImportError.INVALID, e) // hostile counts or sizes: a bad file, not a crash
        } catch (e: StackOverflowError) {
            throw SdocxImportException(SdocxImportError.INVALID, e)
        }

        val id = UUID.randomUUID().toString()
        val bundle = org.samesame.notes.core.sdocx.map.SdocxMapper.toImportBundle(archive, id)
        try {
            documents.writeSdocxImport(bundle, archive, bytes)
        } catch (e: Exception) {
            throw SdocxImportException(SdocxImportError.IO, e)
        }

        val plain = bundle.document.body.plainText()
        val now = clock.now()
        val created = archive.endTag.createdTime.takeIf { it > 0 }?.div(1000) ?: now
        noteDao.upsert(
            NoteEntity(
                id = id,
                title = bundle.title,
                previewText = plain.take(PREVIEW_LENGTH),
                folderId = folderId?.value,
                isFavorite = false,
                // The .sdocx lock flag is only a flag (spec: not encrypted); the imported note is stored unlocked, so the
                // catalog must say so. The flag survives in the side-channel for lossless re-export.
                isLocked = false,
                pageMode = bundle.document.layout.mode,
                backgroundColor = 0,
                createdAt = created,
                modifiedAt = now,
                lastOpenedAt = now,
                trashedAt = null,
                isDirty = true,
            ),
        )
        searchDao.replace(NoteSearchEntity(noteId = id, title = bundle.title, body = plain, inkText = ""))
        return NoteId(id)
    }

    override suspend fun importPdf(pdfFiles: List<java.io.File>, title: String, folderId: FolderId?): NoteId {
        val id = UUID.randomUUID().toString()
        val sources = ArrayList<org.samesame.notes.core.data.pdf.PdfPageSource>()
        var created = false
        try {
            pdfFiles.forEach { sources += org.samesame.notes.core.data.pdf.AndroidPdfPageSource.open(it) }
            org.samesame.notes.core.data.pdf.PdfNoteBuilder.requirePages(sources.sumOf { it.pageCount })
            // The bundle must exist before media can be written; the real document replaces this placeholder at the end.
            documents.createFromDocument(org.samesame.notes.core.data.pdf.PdfNoteBuilder.document(id, title, emptyList()))
            created = true
            // Keep the original PDF bytes: export draws the original page content from them.
            val sourceMedia = pdfFiles.map { documents.writeMediaFile(id, it, "pdf") }
            val fitToFirst = appSettings.settings.first().newNote.pdfPageSize == org.samesame.notes.core.model.PdfPageSize.FitToNote
            val pages = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                // Pages are rendered and written one at a time so a long PDF never sits in memory.
                org.samesame.notes.core.data.pdf.PdfNoteBuilder.renderAll(sources, sourceMedia, fitToFirst) { png ->
                    kotlinx.coroutines.runBlocking { documents.writeMedia(id, png, "png") }
                }
            }
            return finishPdfImport(id, org.samesame.notes.core.data.pdf.PdfNoteBuilder.document(id, title, pages), folderId)
        } catch (e: Throwable) {
            if (created) runCatching { documents.delete(id) }
            throw pdfFailure(e)
        } finally {
            sources.forEach { runCatching { it.close() } }
        }
    }

    override suspend fun renderPdfPages(id: NoteId, pdfFile: java.io.File): List<org.samesame.notes.core.data.pdf.ImportedPdfPage> {
        try {
            val source = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                org.samesame.notes.core.data.pdf.AndroidPdfPageSource.open(pdfFile)
            }
            return source.use { pages ->
                org.samesame.notes.core.data.pdf.PdfNoteBuilder.requirePages(pages.pageCount)
                val pdfMedia = saveMediaFile(id, pdfFile, "pdf")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    // One page at a time: each PNG is written to the bundle before the next page is rendered.
                    org.samesame.notes.core.data.pdf.PdfNoteBuilder.renderAll(listOf(pages), listOf(pdfMedia)) { png ->
                        kotlinx.coroutines.runBlocking { saveMedia(id, png, "png") }
                    }
                }
            }
        } catch (e: Throwable) {
            throw pdfFailure(e)
        }
    }

    /** Anything a broken PDF can throw while importing becomes a [org.samesame.notes.core.data.pdf.PdfImportException]. */
    private fun pdfFailure(e: Throwable): Throwable = when (e) {
        is kotlinx.coroutines.CancellationException, is org.samesame.notes.core.data.pdf.PdfImportException -> e
        is Exception, is OutOfMemoryError, is StackOverflowError ->
            org.samesame.notes.core.data.pdf.PdfImportException(org.samesame.notes.core.data.pdf.PdfImportError.Corrupt, e)
        else -> e
    }

    private suspend fun finishPdfImport(id: String, document: NoteDocument, folderId: FolderId?): NoteId {
        documents.writeDocument(document)
        documents.flush(id)
        val now = clock.now()
        noteDao.upsert(
            NoteEntity(
                id = id, title = document.title, previewText = "", folderId = folderId?.value, isFavorite = false, isLocked = false,
                pageMode = document.layout.mode, backgroundColor = 0, createdAt = now, modifiedAt = now, lastOpenedAt = now,
                trashedAt = null, isDirty = true,
            ),
        )
        searchDao.replace(NoteSearchEntity(noteId = id, title = document.title, body = "", inkText = ""))
        return NoteId(id)
    }

    override suspend fun importNote(input: java.io.InputStream, folderId: FolderId?): NoteId {
        val doc = documents.import(input)
        val plain = doc.body.plainText()
        val now = clock.now()
        noteDao.upsert(
            NoteEntity(
                id = doc.id,
                title = doc.title,
                previewText = plain.take(PREVIEW_LENGTH),
                folderId = folderId?.value,
                isFavorite = false,
                isLocked = false,
                pageMode = doc.layout.mode,
                backgroundColor = 0,
                createdAt = now,
                modifiedAt = now,
                lastOpenedAt = now,
                trashedAt = null,
                isDirty = true,
            ),
        )
        searchDao.replace(NoteSearchEntity(noteId = doc.id, title = doc.title, body = plain, inkText = ""))
        return NoteId(doc.id)
    }

    override suspend fun loadPage(id: NoteId, pageId: String): PageContent = documents.readPage(id.value, pageId)

    override suspend fun saveMedia(id: NoteId, bytes: ByteArray, extension: String): String =
        lockFor(id.value).withLock { documents.writeMedia(id.value, bytes, extension) }

    override suspend fun saveMediaFile(id: NoteId, file: java.io.File, extension: String): String =
        lockFor(id.value).withLock { documents.writeMediaFile(id.value, file, extension) }

    override suspend fun mediaFile(id: NoteId, path: String): java.io.File? = documents.mediaFile(id.value, path)

    override suspend fun loadMedia(id: NoteId, path: String): ByteArray? = documents.readMedia(id.value, path)

    override suspend fun saveContent(id: NoteId, snapshot: NoteSnapshot) = lockFor(id.value).withLock {
        // A queued autosave of a note that was deleted meanwhile (empty note closed, real deletion) must not
        // write the bundle back: the catalog row is the first thing removed, so it is the tombstone here.
        val row = noteDao.get(id.value) ?: return@withLock
        documents.commit(id.value, snapshot)
        val doc = snapshot.document
        val plain = doc.body.plainText()
        val now = clock.now()
        if (row.isLocked) {
            // Owner decision: a locked note's title stays in the catalog and is visible; only the body preview and the
            // searchable body are removed (title search still finds it).
            if (row.title != doc.title) noteDao.updateTitle(id.value, doc.title, now)
            noteDao.updatePreview(id.value, "", now)
            searchDao.replace(NoteSearchEntity(noteId = id.value, title = doc.title, body = "", inkText = ""))
            return@withLock
        }
        if (row.title != doc.title) noteDao.updateTitle(id.value, doc.title, now)
        noteDao.updatePreview(id.value, plain.take(PREVIEW_LENGTH), now)
        searchDao.replace(
            NoteSearchEntity(noteId = id.value, title = doc.title, body = plain, inkText = ""),
        )
    }

    /** The bundle document with the catalog's title (the catalog is authoritative for renames). */
    private suspend fun currentDocument(id: String): NoteDocument {
        val doc = lockFor(id).withLock { documents.readDocument(id) }
        return noteDao.get(id)?.takeIf { !it.isLocked }?.let { doc.copy(title = it.title) } ?: doc
    }

    @Deprecated("Use openContent (WP-H)")
    override suspend fun openDocument(id: NoteId): NoteDocumentV1View {
        val doc = openContent(id)
        return NoteDocumentV1View(
            id = doc.id,
            pageMode = doc.layout.mode,
            pageRatio = doc.layout.ratio,
            body = doc.body.plainText(),
            pages = doc.pages.map {
                PageSpecV1View(
                    id = it.id,
                    template = (it.template as? TemplateRef.Builtin)?.template ?: PageTemplate.Blank,
                    backgroundColor = it.backgroundColor,
                    isBookmarked = it.isBookmarked,
                )
            },
        )
    }

    // NOTE(WP-D): the adapter only carries plain text, so saving through it drops body styling.
    @Deprecated("Use saveContent (WP-H)")
    override suspend fun saveDocument(document: NoteDocumentV1View) {
        val current = currentDocument(document.id)
        saveContent(NoteId(document.id), NoteSnapshot(current.copy(body = RichText.of(document.body))))
        flushToContainer(NoteId(document.id)) // NOTE(WP-D): the old editor never flushes itself
    }

    @Deprecated("Use loadPage (WP-H)")
    override suspend fun loadInk(id: NoteId, pageId: String): List<Stroke> =
        documents.readPage(id.value, pageId).elements.filterIsInstance<InkElement>().map { it.stroke }

    // NOTE(WP-D): keeps existing element ids (matched by position) and floating objects.
    @Deprecated("Use saveContent (WP-H)")
    override suspend fun saveInk(id: NoteId, pageId: String, strokes: List<Stroke>) {
        val doc = currentDocument(id.value)
        val existing = documents.readPage(id.value, pageId)
        val oldInk = existing.elements.filterIsInstance<InkElement>()
        val objects = existing.elements.filter { it !is InkElement }
        val ink = strokes.mapIndexed { i, stroke ->
            InkElement(oldInk.getOrNull(i)?.id ?: ElementId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)), stroke)
        }
        saveContent(NoteId(id.value), NoteSnapshot(doc, listOf(PageContent(pageId, ink + objects, existing.version + 1))))
        flushToContainer(id)
    }

    override suspend fun updateTitle(id: NoteId, title: String) {
        if (noteDao.get(id.value)?.isLocked == true) {
            lockFor(id.value).withLock {
                runCatching { documents.readDocument(id.value).let { documents.writeDocument(it.copy(title = title)) } }
                // The catalog keeps the visible title of a locked note (owner decision); the body stays out of search.
                noteDao.updateTitle(id.value, title, clock.now())
                searchDao.replace(NoteSearchEntity(noteId = id.value, title = title, body = "", inkText = ""))
            }
            return
        }
        noteDao.updateTitle(id.value, title, clock.now())
        reindex(id.value, title = title)
        // Keep the self-contained bundle's title in step (best effort; the catalog is authoritative).
        lockFor(id.value).withLock {
            runCatching { documents.readDocument(id.value, title).let { documents.writeDocument(it.copy(title = title)) } }
        }
    }

    override suspend fun setFavorite(ids: List<NoteId>, favorite: Boolean) =
        noteDao.setFavorite(ids.map(NoteId::value), favorite)

    override suspend fun moveToFolder(ids: List<NoteId>, folderId: FolderId?) =
        noteDao.moveToFolder(ids.map(NoteId::value), folderId?.value)

    override suspend fun moveToTrash(ids: List<NoteId>) = noteDao.setTrashed(ids.map(NoteId::value), clock.now())

    override suspend fun restoreFromTrash(ids: List<NoteId>) = noteDao.setTrashed(ids.map(NoteId::value), null)

    override suspend fun deleteForever(ids: List<NoteId>) {
        val raw = ids.map(NoteId::value)
        noteDao.delete(raw)
        raw.forEach { id ->
            searchDao.deleteForNote(id)
            documents.delete(id)
            previewFailures.forget(id)
            previewCache?.forget(id)
        }
    }

    override suspend fun deleteIfEmpty(id: NoteId): Boolean = lockFor(id.value).withLock {
        val row = noteDao.get(id.value) ?: return@withLock false
        if (row.isLocked || documents.isLocked(id.value)) return@withLock false
        val doc = try {
            documents.readDocument(id.value, titleFromDb = row.title)
        } catch (e: java.io.IOException) {
            return@withLock false // unreadable bundle: never destroy data we cannot inspect
        }
        if (row.title.isNotBlank() || doc.title.isNotBlank()) return@withLock false
        // Imported notes keep their lossless re-export side-channel; an imported note is never "blank".
        val imported = runCatching { documents.readExtras(id.value).isNotEmpty() }.getOrDefault(false)
        if (imported) return@withLock false
        val counts = doc.pages.map { documents.readPage(id.value, it.id).elements.size }
        if (!isNoteEmpty(doc, counts)) return@withLock false
        noteDao.delete(listOf(id.value))
        searchDao.deleteForNote(id.value)
        documents.delete(id.value)
        previewFailures.forget(id.value)
        NewNoteRegistry.forget(id.value)
        true
    }

    override suspend fun purgeExpiredTrash() {
        val cutoff = clock.now() - TimeUnit.DAYS.toMillis(Limits.TRASH_RETENTION_DAYS.toLong())
        deleteForever(noteDao.trashedBefore(cutoff).map(::NoteId))
    }

    override suspend fun lockNotes(ids: List<NoteId>, token: org.samesame.notes.core.security.LockToken) {
        val master = token.masterKey()
        runCatching { maintenance.secureDelete() } // the blanked title/preview and search row are zeroed on disk
        try {
            forEachConcurrently(ids) { id ->
                lockFor(id.value).withLock {
                    val row = noteDao.get(id.value) ?: return@withLock
                    if (row.isLocked) return@withLock
                    // The encrypted document carries the body; the catalog keeps the visible title (owner decision 2026-11-02).
                    org.samesame.notes.core.data.traced("lock:lockNote") { documents.lockNote(id.value, master, title = row.title) }
                    previewCache?.forget(id.value) // no rendered page of a locked note stays on disk
                    noteDao.setLocked(id.value, true, title = row.title, preview = "")
                    // Only the title stays searchable, never the body.
                    searchDao.replace(NoteSearchEntity(noteId = id.value, title = row.title, body = "", inkText = ""))
                }
            }
        } finally {
            master.fill(0)
            // FTS4 keeps deleted terms in its segments until they merge, and the WAL keeps old pages: purge both.
            runCatching { maintenance.purgeDeletedText() }
            // Earlier share/save exports of these notes would stay readable in the cache.
            runCatching { shareCache.sweep() }
        }
    }

    override suspend fun unlockNotes(ids: List<NoteId>, token: org.samesame.notes.core.security.LockToken) {
        val master = token.masterKey()
        try {
            forEachConcurrently(ids) { id -> lockFor(id.value).withLock {
                val row = noteDao.get(id.value) ?: return@withLock
                if (!row.isLocked) return@withLock
                val doc = org.samesame.notes.core.data.traced("lock:unlockNote") { documents.unlockNote(id.value, master) }
                val plain = doc.body.plainText()
                noteDao.setLocked(id.value, false, title = doc.title, preview = plain.take(PREVIEW_LENGTH))
                // A marker left by a render that died while the note was unlocked must not block the fresh preview.
                previewFailures.forget(id.value)
                searchDao.replace(NoteSearchEntity(noteId = id.value, title = doc.title, body = plain, inkText = ""))
            } }
        } finally {
            master.fill(0)
        }
    }

    /**
     * Runs [block] for every id, up to [LOCK_PARALLELISM] at a time (the reference locks several notes in parallel too);
     * the first failure cancels the rest and is rethrown, like the sequential loop did.
     */
    private suspend fun forEachConcurrently(ids: List<NoteId>, block: suspend (NoteId) -> Unit) {
        if (ids.size <= 1) {
            ids.forEach { block(it) }
            return
        }
        val slots = kotlinx.coroutines.sync.Semaphore(LOCK_PARALLELISM)
        kotlinx.coroutines.coroutineScope {
            ids.distinct().forEach { id -> launch { slots.withPermit { block(id) } } }
        }
    }

    override suspend fun openLockedNote(id: NoteId, token: org.samesame.notes.core.security.LockToken) =
        lockFor(id.value).withLock {
            val master = token.masterKey()
            try {
                org.samesame.notes.core.data.traced("lock:openLocked") { documents.openLocked(id.value, master) }
                // Notes locked before titles stayed in the catalog have an empty title there: restore it now that the
                // document is decrypted, so the home page can show it without bumping the modified time.
                val row = noteDao.get(id.value)
                if (row != null && row.isLocked && row.title.isBlank()) {
                    val title = runCatching { documents.readDocument(id.value).title }.getOrDefault("")
                    if (title.isNotBlank()) {
                        noteDao.updateTitleOnly(id.value, title)
                        searchDao.replace(NoteSearchEntity(noteId = id.value, title = title, body = "", inkText = ""))
                    }
                }
            } finally {
                master.fill(0)
            }
        }

    /** Rewrites the note's search row, keeping whichever fields aren't passed. */
    private suspend fun reindex(noteId: String, title: String? = null, body: String? = null) {
        val note = noteDao.get(noteId) ?: return
        if (note.isLocked) return
        searchDao.replace(
            NoteSearchEntity(
                noteId = noteId,
                title = title ?: note.title,
                body = body ?: note.previewText,
                inkText = "", // TODO: filled by the handwriting recognition worker.
            ),
        )
    }

    private companion object {
        const val PREVIEW_LENGTH = 300
        const val LOCK_PARALLELISM = 3
    }
}

internal fun normalizeTag(tag: String) = tag.trim().removePrefix("#").lowercase()

/**
 * Turns user input into a safe FTS4 prefix query: `meeting agen` → `"meeting*" "agen*"`. FTS4 takes the `*` inside the
 * quoted phrase (`"agen"*` is FTS5 syntax and matches nothing here, so partial words were never found).
 */
internal fun toFtsQuery(query: String): String = query
    .split(Regex("\\s+"))
    .map { it.replace("\"", "") }
    .filter { it.isNotBlank() }
    .joinToString(" ") { "\"$it*\"" }

/** Orders notes by [SortOrder]; favourites go first when [SortOrder.favoritesFirst] is set. */
internal fun SortOrder.comparator(): Comparator<Note> {
    val byField: Comparator<Note> = when (field) {
        SortField.Title -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortField.DateCreated -> compareBy { it.createdAt }
        SortField.DateModified -> compareBy { it.modifiedAt }
        SortField.LastOpened -> compareBy { it.lastOpenedAt }
    }
    val directed = if (direction == SortDirection.Descending) byField.reversed() else byField
    return if (favoritesFirst) compareByDescending<Note> { it.isFavorite }.then(directed) else directed
}
