package org.samesame.notes.feature.composer.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.samesame.notes.core.document.NoteSnapshot

/** A snapshot taken on the main thread plus what to do once it is on disk. */
class PendingSave(val snapshot: NoteSnapshot, val onSaved: () -> Unit)

/** What the autosaver needs from the session. Called on the autosaver's scope thread (main). */
interface SaveSource {
    fun isDirty(): Boolean

    /** O(1)-ish snapshot of everything dirty, or null when clean. Dirty flags stay set until `onSaved`. */
    fun takeSnapshot(): PendingSave?
}

/**
 * Debounced, crash-safe saving (doc 5.1).
 *
 * - [onEdit] debounces [debounceMs]; during continuous edits a save is still forced every [maxIntervalMs].
 * - The snapshot is taken on the caller's thread (main) and written on [io], both under one per-note mutex so
 *   writes happen in snapshot order.
 * - A failed write keeps the dirty flags, is reported on [errors] and retried with exponential backoff.
 * - While saved-but-not-packed changes exist, the working copy is packed to the `.ssnote` every [containerFlushMs].
 * - [flush] saves now and packs (ON_STOP; `close = true` when the editor closes).
 */
class Autosaver(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val source: SaveSource,
    private val write: suspend (NoteSnapshot) -> Unit,
    private val packContainer: suspend (close: Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val containerFlushMs: Long = CONTAINER_FLUSH_MS,
    private val retryInitialMs: Long = RETRY_INITIAL_MS,
    private val retryMaxMs: Long = RETRY_MAX_MS,
) {
    /** False while "Auto save notes" is off: edits are not saved until [flush] is forced (the Save choice). */
    @Volatile
    var enabled: Boolean = true

    private val mutex = Mutex()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)

    /** Save/pack failures, for a snackbar. */
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    private var containerDirty = false
    private val jobs = ArrayList<Job>()

    init {
        jobs += scope.launch { debounceLoop() }
        jobs += scope.launch { containerLoop() }
    }

    /** Something changed. Cheap; call on every edit. */
    fun onEdit() {
        if (!enabled) return
        signal.trySend(Unit)
    }

    /** Saves any pending changes now, then packs the container. Call on ON_STOP and when the editor closes. */
    suspend fun flush(close: Boolean = false, force: Boolean = false) {
        if (enabled || force) saveOnce()
        mutex.withLock {
            if (!containerDirty && !close) return
            try {
                withContext(io) { packContainer(close) }
                containerDirty = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.tryEmit(e)
            }
        }
    }

    /** Stops the background loops (the caller then runs a final [flush] on a longer-lived scope). */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun debounceLoop() {
        for (ignored in signal) {
            val burstStart = clock()
            while (true) {
                val left = maxIntervalMs - (clock() - burstStart)
                if (left <= 0) break
                val more = withTimeoutOrNull(minOf(debounceMs, left)) { signal.receive() }
                if (more == null) break
            }
            saveWithRetry()
        }
    }

    private suspend fun saveWithRetry() {
        var backoff = retryInitialMs
        while (!saveOnce()) {
            delay(backoff)
            backoff = minOf(backoff * 2, retryMaxMs)
            if (!source.isDirty()) return
        }
    }

    private suspend fun containerLoop() {
        while (true) {
            delay(containerFlushMs)
            if (!containerDirty) continue
            mutex.withLock {
                try {
                    withContext(io) { packContainer(false) }
                    containerDirty = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _errors.tryEmit(e)
                }
            }
        }
    }

    /** True when clean or saved; false when the write failed (flags kept). */
    private suspend fun saveOnce(): Boolean = mutex.withLock {
        val pending = source.takeSnapshot() ?: return@withLock true
        try {
            withContext(io) { write(pending.snapshot) }
            pending.onSaved()
            containerDirty = true
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _errors.tryEmit(e)
            false
        }
    }

    companion object {
        const val DEBOUNCE_MS = 400L
        const val MAX_INTERVAL_MS = 3_000L
        const val CONTAINER_FLUSH_MS = 30_000L
        const val RETRY_INITIAL_MS = 1_000L
        const val RETRY_MAX_MS = 30_000L
    }
}
