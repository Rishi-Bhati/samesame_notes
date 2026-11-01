package org.samesame.notes.feature.composer.session

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Orders "editor closed" against "editor opened again" for the same note. Closing runs the final save / pack and then
 * deletes the working copy on an app-lifetime scope; a quick re-open (back, then tap the note again) would otherwise load
 * the container before that pack finished (losing the last typed text) and then see its working copy deleted under it.
 * The closing job registers here; the next session for the same key waits for it with [awaitClosed].
 */
class CloseGate<K : Any> {
    private val closing = ConcurrentHashMap<K, Job>()

    /** Records [job] as the pending close of [key]; it unregisters itself when it completes. */
    fun register(key: K, job: Job) {
        closing[key] = job
        job.invokeOnCompletion { closing.remove(key, job) }
    }

    /** Suspends until the pending close of [key] (if any) has finished. */
    suspend fun awaitClosed(key: K) {
        closing[key]?.join()
    }

    fun isClosing(key: K): Boolean = closing[key]?.isActive == true
}
