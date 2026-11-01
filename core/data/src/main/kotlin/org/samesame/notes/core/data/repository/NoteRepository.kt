package org.samesame.notes.core.data.repository

import androidx.ink.strokes.Stroke
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.samesame.notes.core.document.NoteDocument as NoteDocumentV1View
import org.samesame.notes.core.document.NoteSnapshot
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.FolderId
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteFilter
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.model.SortOrder

interface NoteRepository {
    fun observeNotes(filter: NoteFilter, sort: SortOrder): Flow<List<Note>>
    fun observeNote(id: NoteId): Flow<Note?>

    /**
     * Renders the note's first page to a PNG for the home-card previews (text, ink, images and objects),
     * downsampled to about [targetWidthPx] wide. [darkTheme] applies the composer's dark-page colour
     * adjustment when the app setting and the note's `style.adjustForDarkMode` both allow it; otherwise
     * the render is the same light page as before. Returns null for missing or locked notes and when
     * rendering fails; never throws.
     */
    suspend fun renderPreview(id: NoteId, targetWidthPx: Int = 384, darkTheme: Boolean = false): ByteArray?


    /** Drawer badges (all notes, folder-root notes) without loading the catalog. */
    fun observeNoteCounts(): Flow<Pair<Int, Int>> =
        observeNotes(NoteFilter.All, SortOrder()).map { all -> all.size to all.count { it.folderId == null } }
    fun search(query: String): Flow<List<Note>>

    /**
     * Creates an empty note. Page style, layout, template and background come from "Style of new notes"
     * ([org.samesame.notes.core.model.AppSettings.newNote]); an explicit [pageMode] overrides the page style only.
     */
    suspend fun createNote(folderId: FolderId?, pageMode: PageMode? = null): NoteId

    /**
     * True exactly once for a note created by [createNote] in this process (the FAB note): the composer opens it
     * in edit mode. Every other open of the same note is an existing note and opens in reading mode.
     */
    fun takeNewNote(id: NoteId): Boolean = false

    /**
     * Opens the note's bundle (migrating a v1 bundle to v2 on first open, sweeping orphan page
     * files) and marks it opened. The title is taken from the catalog.
     */
    suspend fun openContent(id: NoteId): NoteDocument

    /** Throws [org.samesame.notes.core.document.NoteLockedException] from the content calls until [openLockedNote] ran. */
    suspend fun loadPage(id: NoteId, pageId: String): PageContent

    /**
     * Writes image/media bytes into the note's bundle (`media/<sha256>.<ext>`) and returns the stored
     * path for an `ImageObject.media`. Content-addressed, so the same bytes reuse one file.
     */
    suspend fun saveMedia(id: NoteId, bytes: ByteArray, extension: String): String

    /** Like [saveMedia] for a file of any size (voice recordings), streamed so it never sits in memory. */
    suspend fun saveMediaFile(id: NoteId, file: java.io.File, extension: String): String = saveMedia(id, file.readBytes(), extension)

    /** The file behind a `media/<name>` path of the open note (audio playback needs a file), or null when missing. */
    suspend fun mediaFile(id: NoteId, path: String): java.io.File? = null

    /** Bytes of a `media/<name>` path in the note's bundle, or null when missing. */
    suspend fun loadMedia(id: NoteId, path: String): ByteArray?

    /**
     * Persists a snapshot: dirty page files, then `document.json` (commit point), then the
     * catalog (title, preview, modifiedAt, FTS plain text; a locked note keeps those out of the catalog). Throws on failure; callers keep their
     * dirty flags and retry.
     */
    suspend fun saveContent(id: NoteId, snapshot: NoteSnapshot)

    /**
     * Packs the note's working copy into its canonical `.ssnote` file (atomic). Call on editor
     * close / app stop and about every 30 s while dirty. With [close] the working copy is deleted
     * after a successful pack (only when the editor has closed the note).
     */
    suspend fun flushToContainer(id: NoteId, close: Boolean = false)

    /** Writes the note as a `.ssnote` ZIP to [out] (flushes first). */
    suspend fun exportNote(id: NoteId, out: java.io.OutputStream)

    /**
     * "Save as file": writes the note in [format] to [out] and returns what was written (extension,
     * MIME type) for naming the file. SDOCX throws [UnsupportedOperationException] until its exporter
     * is registered with `NoteExporter.register`.
     */
    suspend fun exportNote(
        id: NoteId,
        format: org.samesame.notes.core.export.ExportFormat,
        options: org.samesame.notes.core.export.ExportOptions = org.samesame.notes.core.export.ExportOptions(),
        out: java.io.OutputStream,
    ): org.samesame.notes.core.export.ExportTarget

