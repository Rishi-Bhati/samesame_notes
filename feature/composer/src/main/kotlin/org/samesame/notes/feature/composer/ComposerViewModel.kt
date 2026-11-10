package org.samesame.notes.feature.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.samesame.notes.core.data.di.ApplicationScope
import org.samesame.notes.core.data.pdf.PdfNoteBuilder
import org.samesame.notes.core.data.repository.AppSettingsRepository
import org.samesame.notes.core.data.repository.EditorPreferencesRepository
import org.samesame.notes.core.data.repository.NoteRepository
import org.samesame.notes.core.model.AppSettings
import org.samesame.notes.core.model.Limits
import org.samesame.notes.core.model.Note
import org.samesame.notes.core.model.NoteId
import org.samesame.notes.feature.composer.voice.DiscardReason
import org.samesame.notes.feature.composer.voice.FinishReason
import org.samesame.notes.feature.composer.voice.RecorderState
import org.samesame.notes.feature.composer.voice.RecordingResult
import org.samesame.notes.feature.composer.voice.VoiceNotice
import org.samesame.notes.feature.composer.voice.VoicePlaybackHub
import org.samesame.notes.feature.composer.voice.VoiceRecordingHub
import org.samesame.notes.feature.composer.canvas.DocumentLayout
import org.samesame.notes.feature.composer.perf.EditorTrace
import org.samesame.notes.feature.composer.session.CloseGate
import org.samesame.notes.feature.composer.session.EditorClipboard
import org.samesame.notes.feature.composer.session.EditorSession
import org.samesame.notes.feature.composer.text.BodyTextBinding
import org.samesame.notes.feature.composer.text.prepareEditorText
import org.samesame.notes.feature.composer.toolbar.EditorMode
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Owns the [EditorSession] of one note (assisted [noteId]). The session is null while the note opens.
 * Final save: `ON_STOP` -> [onStop]; editor closed / process teardown -> [onCleared] on the app scope.
 */
