package org.samesame.notes.feature.notes

import androidx.compose.runtime.mutableStateMapOf

/**
 * Insertion-ordered preview cache for the home cards, backed by a Compose snapshot map so cards
 * recompose when an entry arrives. Not thread-safe: callers mutate it on the main thread.
 */
internal class PreviewCache<K, V>(private val maxEntries: Int) {
    private val values = mutableStateMapOf<K, V>()
    private val order = ArrayDeque<K>()

    operator fun get(key: K): V? = values[key]
    fun contains(key: K): Boolean = values.containsKey(key)

    fun put(key: K, value: V) {
        if (values.put(key, value) == null) {
            order.addLast(key)
        } else {
            order.remove(key)
            order.addLast(key)
        }
        while (order.size > maxEntries) values.remove(order.removeFirst())
    }

    val size: Int get() = values.size
}

/** One card's interest in a preview render; release it when the card leaves the screen. Releasing twice is harmless. */
fun interface PreviewTicket {
    fun release()
}

/**
 * In-flight preview renders with the number of cards waiting for each. A render nobody waits for any more is cancelled
 * if it has not started (it is still queued for a render slot); one that started runs to the end and is cached.
 * Main thread only.
 */
internal class PreviewRequests<K : Any> {
    class Request<K : Any> internal constructor(val key: K) {
        internal var wanted = 0
        internal var started = false
        /** False until the caller launched the render for this (new) request. */
        var launched = false
        /** Cancels the queued render (set by the caller after launching it). */
        var cancel: (() -> Unit)? = null
    }

    private val live = HashMap<K, Request<K>>()

    val size: Int get() = live.size

    fun isInFlight(key: K): Boolean = key in live

    /** Registers one more waiting card for [key]: the request in flight, or a new one ([Request.launched] false). */
    fun want(key: K): Request<K> {
        val r = live.getOrPut(key) { Request(key) }
        r.wanted++
        return r
    }

    /** The render of [r] got its slot: from now on it is never cancelled by [release]. */
    fun started(r: Request<K>) {
        r.started = true
    }

    /** One waiting card left; cancels and forgets a request that nobody waits for and that has not started. */
    fun release(r: Request<K>) {
        r.wanted--
        if (r.wanted <= 0 && !r.started && live[r.key] === r) {
            live.remove(r.key)
            r.cancel?.invoke()
        }
    }

    /** The render of [r] ended (done, failed or cancelled). A newer request for the same key is left alone. */
    fun finished(r: Request<K>) {
        if (live[r.key] === r) live.remove(r.key)
    }

    /** A ticket that releases [r] once. */
    fun ticket(r: Request<K>): PreviewTicket {
        var released = false
        return PreviewTicket {
            if (!released) {
                released = true
                release(r)
            }
        }
    }
}
