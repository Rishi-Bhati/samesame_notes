package org.samesame.notes.core.document

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.samesame.notes.core.document.legacy.NoteDocumentV1
import org.samesame.notes.core.ink.page.PageContent
import org.samesame.notes.core.ink.page.PageLayerCodec
import org.samesame.notes.core.model.PageMode
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.TamperedDataException
import org.samesame.notes.core.model.document.NOTE_DOCUMENT_FORMAT_VERSION
import org.samesame.notes.core.model.document.NoteDocument
import org.samesame.notes.core.model.document.PageLayout
import org.samesame.notes.core.model.document.PageSpec
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.io.InputTooLargeException
import org.samesame.notes.core.model.io.LimitedInputStream
import org.samesame.notes.core.model.io.readBytesLimited
import javax.inject.Inject
import javax.inject.Singleton

internal const val DOCUMENT_FILE = "document.json"
internal const val MANIFEST_FILE = "manifest.json"

/** Bundle directory holding the `.sdocx` round-trip side-channel (spec §12.5). */
const val EXTRAS_DIR = "sdocx"
const val NOTE_CONTAINER_EXTENSION = "ssnote"
private const val FORMAT_NAME = "samesame-note"
private const val TMP_SUFFIX = ".tmp"

/**
 * Container version (manifest `containerVersion`; absent = 1). v1: plain ZIP. v2: adds the optional
 * locked layout: plaintext manifest (`locked: true`, `wrappedKey`) + `payload.enc`, which seals an inner
 * ZIP of `document.json`, `ink/`, `media/` and `sdocx/` with the note's AES-GCM key. v1 containers are
 * read as before and rewritten as v2 on the next pack.
 */
const val CONTAINER_VERSION = 2
internal const val PAYLOAD_FILE = "payload.enc"

/** The note is locked and has not been opened with its password / biometrics in this process. */
class NoteLockedException(val noteId: String) : IOException("Note $noteId is locked")

/**
 * Sealing a locked note's container failed (e.g. storage full). Its plaintext working copy was **kept** on disk: the
 * container may not hold the latest edits, so the next open with the password adopts it. The editor shows a message.
 */
class LockedNoteSaveException(val noteId: String, cause: Throwable? = null) :
    IOException("Could not save locked note $noteId", cause)

/**
 * Notes on disk. // NOTE(WP-D): per the owner's scope change, each note is ONE file.
 *
 * Canonical: `<notesRoot>/<noteId>.ssnote`, a ZIP with `manifest.json` first, then `document.json`,
 * `ink/<pageId>.ink`, `media/<hash>.<ext>`. Ink and media are STORED (repacking = fast copy), JSON is
 * deflated.
 *
 * Working copy: `<workingRoot>/<noteId>/` with the same layout (minus manifest). All reads and
 * autosaves go to the working copy with the commit protocol (pages first, `document.json` last).
 * [flush] packs it back into the container (temp file + fsync + atomic rename). Opening unpacks the
 * container unless a working dir exists that is not older than the container (crash recovery).
 * Legacy v1 directory bundles at `<notesRoot>/<noteId>/` are adopted as the working copy, migrated
 * to v2 and packed on first open.
 *
 * Callers serialize access per note (the repository holds a per-note mutex).
 */