    /** Imports a `.ssnote` stream as a new catalog entry (new id on collision). Throws IOException if invalid. */
    suspend fun importNote(input: java.io.InputStream, folderId: FolderId? = null): NoteId

    /**
     * Imports a `.sdocx` stream as a new note: maps the document, stores media, keeps the original
     * archive in the bundle's side-channel for lossless re-export (spec §12.5/§14), and returns the
     * new id. Legacy `.sdoc` and corrupt files throw [SdocxImportException].
     */
    suspend fun importSdocx(input: java.io.InputStream, folderId: FolderId? = null): NoteId

    /**
     * "Import PDF": creates one paged note whose pages are the pages of [pdfFiles] (in order). Every page keeps its own
     * size and orientation (format v6 `PageSpec.size`); it is rendered to an image stored in the bundle and used as the
     * page background, so it can be written on, and the original PDF is stored too so export can reuse the original page
     * content. The note is titled [title] (the first file's name by default). Throws
     * [org.samesame.notes.core.data.pdf.PdfImportException] for protected, corrupt or empty files (never anything else).
     */
    suspend fun importPdf(pdfFiles: List<java.io.File>, title: String, folderId: FolderId? = null): NoteId

    /**
     * "Insert PDF" into an open note: stores [pdfFile] in the note's bundle and renders every page of it, each in its own
     * shape, as a PNG in the bundle. Returns the new pages in order (background, source page and size); the caller inserts
     * them. Throws [org.samesame.notes.core.data.pdf.PdfImportException] like [importPdf].
     */
    suspend fun renderPdfPages(id: NoteId, pdfFile: java.io.File): List<org.samesame.notes.core.data.pdf.ImportedPdfPage>

    @Deprecated("Use openContent (WP-H)")
    suspend fun openDocument(id: NoteId): NoteDocumentV1View
    @Deprecated("Use saveContent (WP-H)")
    suspend fun saveDocument(document: NoteDocumentV1View)
    @Deprecated("Use loadPage (WP-H)")
    suspend fun loadInk(id: NoteId, pageId: String): List<Stroke>
    @Deprecated("Use saveContent (WP-H)")
    suspend fun saveInk(id: NoteId, pageId: String, strokes: List<Stroke>)
    suspend fun updateTitle(id: NoteId, title: String)

    /** Copies the note (text, ink, folder) into a new note. Returns null if [id] is unknown. */
    suspend fun duplicateNote(id: NoteId): NoteId?

    suspend fun setFavorite(ids: List<NoteId>, favorite: Boolean)
    suspend fun moveToFolder(ids: List<NoteId>, folderId: FolderId?)
    suspend fun moveToTrash(ids: List<NoteId>)
    suspend fun restoreFromTrash(ids: List<NoteId>)
    suspend fun deleteForever(ids: List<NoteId>)

    /**
     * Deletes the note permanently (catalog row, container, working copy, search row) when it holds no user
     * content: blank title and body, no ink or objects on any page, no voice recordings, no PDF/image-template
     * pages and no imported `.sdocx` side-channel. This is what happens when the editor closes a note the user
     * never wrote into (or emptied later), so no blank entry stays in the list or the Recycle bin. Locked notes
     * are never touched. Returns true when the note was deleted.
     */
    suspend fun deleteIfEmpty(id: NoteId): Boolean = false

    /**
     * Locks the notes: encrypts each container with a fresh note key wrapped by the [token]'s master key,
     * hides title and preview in the catalog and drops them from the search index. Already locked notes
     * are skipped. Locked notes are excluded from sharing, "Save as file" and search.
     */
    suspend fun lockNotes(ids: List<NoteId>, token: org.samesame.notes.core.security.LockToken)

    /** Removes the lock (decrypts the containers, restores title, preview and search entry). */
    suspend fun unlockNotes(ids: List<NoteId>, token: org.samesame.notes.core.security.LockToken)

    /**
     * Decrypts a locked note into its working copy so [openContent] works. The key lives in memory until
     * [flushToContainer] with `close = true`. A wrong or foreign token throws
     * [org.samesame.notes.core.security.TamperedDataException].
     */
    suspend fun openLockedNote(id: NoteId, token: org.samesame.notes.core.security.LockToken)

    /** Permanently deletes notes that have been in Trash longer than the retention period. */
    suspend fun purgeExpiredTrash()
}