@HiltViewModel(assistedFactory = ComposerViewModel.Factory::class)
class ComposerViewModel @AssistedInject constructor(
    @Assisted noteId: String,
    @param:ApplicationContext private val appContext: Context,
    private val notes: NoteRepository,
    folderRepository: org.samesame.notes.core.data.repository.FolderRepository,
    private val prefs: EditorPreferencesRepository,
    appSettings: AppSettingsRepository,
    private val clipboard: EditorClipboard,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val files: org.samesame.notes.core.data.repository.FilesRepository,
) : ViewModel() {

    /** The Files library as the editor sees it (file links, Show in note). */
    val fileLinks: org.samesame.notes.feature.composer.files.NoteFileLinks = object : org.samesame.notes.feature.composer.files.NoteFileLinks {
        override suspend fun resolve(ids: List<org.samesame.notes.core.model.files.FileId>) =
            files.getFiles(ids).associateBy { it.id }

        override fun contentUri(file: org.samesame.notes.core.model.files.LibraryFile) = files.contentUri(file)

        override suspend fun readBytes(file: org.samesame.notes.core.model.files.LibraryFile, maxBytes: Long): ByteArray? =
            withContext(Dispatchers.IO) {
                if (file.isInTrash || file.sizeBytes > maxBytes) return@withContext null
                runCatching {
                    appContext.contentResolver.openInputStream(files.contentUri(file))?.use { input ->
                        val out = java.io.ByteArrayOutputStream(file.sizeBytes.coerceIn(0, maxBytes).toInt())
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return@use null
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    }
                }.getOrNull()
            }

        override suspend fun restore(id: org.samesame.notes.core.model.files.FileId) = files.restore(listOf(id))
    }

    @AssistedFactory
    interface Factory {
        fun create(noteId: String): ComposerViewModel
    }

    private val id = NoteId(noteId)

    private val _session = MutableStateFlow<EditorSession?>(null)

    /** Hooks for the canvas (`SessionPageSource`, `SessionToolHandlers`) and the text engine (`bindBody`, `onTextEdit`...). */
    val session: StateFlow<EditorSession?> = _session.asStateFlow()

    /** App settings (Settings screens); the editor reads the behaviour switches from here. */
    val settings: StateFlow<AppSettings> =
        appSettings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** True once the first real [settings] snapshot is in (the editor waits for it before composing, see [ready]). */
    private val settingsLoaded = MutableStateFlow(false)

    /** The open note's catalog row and folder name; null until the first emission. */
    private data class NoteInfo(val note: Note?, val folderName: String?)

    // One subscription for everything the header and the star need: the row changes on every save (modified_at), and
    // three separate observeNote flows meant three queries and three emissions per change.
    private val noteInfo: StateFlow<NoteInfo?> =
        kotlinx.coroutines.flow.combine(notes.observeNote(id), folderRepository.observeFolders()) { n, folders ->
            NoteInfo(n, folders.firstOrNull { it.id == n?.folderId }?.name)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True when the note is locked in the catalog (Save as file / Share need it unlocked first). */
    val isLocked: StateFlow<Boolean> = noteInfo
        .map { it?.note?.isLocked == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Favourite star of the open note (overflow "Favourite"; filled when on). */
    val isFavorite: StateFlow<Boolean> = noteInfo
        .map { it?.note?.isFavorite == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Folder, created and modified times for the expanded title header. */
    val meta: StateFlow<NoteMeta> = noteInfo
        .map { info ->
            val n = info?.note ?: return@map NoteMeta()
            NoteMeta(folderName = info.folderName, createdAt = n.createdAt, modifiedAt = n.modifiedAt, favorite = n.isFavorite)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NoteMeta())

    /**
     * True when the editor may compose: the session is open and its first values (catalog row, app settings) are in.
     * Composing earlier made the full editor recompose once per late value (2-4 whole-screen recompositions while the
     * open animation ran); with the gate it composes once, already final. The screen keeps a failsafe timeout so a
     * source that never emits cannot leave the editor on its placeholder.
     */
    val ready: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_session, noteInfo, settingsLoaded) { s, info, settingsIn ->
            s != null && info != null && settingsIn
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleFavorite() {
        appScope.launch { runCatching { notes.setFavorite(listOf(id), !isFavorite.value) } }
    }

    /** Moves the open note to the Recycle bin, then [onDone] leaves the editor (the reference: the note is gone from the editor). */
    fun moveToTrash(onDone: () -> Unit) {
        // App scope: the write must finish even though leaving the editor clears this view-model.
        val trash = appScope.launch { runCatching { notes.moveToTrash(listOf(id)) } }
        // A locked note counts as verified only while it is open here (the repository refuses it otherwise): leave the
        // editor, which closes the note, only after the move is done.
        viewModelScope.launch {
            trash.join()
            onDone()
        }
    }

    /**
     * Locks the open note with the verified [token]. The session is saved and packed first and dropped, so nothing writes
     * the plain working copy after the note is encrypted; then [onDone] leaves the editor.
     */
    fun lockNote(token: org.samesame.notes.core.security.LockToken, onDone: () -> Unit) {
        val closing = _session.value
        // The session stays on screen while the editor closes (dropping it first flashed the empty loading page through
        // the close animation); it is retired instead: ON_STOP, re-lock and onCleared leave it to this job.
        retired = closing
        appScope.launch(Dispatchers.Main.immediate) {
            try {
                closing?.finish()
                runCatching { notes.lockNotes(listOf(id), token) }
            } finally {
                token.close()
            }
        }
        onDone()
    }

    /** A session [lockNote] is finishing: still shown until the editor is gone, but no longer saved by anyone else. */
    private var retired: EditorSession? = null

    private val _lockedOut = MutableStateFlow(false)

    /** The note is locked and was not unlocked in this process (e.g. restored after process death): leave the editor. */
    val lockedOut: StateFlow<Boolean> = _lockedOut.asStateFlow()

    /** Removes the lock of the open note with the verified [token] (closed afterwards). */
    fun unlock(token: org.samesame.notes.core.security.LockToken) {
        viewModelScope.launch {
            try { runCatching { notes.unlockNotes(listOf(id), token) } } finally { token.close() }
        }
    }

    /**
     * Insert PDF (`#53`): renders the pages of [file] (a local copy of the picked PDF), each keeping its own size, and
     * inserts them as page backgrounds right after [afterPageId], as one undo step. Returns the number of pages added.
     * Throws [org.samesame.notes.core.data.pdf.PdfImportException] when the file cannot be used.
     */
    suspend fun insertPdf(file: java.io.File, afterPageId: String): Int {
        val open = _session.value ?: return 0
        val pages = notes.renderPdfPages(id, file)
        open.insertPagesAfter(afterPageId, PdfNoteBuilder.backgroundPages(pages))
        return pages.size
    }

    // ---- voice recording (O5)
    private val _voiceNotices = MutableSharedFlow<VoiceNotice>(extraBufferCapacity = 8)

    /** Outcome messages of recordings for this note (saved, too short, failed...). */
    val voiceNotices: SharedFlow<VoiceNotice> = _voiceNotices.asSharedFlow()

    /** The bundle file behind a voice object's `media/<name>` path (the player needs a file). */
    suspend fun voiceFile(media: String): java.io.File? = notes.mediaFile(id, media)

    /**
     * Hands a finished recording of this note to the session: audio into the bundle, then the note's recording list
     * (one undo step). the reference records are note-level, never on-page objects. Discarded ones only
     * produce their notice.
     */
    private suspend fun claimRecording(open: EditorSession, r: RecordingResult, notify: Boolean) {
        val recorder = VoiceRecordingHub.get(appContext)
        recorder.consume(r)
        when (r) {
            is RecordingResult.Discarded -> if (notify) _voiceNotices.tryEmit(
                if (r.reason == DiscardReason.TooShort) VoiceNotice.TooShort else VoiceNotice.Failed,
            )
            is RecordingResult.Saved -> {
                val media = runCatching { notes.saveMediaFile(id, r.file, Limits.VOICE_FILE_EXTENSION) }.getOrNull()
                r.file.delete()
                val added = media != null && open.addVoiceRecording(
                    media = media,
                    createdAtMs = System.currentTimeMillis(),
                    elapsedMs = r.durationMs,
                ).isNotEmpty()
                if (notify) _voiceNotices.tryEmit(
                    when {
                        !added -> VoiceNotice.Failed
                        r.reason == FinishReason.Interrupted -> VoiceNotice.SavedInterrupted
                        r.reason == FinishReason.MaxLength -> VoiceNotice.SavedMaxLength
                        else -> VoiceNotice.Saved
                    },
                )
            }
        }
    }

    init {
        // Recordings of this note that finish while the editor is open (Stop, notification Stop, a call, the length cap),
        // or finished earlier while no editor was open, are inserted as soon as the session exists (and into the new
        // session after a locked note was re-opened).
        viewModelScope.launch {
            _session.collectLatest { open ->
                if (open == null) return@collectLatest
                VoiceRecordingHub.get(appContext).results.collect { list ->
                    list.filter { it.noteId == id.value }.forEach { claimRecording(open, it, notify = true) }
                }
            }
        }
        viewModelScope.launch {
            val initial = appSettings.settings.first()
            settingsLoaded.value = true
            if (!openSession(initial.autoSaveNotes)) return@launch
            appSettings.settings.collect { s -> _session.value?.autoSave = s.autoSaveNotes }
        }
    }

    /** Opens the editing session; false (and [lockedOut]) when the note is locked and not open in this process. */
    private suspend fun openSession(autoSave: Boolean): Boolean {
        closeGate.awaitClosed(id.value) // a previous session of this note is still saving: wait for its final pack
        val doc = try {
            EditorTrace.section("open:content") { notes.openContent(id) }
        } catch (e: org.samesame.notes.core.document.NoteLockedException) {
            _lockedOut.value = true
            return false
        }
        // The reading position is its own per-note row, not part of the document: opening and scrolling must not touch
        // the note's content, modified time, preview or container.
        val viewState = EditorTrace.section("open:viewstate") {
            runCatching { notes.loadViewState(id) }.getOrNull()
        }
        // The two things the editor needs before it can compose with final values, off the main thread while the blank
        // page shows: the body text + the one layout that shapes the whole note (see prepareEditorText), and the tool
        // prefs (so the first composition has the real pen setup instead of recomposing when it arrives).
        val (toolPrefs, prepared) = withContext(Dispatchers.Default) {
            val storedPrefs = EditorTrace.section("open:prefs") {
                runCatching { prefs.preferences.first() }.getOrDefault(org.samesame.notes.core.data.repository.EditorPreferences())
            }
            storedPrefs to runCatching { prepareEditorText(doc, DocumentLayout(doc.pages, doc.layout)) }.getOrNull()
        }
        val session = EditorTrace.section("open:session") {
            EditorSession(
                noteId = id.value,
                initialDocument = doc,
                // The FAB note opens in edit mode with the keyboard; any note opened again is read-only until Edit.
                initialMode = if (notes.takeNewNote(id)) EditorMode.Text else EditorMode.Reading,
                scope = viewModelScope,
                io = Dispatchers.IO,
                pageLoader = { pageId -> notes.loadPage(id, pageId) },
                saveSink = { snapshot -> notes.saveContent(id, snapshot) },
                packContainer = { close -> notes.flushToContainer(id, close) },
                mediaLoader = { path -> notes.loadMedia(id, path) },
                mediaSink = { bytes, extension -> notes.saveMedia(id, bytes, extension) },
                prefs = prefs,
                initialPrefs = toolPrefs,
                clipboard = clipboard,
                initialViewState = viewState,
                viewStateSink = { state -> notes.saveViewState(id, state) },
            )
        }.also { it.autoSave = autoSave }
        // Binds the prepared body before the session is published, so the editor adopts it instead of building one.
        prepared?.let { session.bindBody(BodyTextBinding(it.body, session, it.layout)) }
        _session.value = session
        return true
    }

    // ---- locked notes in the background (security review M3; the reference re-verifies after ~3 s or screen off)
    private val _relocked = MutableStateFlow(false)

    /**
     * The open locked note was closed after the editor sat in the background (or the screen went off): its decrypted
     * working copy and key are gone, and the editor asks for the password again before showing anything.
     */
    val relocked: StateFlow<Boolean> = _relocked.asStateFlow()

    /** `SystemClock.elapsedRealtime` at the last ON_STOP of a locked note; 0 while in the foreground. */
    private var stoppedAt = 0L
    private var relockJob: Job? = null

    /** ON_START: cancels the pending re-lock, or re-locks now when the grace period passed while the process slept. */
    fun onStart() {
        relockJob?.cancel()
        relockJob = null
        val since = stoppedAt
        stoppedAt = 0L
        if (since != 0L && isLocked.value && SystemClock.elapsedRealtime() - since >= RELOCK_AFTER_MS) relockNow()
    }

    private fun scheduleRelock() {
        if (!isLocked.value || _session.value == null || _session.value === retired) return
        stoppedAt = SystemClock.elapsedRealtime()
        val screenOff = appContext.getSystemService(PowerManager::class.java)?.isInteractive == false
        relockJob?.cancel()
        if (screenOff) {
            relockNow()
        } else {
            relockJob = viewModelScope.launch {
                delay(RELOCK_AFTER_MS)
                relockJob = null
                relockNow()
            }
        }
    }

    private fun relockNow() {
        val s = _session.value?.takeIf { it !== retired } ?: return
        _session.value = null
        _relocked.value = true
        // A recording of this note keeps going in its service; it is added when the note is opened again.
        closeSession(s, endRecording = false)
    }

    /** The password (or biometrics) was verified again: decrypt the note and reopen the editor. */
    fun reopenLocked(token: org.samesame.notes.core.security.LockToken) {
        viewModelScope.launch {
            try {
                closeGate.awaitClosed(id.value)
                val opened = runCatching { notes.openLockedNote(id, token) }.isSuccess
                if (!opened || !openSession(settings.value.autoSaveNotes)) {
                    _lockedOut.value = true
                    return@launch
                }
                _relocked.value = false
            } finally {
                token.close()
            }
        }
    }

    /**
     * The in-memory session behind the drawing sheet (Insert > Drawing): one sheet-sized page holding [elements], never
     * saved to a bundle. It shares the editor preferences, so pen and colour choices carry over.
     */
    fun newDrawingSession(scope: CoroutineScope, elements: List<org.samesame.notes.core.ink.page.PageElement>): EditorSession {
        val sheetId = org.samesame.notes.feature.composer.drawing.DrawingGeometry.SHEET_PAGE_ID
        val white = 0xFFFFFFFFL
        val doc = org.samesame.notes.core.model.document.NoteDocument(
            id = "drawing-sheet",
            layout = org.samesame.notes.core.model.document.PageLayout(
                org.samesame.notes.core.model.PageMode.Pages, org.samesame.notes.core.model.PageRatio.DrawingSheet,
            ),
            style = org.samesame.notes.core.model.document.NoteStyle(backgroundColor = white, adjustForDarkMode = false),
            pages = listOf(org.samesame.notes.core.model.document.PageSpec(sheetId, backgroundColor = white)),
        )
        return EditorSession(
            noteId = doc.id,
            initialDocument = doc,
            scope = scope,
            io = Dispatchers.IO,
            pageLoader = { pageId -> org.samesame.notes.core.ink.page.PageContent(pageId, elements, 0) },
            saveSink = { },
            packContainer = { },
            prefs = prefs,
        ).also { it.autoSave = false }
    }

    /** "Save as file": renders [format] to [out] through the repository / core:export. */
    suspend fun export(
        format: org.samesame.notes.core.export.ExportFormat,
        options: org.samesame.notes.core.export.ExportOptions,
        out: java.io.OutputStream,
    ): org.samesame.notes.core.export.ExportTarget = notes.exportNote(id, format, options, out)

    /**
     * `ON_STOP`: save and pack, surviving the view-model's own scope. Runs on the main thread: the snapshot reads the
     * session's main-thread-only state (body text, page maps); only the disk writes hop to the IO dispatcher.
     */
    fun onStop() {
        _session.value?.takeIf { it !== retired }?.let { s ->
            appScope.launch(Dispatchers.Main.immediate) { EditorTrace.section("close:flush") { s.flush() } }
        }
        scheduleRelock()
    }

    override fun onCleared() {
        relockJob?.cancel()
        val s = _session.value?.takeIf { it !== retired }
        if (s != null) {
            closeSession(s, endRecording = true)
        } else {
            VoicePlaybackHub.peek()?.close()
            // Closed before the session finished opening (or it never opened): an untouched FAB note still has to go.
            // A locked note is left alone by deleteIfEmpty, and one with content is never touched.
            if (retired == null) appScope.launch { discardIfEmpty() }
        }
    }

    /** Discards the note when nothing but blank pages was left in it. Logged, never thrown to the caller. */
    private suspend fun discardIfEmpty() {
        try {
            notes.deleteIfEmpty(id)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(Level.WARNING, "Could not discard empty note ${id.value}", e)
        }
    }

    /**
     * Ends [s]: with [endRecording], this note's recording is stopped and saved into it; then the session is saved,
     * packed and its working copy dropped.
     */
    private fun closeSession(s: EditorSession, endRecording: Boolean) {
        VoicePlaybackHub.peek()?.close()
        val recorder = VoiceRecordingHub.peek()
        // Leaving the editor ends this note's recording: it is saved into the note before the final save.
        val recordingHere = (recorder?.state?.value as? RecorderState.Active)?.noteId == id.value
        if (recordingHere && endRecording) recorder?.stopForClose()
        // Started immediately on the main thread, so the final snapshot is taken before anything else can run.
        val job = appScope.launch(Dispatchers.Main.immediate) {
            try {
                recorder?.results?.value?.filter { it.noteId == id.value }?.forEach { claimRecording(s, it, notify = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "Could not claim the finished recordings of note ${id.value}", e)
            }
            val finished = try {
                EditorTrace.section("close:finish") { s.finish() }
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(Level.WARNING, "Could not finish the editor session of note ${id.value}", e)
                false
            }
            // Closing a note the user never wrote into (or emptied later) leaves nothing behind: no blank entry
            // in the list or the Recycle bin. Runs after the final save/pack, on the same scope, so no autosave
            // or flush can recreate the note (the repository treats the missing catalog row as the tombstone).
            // A failed finish keeps the note: its unsaved content may never have reached disk.
            if (finished) discardIfEmpty()
        }
        closeGate.register(id.value, job)
    }

    private companion object {
        /** Shared by all view-models: a re-opened note waits for the previous session's close. */
        val closeGate = CloseGate<String>()

        /** A locked note left in the background longer than this is closed and asks for the password again. */
        const val RELOCK_AFTER_MS = 3_000L

        val log: Logger = Logger.getLogger(ComposerViewModel::class.java.name)
    }
}