@Singleton
class NoteDocumentStore(private val notesRoot: File, private val workingRoot: File) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.filesDir, "notes"), File(context.noBackupFilesDir, "working"))

    /** Data keys of locked notes that are currently open (decrypted working copy exists). Memory only. */
    private val openKeys = ConcurrentHashMap<String, ByteArray>()

    /** Cached "is this container locked" per note id; absent = unknown. */
    private val lockedCache = ConcurrentHashMap<String, Boolean>()

    init {
        wipeStaleLockedWorkingCopies()
    }

    /**
     * A working copy left by a previous process is deleted only when it cannot hold newer edits. For a locked note that
     * means its `document.json` is not newer than the container: a newer one is kept and adopted by [openLocked] (a failed
     * seal or a kill before the final pack must cost nothing). Extraction staging (`.unpack`) is always disposable.
     */
    private fun wipeStaleLockedWorkingCopies() {
        workingRoot.listFiles()?.forEach { dir ->
            val id = dir.name.removeSuffix(".unpack")
            if (!isSafeId(id)) return@forEach
            when {
                dir.name.endsWith(".unpack") -> dir.deleteRecursively()
                isLockedContainer(id) && !hasNewerWorkingCopy(id, dir) -> dir.deleteRecursively()
            }
        }
    }

    /** The working copy holds edits the container does not: its document is newer than the container. */
    private fun hasNewerWorkingCopy(noteId: String, dir: File = workingDir(noteId)): Boolean {
        val doc = File(dir, DOCUMENT_FILE)
        val container = containerFile(noteId)
        return doc.exists() && (!container.exists() || doc.lastModified() > container.lastModified())
    }

    /** Whether the note's container is locked (reads the plaintext manifest; false when there is none). */
    fun isLocked(noteId: String): Boolean = isLockedContainer(noteId)

    private fun isLockedContainer(noteId: String): Boolean = lockedCache.getOrPut(checkId(noteId)) {
        val c = containerFile(noteId)
        if (!c.exists()) false else runCatching { ZipFile(c).use { readManifestInfo(it).locked } }.getOrDefault(false)
    }

    /** Test seam: invoked after the page files are written and before `document.json` is replaced. */
    internal var beforeDocumentCommit: (() -> Unit)? = null

    /** Test seam: invoked before the packed temp is renamed over the container (simulates a failed seal). */
    internal var beforePackRename: (() -> Unit)? = null


    /**
     * Notes whose working copy was written in this process since its last pack. A [flush] of a note that is neither in
     * here nor newer on disk than its container has nothing to pack (closing a big note that was only read no longer
     * rewrites and fsyncs the whole container, and a locked one is not re-encrypted).
     */
    private val unpacked = ConcurrentHashMap.newKeySet<String>()


    suspend fun create(
        noteId: String,
        pageMode: PageMode,
        title: String = "",
        ratio: org.samesame.notes.core.model.PageRatio = PageLayout().ratio,
        template: org.samesame.notes.core.model.PageTemplate = org.samesame.notes.core.model.PageTemplate.Blank,
        backgroundColor: Long? = null,
        adjustForDarkMode: Boolean = true,
    ): NoteDocument =
        withContext(Dispatchers.IO) {
            val document = NoteDocument(
                id = noteId,
                title = title,
                layout = PageLayout(mode = pageMode, ratio = ratio),
                style = org.samesame.notes.core.model.document.NoteStyle(backgroundColor, adjustForDarkMode),
                pages = listOf(
                    PageSpec(
                        id = UUID.randomUUID().toString(),
                        template = org.samesame.notes.core.model.document.TemplateRef.Builtin(template),
                    ),
                ),
            )
            val dir = workingDir(noteId)
            dir.deleteRecursively()
            writeFileAtomically(File(dir, DOCUMENT_FILE)) { it.write(document.toJson().encodeToByteArray()) }
            pack(noteId)
            document
        }

    /**
     * Reads `document.json` from the working copy (unpacking/adopting first if needed), migrating a v1
     * bundle to v2 and packing it. [titleFromDb] is only used by that migration. Throws [IOException]
     * for versions newer than this app.
     */
    suspend fun readDocument(noteId: String, titleFromDb: String = ""): NoteDocument = withContext(Dispatchers.IO) {
        val dir = ensureWorking(noteId)
        val text = File(dir, DOCUMENT_FILE).readText()
        val version = documentVersion(text)
        when {
            version > NOTE_DOCUMENT_FORMAT_VERSION ->
                throw IOException("Note format version $version is newer than this app supports")
            version == 1 -> NoteDocumentMigrations.migrateBundleV1(dir, NoteDocumentV1.fromJson(text), titleFromDb)
                .also { pack(noteId) }
            else -> parseDocument(text, version)
        }
    }

    private fun documentVersion(text: String): Int =
        (Json.parseToJsonElement(text) as JsonObject)["formatVersion"]?.jsonPrimitive?.int ?: 1

    /** Reads a document of the current version or of v2-v5 (upgraded in memory); anything else is refused. */
    private fun parseDocument(text: String, version: Int): NoteDocument = when (version) {
        NOTE_DOCUMENT_FORMAT_VERSION -> NoteDocument.fromJson(text)
        5 -> NoteDocumentMigrations.v5ToV6(NoteDocument.fromJson(text))
        4 -> NoteDocumentMigrations.v5ToV6(NoteDocumentMigrations.v4ToV5(NoteDocument.fromJson(text)))
        3 -> NoteDocumentMigrations.v5ToV6(NoteDocumentMigrations.v4ToV5(NoteDocumentMigrations.v3ToV4(NoteDocument.fromJson(text))))
        2 -> NoteDocumentMigrations.v5ToV6(
            NoteDocumentMigrations.v4ToV5(NoteDocumentMigrations.v3ToV4(NoteDocumentMigrations.v2ToV3(NoteDocument.fromJson(text)))),
        )
        else -> throw IOException("Unsupported note format version $version")
    }

    suspend fun writeDocument(document: NoteDocument) = withContext(Dispatchers.IO) {
        val dir = ensureWorking(document.id)
        writeFileAtomically(File(dir, DOCUMENT_FILE)) { it.write(document.toJson().encodeToByteArray()) }
        unpacked += document.id
        packIfLocked(document.id)
    }

    /** Empty content when the page has no file yet. */
    suspend fun readPage(noteId: String, pageId: String): PageContent = withContext(Dispatchers.IO) {
        val file = pageFile(ensureWorking(noteId), pageId)
        val elements = if (!file.exists()) emptyList() else file.inputStream().buffered().use { PageLayerCodec.decode(it) }
        PageContent(pageId, elements, 0)
    }

    /** Read-only view of one note for [read]. */
    interface NoteReader {
        fun document(): NoteDocument
        /** Empty content when the page has no file. */
        fun page(pageId: String): PageContent
        /** Bytes of `media/<name>`, or null when missing / not a safe media path. */
        fun media(path: String): ByteArray?
    }

    /**
     * Runs [block] with read-only access to the note (the home previews): the working copy when one exists that is
     * not older than the container, otherwise the packed container itself, so nothing is unpacked to disk just to
     * look at a note (a big note's images were written out and left behind for every preview). A legacy or v1 note
     * takes the normal path (it is adopted / migrated once). Throws [NoteLockedException] for a locked note.
     */
    suspend fun <T> read(noteId: String, titleFromDb: String = "", block: suspend (NoteReader) -> T): T = withContext(Dispatchers.IO) {
        if (isLockedContainer(noteId)) throw NoteLockedException(noteId)
        val container = containerFile(noteId)
        val workingDoc = File(workingDir(noteId), DOCUMENT_FILE)
        val useWorking = workingDoc.exists() && (!container.exists() || workingDoc.lastModified() >= container.lastModified())
        val zip = if (useWorking || !container.exists()) null else ZipFile(container)
        try {
            val docText = zip?.let { zf ->
                if (readManifestInfo(zf).locked) throw NoteLockedException(noteId)
                zf.getEntry(DOCUMENT_FILE)?.let { readEntryLimited(zf, it).decodeToString() }
            }
            val version = docText?.let(::documentVersion)
            if (zip == null || docText == null || version == null || version < 2) {
                // Working copy (or a note that needs adopting / migrating first).
                val doc = readDocument(noteId, titleFromDb)
                val dir = workingDir(noteId)
                return@withContext block(object : NoteReader {
                    override fun document() = doc
                    override fun page(pageId: String): PageContent {
                        val file = pageFile(dir, pageId)
                        val elements = if (!file.exists()) emptyList() else file.inputStream().buffered().use { PageLayerCodec.decode(it) }
                        return PageContent(pageId, elements, 0)
                    }
                    override fun media(path: String): ByteArray? {
                        val parts = path.split('/')
                        if (parts.size != 2 || parts[0] != "media" || !isSafeId(parts[1])) return null
                        return File(File(dir, "media"), parts[1]).takeIf { it.isFile }?.readBytes()
                    }
                })
            }
            val doc = parseDocument(docText, version)
            block(object : NoteReader {
                override fun document() = doc
                override fun page(pageId: String): PageContent {
                    require(isSafeId(pageId)) { "Bad page id" }
                    val entry = zip.getEntry("ink/$pageId.ink") ?: return PageContent(pageId, emptyList(), 0)
                    val bytes = readEntryLimited(zip, entry)
                    return PageContent(pageId, PageLayerCodec.decode(bytes.inputStream()), 0)
                }
                override fun media(path: String): ByteArray? {
                    val parts = path.split('/')
                    if (parts.size != 2 || parts[0] != "media" || !isSafeId(parts[1])) return null
                    val entry = zip.getEntry(path) ?: return null
                    return readEntryLimited(zip, entry)
                }
            })
        } finally {
            zip?.close()
        }
    }

    /** Bytes of `media/<name>` from the working copy, or null when missing / not a safe media path. */
    suspend fun readMedia(noteId: String, path: String): ByteArray? = withContext(Dispatchers.IO) {
        val parts = path.split('/')
        if (parts.size != 2 || parts[0] != "media" || !isSafeId(parts[1])) return@withContext null
        File(File(ensureWorking(noteId), "media"), parts[1]).takeIf { it.isFile }?.readBytes()
    }

    suspend fun writePage(noteId: String, content: PageContent) = withContext(Dispatchers.IO) {
        val file = pageFile(ensureWorking(noteId), content.pageId)
        writeFileAtomically(file) { PageLayerCodec.encode(content, it) }
        unpacked += noteId
        packIfLocked(noteId)
    }

    /**
     * Writes a complete foreign document plus its `sdocx/` side-channel as a new working copy, then
     * packs it. Used by the `.sdocx` importer, which already has the full document; unlike [create]
     * it adds no page or title of its own. The whole `sdocx/` directory is replaced.
     */
    suspend fun createFromDocument(
        document: NoteDocument,
        extras: Map<String, ByteArray> = emptyMap(),
    ): NoteDocument = withContext(Dispatchers.IO) {
        val dir = workingDir(document.id)
        dir.deleteRecursively()
        writeFileAtomically(File(dir, DOCUMENT_FILE)) { it.write(document.toJson().encodeToByteArray()) }
        File(dir, EXTRAS_DIR).deleteRecursively()
        for ((path, bytes) in extras) {
            require(isAllowedEntry(path)) { "Bad side-channel path: $path" }
            writeFileAtomically(File(dir, path)) { it.write(bytes) }
        }
        pack(document.id)
        document
    }

    /** Writes the `sdocx/` side-channel of an existing note (replacing any previous one). */
    suspend fun writeExtras(noteId: String, entries: Map<String, ByteArray>) = withContext(Dispatchers.IO) {
        val dir = ensureWorking(noteId)
        unpacked += noteId
        File(dir, EXTRAS_DIR).deleteRecursively()
        for ((path, bytes) in entries) {
            require(isAllowedEntry(path)) { "Bad side-channel path: $path" }
            writeFileAtomically(File(dir, path)) { it.write(bytes) }
        }
    }

    /** Reads every file under `sdocx/`, keyed by bundle-relative path. Empty when absent. */
    suspend fun readExtras(noteId: String): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        val root = File(ensureWorking(noteId), EXTRAS_DIR)
        if (!root.isDirectory) return@withContext emptyMap()
        root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) }
            .associate { "$EXTRAS_DIR/${it.relativeTo(root).invariantSeparatorsPath}" to it.readBytes() }
    }

    /**
     * Writes [bytes] to `media/<sha256>.<ext>` in the working copy and returns that path (content
     * addressed, so re-inserting the same image reuses the file). Written before the commit that
     * references it (docs 5.1); an orphan after a crash is swept on the next open.
     */
    suspend fun writeMedia(noteId: String, bytes: ByteArray, extension: String): String = withContext(Dispatchers.IO) {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val name = digest.joinToString("") { "%02x".format(it) } + "." + ext
        writeFileAtomically(File(File(ensureWorking(noteId), "media"), name)) { it.write(bytes) }
        unpacked += noteId
        "media/$name"
    }

    /**
     * Copies [source] (any size, streamed) to `media/<sha256>.<ext>` in the working copy and returns that path; the
     * same content-addressed name as [writeMedia]. For long recordings that must not be held in memory.
     */
    suspend fun writeMediaFile(noteId: String, source: File, extension: String): String = withContext(Dispatchers.IO) {
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val name = md.digest().joinToString("") { "%02x".format(it) } + "." + ext
        val target = File(File(ensureWorking(noteId), "media"), name)
        if (!target.isFile || target.length() != source.length()) {
            writeFileAtomically(target) { out -> source.inputStream().buffered().use { it.copyTo(out) } }
            unpacked += noteId
        }
        "media/$name"
    }

    /** The working-copy file behind `media/<name>` (for players that need a file), or null when missing / not a safe media path. */
    suspend fun mediaFile(noteId: String, path: String): File? = withContext(Dispatchers.IO) {
        val parts = path.split('/')
        if (parts.size != 2 || parts[0] != "media" || !isSafeId(parts[1])) return@withContext null
        File(File(ensureWorking(noteId), "media"), parts[1]).takeIf { it.isFile }
    }

    /**
     * Commit order (docs 5.1): (1) changed page files, (2) media (written by their own callers
     * before this), (3) `document.json` last as the commit point, (4) delete files of pages no
     * longer listed. Only the working copy is touched; [flush] packs it. A crash can leave orphan
     * page files, never a document referencing a missing page; [sweepOrphans] on open cleans up.
     */
    suspend fun commit(noteId: String, snapshot: NoteSnapshot) = withContext(Dispatchers.IO) {
        val doc = snapshot.document
        require(doc.id == noteId) { "Snapshot is for another note" }
        val dir = ensureWorking(noteId)
        for (page in snapshot.dirtyPages) {
            writeFileAtomically(pageFile(dir, page.pageId)) { PageLayerCodec.encode(page, it) }
        }
        beforeDocumentCommit?.invoke()
        writeFileAtomically(File(dir, DOCUMENT_FILE)) { it.write(doc.toJson().encodeToByteArray()) }
        unpacked += noteId
        sweepOrphansIn(dir, doc)
        // A locked note's decrypted working copy does not survive the process: every autosave is sealed into the
        // container right away (temp file + fsync + atomic rename), so no acknowledged edit can be lost.
        packIfLocked(noteId)
    }

    /** Deletes page files not listed in [document], and stale temp files. */
    suspend fun sweepOrphans(noteId: String, document: NoteDocument) = withContext(Dispatchers.IO) {
        if (sweepOrphansIn(ensureWorking(noteId), document)) unpacked += noteId
    }

    /** True when a page file the container may still hold was removed. */
    private fun sweepOrphansIn(dir: File, document: NoteDocument): Boolean {
        val listed = document.pages.mapTo(HashSet()) { "${it.id}.ink" }
        var removed = false
        File(dir, "ink").listFiles()?.forEach { f ->
            if (f.name.endsWith(TMP_SUFFIX)) f.delete()
            else if (f.name.endsWith(".ink") && f.name !in listed && f.delete()) removed = true
        }
        File(dir, DOCUMENT_FILE + TMP_SUFFIX).delete()
        return removed
    }

    /**
     * Packs the working copy into the container. When [close] is true the working dir is deleted afterwards, but only
     * once the container is verified to hold it. For a locked note a failed seal keeps the plaintext working copy (the
     * only copy of the latest edits), drops the key and throws [LockedNoteSaveException] to the caller. No-op when
     * there is no working copy.
     */
    suspend fun flush(noteId: String, close: Boolean = false) = withContext(Dispatchers.IO) {
        val dir = workingDir(noteId)
        if (!File(dir, DOCUMENT_FILE).exists()) return@withContext
        if (close && isLockedContainer(noteId)) {
            try {
                if (!isInSync(noteId)) pack(noteId)
                if (!isInSync(noteId)) throw IOException("Sealed container does not match the working copy")
            } catch (e: CancellationException) {
                openKeys.remove(noteId)?.fill(0)
                throw e
            } catch (e: Throwable) {
                // Never delete the only durable copy: the container may not hold the latest edits.
                log.log(Level.WARNING, "Could not seal locked note $noteId; keeping its working copy", e)
                openKeys.remove(noteId)?.fill(0)
                throw if (e is LockedNoteSaveException) e else LockedNoteSaveException(noteId, e)
            }
            dir.deleteRecursively()
            openKeys.remove(noteId)?.fill(0)
            return@withContext
        }
        if (!isInSync(noteId)) pack(noteId)
        if (close) {
            dir.deleteRecursively()
            openKeys.remove(noteId)?.fill(0)
            }
    }

    /**
     * The container already holds exactly the working copy: nothing was written in this process since the last pack
     * (or unpack), and `document.json` carries the container's timestamp (set by [pack] / [unpack]; a working copy left
     * by a killed process is newer, or the container is missing, and gets packed).
     */
    private fun isInSync(noteId: String): Boolean {
        if (noteId in unpacked) return false
        val container = containerFile(noteId)
        val doc = File(workingDir(noteId), DOCUMENT_FILE)
        return container.exists() && doc.exists() && doc.lastModified() == container.lastModified()
    }

    // ---- locked notes ----------------------------------------------------------------------

    /**
     * Locks an unlocked note: a fresh data key wrapped by [master] encrypts the container's payload; the
     * working copy is removed afterwards. The previous plaintext container is replaced atomically (its old
     * blocks may remain on flash until overwritten; there is no secure erase). [title], when given, is written
     * into the encrypted document (the catalog keeps no title for a locked note).
     *
     * A note without newer edits in its working copy (the usual case: the editor closed it) is re-sealed straight
     * from its container, without unpacking it to disk and reading it back.
     */
    suspend fun lockNote(noteId: String, master: ByteArray, title: String? = null) = withContext(Dispatchers.IO) {
        if (isLockedContainer(noteId)) return@withContext
        val dek = LockCrypto.newKey()
        try {
            val wrapped = LockCrypto.wrapNoteKey(master, noteId, dek)
            if (!lockFromContainer(noteId, wrapped, dek, title)) {
                if (title != null) {
                    val doc = readDocument(noteId, title) // adopts / migrates a legacy bundle first
                    if (doc.title != title) {
                        writeFileAtomically(File(workingDir(noteId), DOCUMENT_FILE)) { it.write(doc.copy(title = title).toJson().encodeToByteArray()) }
                        unpacked += noteId
                    }
                } else {
                    ensureWorking(noteId)
                }
                pack(noteId, newLock = wrapped, dek = dek)
            }
            lockedCache[noteId] = true
        } finally {
            dek.fill(0)
        }
        workingDir(noteId).deleteRecursively()
        openKeys.remove(noteId)?.fill(0)
        unpacked -= noteId
    }

    /**
     * [lockNote] from the packed container: its entries go into the sealed inner ZIP as they are (only `document.json`
     * is rewritten for [title]). False (nothing written) when the working copy holds edits the container lacks, or the
     * container is missing, already locked or of a document version that needs the migration path.
     */
    private fun lockFromContainer(noteId: String, wrappedKey: ByteArray, dek: ByteArray, title: String?): Boolean {
        val container = containerFile(noteId)
        if (!container.exists()) return false
        val workingDoc = File(workingDir(noteId), DOCUMENT_FILE)
        if (workingDoc.exists() && (noteId in unpacked || workingDoc.lastModified() > container.lastModified())) return false
        val sealed = ZipFile(container).use { zf ->
            if (readManifestInfo(zf).locked) return false
            val docEntry = zf.getEntry(DOCUMENT_FILE) ?: return false
            val original = readEntryLimited(zf, docEntry)
            val text = original.decodeToString()
            val version = documentVersion(text)
            if (version < 2 || version > NOTE_DOCUMENT_FORMAT_VERSION) return false
            val docBytes = if (title == null) original else {
                val doc = parseDocument(text, version)
                if (doc.title == title) original else doc.copy(title = title).toJson().encodeToByteArray()
            }
            val budget = InflateBudget(container.length())
            val inner = ExposedByteArrayOutputStream((container.length() + 64 * 1024).coerceAtMost(Int.MAX_VALUE - 1L).toInt())
            ZipOutputStream(inner).use { zos ->
                writeEntry(zos, DOCUMENT_FILE, docBytes, stored = false)
                val entries = zf.entries().toList()
                    .filter { !it.isDirectory && it.name != MANIFEST_FILE && it.name != DOCUMENT_FILE && isAllowedEntry(it.name) }
                    .sortedBy { it.name }
                for (entry in entries) {
                    budget.countEntry()
                    val bytes = zf.getInputStream(entry).use { src ->
                        val limited = budget.limit(src, entry.name)
                        limited.readBytes().also { budget.consume(limited.count) }
                    }
                    writeEntry(zos, entry.name, bytes, stored = entry.method == ZipEntry.STORED)
                }
            }
            LockCrypto.sealPayload(dek, noteId, inner.buffer, inner.size())
        }
        writeContainer(container) { zos ->
            writeEntry(zos, MANIFEST_FILE, manifestBytes(noteId, wrappedKey), stored = false)
            writeEntry(zos, PAYLOAD_FILE, sealed, stored = true)
        }
        return true
    }

    /** Writes [container] through a temp file + fsync + atomic rename. */
    private inline fun writeContainer(container: File, write: (ZipOutputStream) -> Unit) {
        notesRoot.mkdirs()
        val tmp = File(notesRoot, container.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { fos ->
                val bos = BufferedOutputStream(fos)
                val zos = ZipOutputStream(bos)
                write(zos)
                zos.finish()
                bos.flush()
                fos.fd.sync()
            }
            Files.move(tmp.toPath(), container.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(notesRoot)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Unwraps the note key with [master] and unpacks the decrypted working copy (kept until [flush] with
     * `close = true`). Wrong master / modified data throws [TamperedDataException].
     */
    suspend fun openLocked(noteId: String, master: ByteArray) = withContext(Dispatchers.IO) {
        val container = containerFile(noteId)
        if (!container.exists()) throw FileNotFoundException("Note $noteId not found")
        val info = ZipFile(container).use { readManifestInfo(it) }
        if (!info.locked) return@withContext
        val dek = LockCrypto.unwrapNoteKey(master, noteId, info.wrappedKey ?: throw IOException("Locked note has no key"))
        val workingDoc = File(workingDir(noteId), DOCUMENT_FILE)
        val existing = openKeys[noteId]
        if (existing != null && existing.contentEquals(dek) && workingDoc.exists()) {
            dek.fill(0) // already open in this process: keep the working copy (it may hold unsaved edits)
            return@withContext
        }
        openKeys.put(noteId, dek)?.fill(0)
        if (hasNewerWorkingCopy(noteId)) {
            // Unsealed edits from a crash or a failed save: this plaintext copy is the newest state, so never decrypt over
            // it. Seal it right away with the key that is now available; if that fails, keep it and let the editor
            // continue (the next autosave retries and the failure is surfaced there).
            try {
                pack(noteId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.log(Level.WARNING, "Could not seal the recovered working copy of locked note $noteId", e)
            }
            return@withContext
        }
        try {
            unpack(container, noteId)
        } catch (e: Throwable) {
            openKeys.remove(noteId)?.fill(0)
            workingDir(noteId).deleteRecursively()
            throw e
        }
    }

    /**
     * Removes the lock: decrypts and rewrites the container as plain v2, and returns the note's document. A note that
     * is not open in this process is rewritten straight from the decrypted payload (no working copy is unpacked to disk
     * and packed again); an open one is packed from its working copy, which may hold newer edits.
     */
    suspend fun unlockNote(noteId: String, master: ByteArray): NoteDocument = withContext(Dispatchers.IO) {
        if (!isLockedContainer(noteId)) return@withContext readDocument(noteId)
        // A newer plaintext working copy (failed save / crash) is the newest state: adopt and seal it first, otherwise the
        // fast path would decrypt the stale container and drop those edits.
        if (hasNewerWorkingCopy(noteId)) openLocked(noteId, master)
        val open = File(workingDir(noteId), DOCUMENT_FILE).exists() && openKeys.containsKey(noteId)
        val fast = if (open) null else unlockFromContainer(noteId, master)
        val document = fast ?: run {
            if (!open) openLockedBlocking(noteId, master)
            val doc = readDocument(noteId)
            lockedCache[noteId] = false
            try {
                pack(noteId)
            } catch (e: Throwable) {
                lockedCache[noteId] = true
                throw e
            }
            doc
        }
        workingDir(noteId).deleteRecursively()
        openKeys.remove(noteId)?.fill(0)
        unpacked -= noteId
        document
    }

    /**
     * [unlockNote] without a working copy: the inner ZIP's entries are written into a plain container as they are.
     * Null (nothing written) when the payload's document needs the migration path (a v1 document). Wrong [master] or
     * modified data throws [TamperedDataException].
     */
    private fun unlockFromContainer(noteId: String, master: ByteArray): NoteDocument? {
        val container = containerFile(noteId)
        val budget = InflateBudget(container.length())
        val inner = ZipFile(container).use { zf ->
            val info = readManifestInfo(zf)
            val dek = LockCrypto.unwrapNoteKey(master, noteId, info.wrappedKey ?: throw IOException("Locked note has no key"))
            try {
                val payload = zf.getEntry(PAYLOAD_FILE) ?: throw IOException("Locked note has no payload")
                val sealed = zf.getInputStream(payload).use { it.readBytesLimited(container.length(), PAYLOAD_FILE) }
                LockCrypto.openPayload(dek, noteId, sealed)
            } finally {
                dek.fill(0)
            }
        }
        var document: NoteDocument? = null
        try {
            writeContainer(container) { zos ->
                writeEntry(zos, MANIFEST_FILE, manifestBytes(noteId, null), stored = false)
                ZipInputStream(inner.inputStream()).use { zin ->
                    while (true) {
                        val entry = zin.nextEntry ?: break
                        budget.countEntry()
                        if (entry.isDirectory || !isAllowedEntry(entry.name)) continue
                        val limited = budget.limit(zin, entry.name)
                        val bytes = limited.readBytes()
                        budget.consume(limited.count)
                        if (entry.name == DOCUMENT_FILE) {
                            val text = bytes.decodeToString()
                            val version = documentVersion(text)
                            if (version < 2) throw NeedsMigration()
                            document = parseDocument(text, version)
                        }
                        writeEntry(zos, entry.name, bytes, stored = entry.method == ZipEntry.STORED)
                    }
                }
                if (document == null) throw IOException("Container has no document.json")
            }
        } catch (e: NeedsMigration) {
            return null
        }
        lockedCache[noteId] = false
        return document
    }

    private class NeedsMigration : IOException()

    private fun openLockedBlocking(noteId: String, master: ByteArray) {
        val container = containerFile(noteId)
        val info = ZipFile(container).use { readManifestInfo(it) }
        val dek = LockCrypto.unwrapNoteKey(master, noteId, info.wrappedKey ?: throw IOException("Locked note has no key"))
        openKeys.put(noteId, dek)?.fill(0)
        unpack(container, noteId)
    }

    /** Closes a locked note's session without packing (e.g. after a failed open). */
    fun forgetKey(noteId: String) {
        openKeys.remove(noteId)?.fill(0)
    }

    private fun packIfLocked(noteId: String) {
        if (isLockedContainer(noteId)) pack(noteId)
    }

    /** Whether any form of this note (container, working copy, legacy directory) exists. */
    fun exists(noteId: String): Boolean =
        containerFile(noteId).exists() || File(workingDir(noteId), DOCUMENT_FILE).exists() ||
            File(File(notesRoot, checkId(noteId)), DOCUMENT_FILE).exists()

    /** Copies a note under a new id (the container is copied, its manifest and document rewritten). */
    suspend fun duplicate(sourceId: String, newId: String, titleFromDb: String = ""): NoteDocument {
        if (isLockedContainer(sourceId)) throw NoteLockedException(sourceId)
        val document = readDocument(sourceId, titleFromDb) // adopts/migrates legacy and unpacks if needed
        return withContext(Dispatchers.IO) {
            pack(sourceId)
            rewriteContainer(containerFile(sourceId), containerFile(newId), newId)
            document.copy(id = newId)
        }
    }

    /** Streams the packed container (flushing the working copy first). */
    suspend fun export(noteId: String, out: OutputStream) = withContext(Dispatchers.IO) {
        if (isLockedContainer(noteId)) throw NoteLockedException(noteId) // locked notes are never exported
        ensureWorking(noteId)
        pack(noteId)
        containerFile(noteId).inputStream().use { it.copyTo(out) }
        out.flush()
    }

    /**
     * Validates and stores a foreign container under a fresh id (never the one in the file: an id chosen by
     * someone else could collide with, or shadow the staging paths of, an existing note). Every entry is
     * inflated once under [Limits] caps, so a zip bomb is refused here rather than on first open.
     */
    suspend fun import(input: InputStream): NoteDocument = withContext(Dispatchers.IO) {
        notesRoot.mkdirs()
        val staged = File(notesRoot, "import-${UUID.randomUUID()}.$NOTE_CONTAINER_EXTENSION$TMP_SUFFIX")
        try {
            staged.outputStream().use { LimitedInputStream(input, Limits.MAX_IMPORT_BYTES, "Note file", closeInput = false).copyTo(it) }
            val doc = ZipFile(staged).use { zf ->
                val info = readManifestInfo(zf)
                if (info.locked) throw IOException("Locked notes can't be imported: their key lives on the original device")
                checkEntrySizes(zf, staged.length())
                val text = readEntryLimited(zf, zf.getEntry(DOCUMENT_FILE) ?: throw IOException("No document.json")).decodeToString()
                parseDocument(text, documentVersion(text))
            }
            val id = UUID.randomUUID().toString()
            rewriteContainer(staged, containerFile(id), id)
            doc.copy(id = id)
        } finally {
            staged.delete()
        }
    }

    /** Inflates every entry once (discarding the bytes) and fails when an entry or the total exceeds the caps. */
    private fun checkEntrySizes(zf: ZipFile, containerBytes: Long) {
        val budget = InflateBudget(containerBytes)
        val sink = ByteArray(64 * 1024)
        for (entry in zf.entries()) {
            budget.countEntry()
            if (entry.isDirectory) continue
            zf.getInputStream(entry).use { src ->
                val limited = budget.limit(src, entry.name)
                while (limited.read(sink) >= 0) Unit
                budget.consume(limited.count)
            }
        }
    }

    private fun readEntryLimited(zf: ZipFile, entry: ZipEntry): ByteArray =
        zf.getInputStream(entry).use { it.readBytesLimited(entryLimit(entry.name).toLong(), entry.name) }

    suspend fun delete(noteId: String) = withContext(Dispatchers.IO) {
        openKeys.remove(noteId)?.fill(0)
        lockedCache.remove(noteId)
        containerFile(noteId).delete()
        File(notesRoot, noteId + ".$NOTE_CONTAINER_EXTENSION$TMP_SUFFIX").delete()
        workingDir(noteId).deleteRecursively()
        File(notesRoot, checkId(noteId)).deleteRecursively() // legacy directory bundle
    }

    // ---- working copy lifecycle ------------------------------------------------------------

    private fun ensureWorking(noteId: String): File {
        val working = workingDir(noteId)
        val workingDoc = File(working, DOCUMENT_FILE)
        val container = containerFile(noteId)
        File(notesRoot, noteId + ".$NOTE_CONTAINER_EXTENSION$TMP_SUFFIX").delete() // partial pack: ignored
        if (isLockedContainer(noteId)) {
            // A locked note is only reachable after openLocked put its key in memory.
            if (!openKeys.containsKey(noteId) || !workingDoc.exists()) throw NoteLockedException(noteId)
            return working
        }
        if (workingDoc.exists() && (!container.exists() || workingDoc.lastModified() >= container.lastModified())) {
            return working
        }
        if (container.exists()) {
            unpack(container, noteId)
            return working
        }
        val legacy = File(notesRoot, noteId)
        if (File(legacy, DOCUMENT_FILE).exists()) {
            working.deleteRecursively()
            working.parentFile?.mkdirs()
            moveDirectory(legacy, working)
            return working
        }
        throw FileNotFoundException("Note $noteId not found")
    }

    private fun unpack(container: File, noteId: String) {
        val tmpDir = File(workingRoot, "${checkId(noteId)}.unpack")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        var upgrade = false
        try {
            ZipFile(container).use { zf ->
                val info = readManifestInfo(zf)
                upgrade = info.containerVersion < CONTAINER_VERSION
                // ZIP headers are not trusted: each entry and the total are inflated under caps (zip bombs).
                val budget = InflateBudget(container.length())
                if (info.locked) {
                    val dek = openKeys[noteId] ?: throw NoteLockedException(noteId)
                    val payload = zf.getEntry(PAYLOAD_FILE) ?: throw IOException("Locked note has no payload")
                    val sealed = zf.getInputStream(payload).use { it.readBytesLimited(container.length(), PAYLOAD_FILE) }
                    val inner = LockCrypto.openPayload(dek, noteId, sealed)
                    ZipInputStream(inner.inputStream()).use { zin ->
                        while (true) {
                            val entry = zin.nextEntry ?: break
                            budget.countEntry()
                            if (entry.isDirectory || !isAllowedEntry(entry.name)) continue
                            extract(zin, File(tmpDir, entry.name), entry.name, budget)
                        }
                    }
                } else {
                    for (entry in zf.entries()) {
                        budget.countEntry()
                        if (entry.isDirectory || entry.name == MANIFEST_FILE || !isAllowedEntry(entry.name)) continue
                        zf.getInputStream(entry).use { src -> extract(src, File(tmpDir, entry.name), entry.name, budget) }
                    }
                }
            }
            if (!File(tmpDir, DOCUMENT_FILE).exists()) throw IOException("Container has no document.json")
            File(tmpDir, DOCUMENT_FILE).setLastModified(container.lastModified())
            val working = workingDir(noteId)
            working.deleteRecursively()
            if (!tmpDir.renameTo(working)) throw IOException("Could not create working copy")
            // An older container is rewritten in the current layout on the next flush.
            if (upgrade) unpacked += noteId else unpacked -= noteId
        } catch (e: Throwable) {
            tmpDir.deleteRecursively() // a failed extraction must not leave (possibly decrypted) files behind
            throw e
        }
    }

    private fun extract(src: InputStream, out: File, name: String, budget: InflateBudget) {
        out.parentFile?.mkdirs()
        val limited = budget.limit(src, name)
        out.outputStream().use { limited.copyTo(it) }
        budget.consume(limited.count)
    }

    /** Uncompressed bytes and entries still allowed while reading one container. */
    private class InflateBudget(containerBytes: Long) {
        private var left = Limits.maxInflatedBytes(containerBytes)
        private var entries = 0

        fun countEntry() {
            if (++entries > Limits.MAX_ARCHIVE_ENTRIES) throw InputTooLargeException("Too many entries in note container")
        }

        fun limit(src: InputStream, name: String) =
            LimitedInputStream(src, minOf(entryLimit(name).toLong(), left), name, closeInput = false)

        fun consume(bytes: Long) {
            left -= bytes
        }
    }

    /**
     * Packs the working copy. [newLock] (a wrapped note key) turns the result into a locked container using
     * [dek]; otherwise a locked note keeps the wrapped key of its current container and the key in [openKeys].
     */
    private fun pack(noteId: String, newLock: ByteArray? = null, dek: ByteArray? = null) {
        val dir = workingDir(noteId)
        val docFile = File(dir, DOCUMENT_FILE)
        if (!docFile.exists()) throw FileNotFoundException("No working copy for $noteId")
        notesRoot.mkdirs()
        val container = containerFile(noteId)
        val tmp = File(notesRoot, container.name + TMP_SUFFIX)

        var lockKey: ByteArray? = null
        var wrappedKey: ByteArray? = null
        if (newLock != null) {
            lockKey = dek
            wrappedKey = newLock
        } else if (isLockedContainer(noteId)) {
            lockKey = openKeys[noteId] ?: throw NoteLockedException(noteId)
            wrappedKey = ZipFile(container).use { readManifestInfo(it).wrappedKey } ?: throw IOException("Locked note has no key")
        }

        unpacked -= noteId // a write that lands while this pack runs marks the note again
        try {
            FileOutputStream(tmp).use { fos ->
                val bos = BufferedOutputStream(fos)
                val zos = ZipOutputStream(bos)
                writeEntry(zos, MANIFEST_FILE, manifestBytes(noteId, wrappedKey), stored = false)
                if (lockKey == null) {
                    writeContent(zos, dir, docFile)
                } else {
                    val inner = ExposedByteArrayOutputStream(workingSize(dir))
                    ZipOutputStream(inner).use { writeContent(it, dir, docFile) }
                    writeEntry(zos, PAYLOAD_FILE, LockCrypto.sealPayload(lockKey, noteId, inner.buffer, inner.size()), stored = true)
                }
                zos.finish()
                bos.flush()
                fos.fd.sync()
            }
            beforePackRename?.invoke()
            Files.move(tmp.toPath(), container.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(notesRoot)
        } catch (e: Throwable) {
            unpacked += noteId
            tmp.delete()
            throw e
        }
        // Equal timestamps mean "in sync": a later autosave makes the working copy strictly newer.
        docFile.setLastModified(container.lastModified())
    }

    /** Bytes in the working copy (the inner ZIP of a locked note is about this big: sizes the buffer once). */
    private fun workingSize(dir: File): Int =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }.coerceAtMost(Int.MAX_VALUE - 1L).toInt() + 64 * 1024

    private fun writeContent(zos: ZipOutputStream, dir: File, docFile: File) {
        writeEntry(zos, DOCUMENT_FILE, docFile.readBytes(), stored = false)
        for (sub in listOf("ink", "media")) {
            File(dir, sub).listFiles()?.filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) }
                ?.sortedBy { it.name }?.forEach { writeEntry(zos, "$sub/${it.name}", it.readBytes(), stored = true) }
        }
        // The sdocx/ side-channel is small JSON + one deflated archive blob; deflate it.
        File(dir, EXTRAS_DIR).walkTopDown().filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) }
            .sortedBy { it.path }.forEach {
                val rel = it.relativeTo(dir).invariantSeparatorsPath
                writeEntry(zos, rel, it.readBytes(), stored = false)
            }
    }

    private fun rewriteContainer(src: File, dst: File, newId: String) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + TMP_SUFFIX)
        try {
            ZipFile(src).use { zf ->
                FileOutputStream(tmp).use { fos ->
                    val bos = BufferedOutputStream(fos)
                    val zos = ZipOutputStream(bos)
                    writeEntry(zos, MANIFEST_FILE, manifestBytes(newId, null), stored = false)
                    for (entry in zf.entries()) {
                        if (entry.isDirectory || entry.name == MANIFEST_FILE || !isAllowedEntry(entry.name)) continue
                        var bytes = readEntryLimited(zf, entry)
                        if (entry.name == DOCUMENT_FILE) {
                            bytes = NoteDocument.fromJson(bytes.decodeToString()).copy(id = newId).toJson().encodeToByteArray()
                        }
                        writeEntry(zos, entry.name, bytes, stored = entry.method == ZipEntry.STORED)
                    }
                    zos.finish()
                    bos.flush()
                    fos.fd.sync()
                }
            }
            Files.move(tmp.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            dst.parentFile?.let(::syncDirectory)
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** Makes a finished rename durable: fsync the directory that now holds the new file (best effort). */
    private fun syncDirectory(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: Exception) {
            // Some filesystems refuse to open a directory; the rename itself is already atomic.
            log.log(Level.FINE, "Could not fsync $dir", e)
        }
    }

    private class ManifestInfo(val noteId: String, val locked: Boolean, val wrappedKey: ByteArray?, val containerVersion: Int = CONTAINER_VERSION)

    /** Validates the manifest (v1 containers have no `containerVersion`/`locked`; both default to plain). */
    private fun readManifestInfo(zf: ZipFile): ManifestInfo {
        val entry = zf.getEntry(MANIFEST_FILE) ?: throw IOException("Not a SameSame note: no manifest")
        val text = zf.getInputStream(entry).use { it.readBytesLimited(Limits.MAX_MANIFEST_BYTES.toLong(), MANIFEST_FILE) }.decodeToString()
        val obj = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IOException("Bad manifest")
        if (obj["format"]?.jsonPrimitive?.content != FORMAT_NAME) throw IOException("Not a SameSame note")
        val version = obj["formatVersion"]?.jsonPrimitive?.int ?: throw IOException("Bad manifest")
        if (version > NOTE_DOCUMENT_FORMAT_VERSION) throw IOException("Note format version $version is newer than this app supports")
        val containerVersion = obj["containerVersion"]?.jsonPrimitive?.int ?: 1
        if (containerVersion > CONTAINER_VERSION) throw IOException("Container version $containerVersion is newer than this app supports")
        val locked = containerVersion >= 2 && obj["locked"]?.jsonPrimitive?.content == "true"
        val key = if (locked) obj["wrappedKey"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) } else null
        return ManifestInfo(
            obj["noteId"]?.jsonPrimitive?.content ?: throw IOException("Bad manifest"), locked, key, containerVersion,
        )
    }

    private fun manifestBytes(noteId: String, wrappedKey: ByteArray?): ByteArray = buildJsonObject {
        put("format", FORMAT_NAME)
        put("formatVersion", NOTE_DOCUMENT_FORMAT_VERSION)
        put("containerVersion", CONTAINER_VERSION)
        put("noteId", noteId)
        if (wrappedKey != null) {
            put("locked", true)
            put("wrappedKey", Base64.getEncoder().encodeToString(wrappedKey))
        }
    }.toString().encodeToByteArray()

    private fun writeEntry(zos: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = CRC32().also { it.update(bytes) }.value
        }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun moveDirectory(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Different filesystems: copy, then delete the source.
            from.copyRecursively(to, overwrite = true)
            from.deleteRecursively()
        }
    }

    // ---- paths ---------------------------------------------------------------------------

    private fun isSafeId(id: String) = id.isNotEmpty() && id != ".." && id != "." && id.none { it == '/' || it == '\\' || it == '\u0000' }

    private fun checkId(noteId: String): String {
        require(isSafeId(noteId)) { "Bad note id" }
        return noteId
    }

    private fun isAllowedEntry(name: String): Boolean {
        if (name == DOCUMENT_FILE) return true
        if (name.startsWith("$EXTRAS_DIR/")) {
            val rel = name.removePrefix("$EXTRAS_DIR/")
            return rel.isNotBlank() && !rel.endsWith(TMP_SUFFIX) && isSafeRelativePath(rel)
        }
        val parts = name.split('/')
        return parts.size == 2 && (parts[0] == "ink" || parts[0] == "media") && isSafeId(parts[1]) && !parts[1].endsWith(TMP_SUFFIX)
    }

    private fun isSafeRelativePath(rel: String): Boolean =
        rel.none { it == '\\' || it == '\u0000' } && rel.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun containerFile(noteId: String) = File(notesRoot, "${checkId(noteId)}.$NOTE_CONTAINER_EXTENSION")

    private fun workingDir(noteId: String) = File(workingRoot, checkId(noteId))

    private fun pageFile(dir: File, pageId: String): File {
        require(isSafeId(pageId)) { "Bad page id" }
        return File(File(dir, "ink"), "$pageId.ink")
    }

    private companion object {
        val log: Logger = Logger.getLogger(NoteDocumentStore::class.java.name)
    }
}

/** A [java.io.ByteArrayOutputStream] whose buffer can be read without the copy `toByteArray` makes. */
internal class ExposedByteArrayOutputStream(initial: Int) : java.io.ByteArrayOutputStream(initial.coerceAtLeast(32)) {
    val buffer: ByteArray get() = buf
}

/** Uncompressed size allowed for one container entry. */
private fun entryLimit(name: String): Long =
    if (name == DOCUMENT_FILE) Limits.MAX_DOCUMENT_JSON_BYTES.toLong() else Limits.MAX_ARCHIVE_ENTRY_BYTES

/** Writes via `<name>.tmp` + fsync + atomic rename; a failed write leaves the old file intact. */
internal inline fun writeFileAtomically(target: File, block: (OutputStream) -> Unit) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    try {
        FileOutputStream(tmp).use { stream ->
            val out = stream.buffered()
            block(out)
            out.flush()
            stream.fd.sync()
        }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Throwable) {
        tmp.delete()
        throw e
    }
}
