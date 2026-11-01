package org.samesame.notes.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException

/** Too many wrong passwords: no attempt is allowed for [remainingMs] more milliseconds. */
class LockedOutException(val remainingMs: Long) : GeneralSecurityException("Too many attempts; retry in $remainingMs ms")

/**
 * Throttles password guesses, persisted in [storage] so killing the app does not reset it.
 *
 * The first [FREE_ATTEMPTS] wrong passwords cost nothing; then each wrong password locks the vault for
 * [FIRST_LOCKOUT_MS], doubling with every further failure up to [MAX_LOCKOUT_MS]. A correct password resets
 * the count. The clock is the wall clock (it must survive restarts); moving it backwards restarts the current
 * lockout instead of shortening it.
 */
class AttemptLimiter(
    private val storage: LockConfigStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var lastFailureAt = 0L
    private var loaded = false

    /** @throws LockedOutException while a lockout is running. */
    @Synchronized
    fun checkAllowed() {
        load()
        val remaining = remainingMs()
        if (remaining > 0) throw LockedOutException(remaining)
    }

    @Synchronized
    fun recordFailure() {
        load()
        failures = (failures + 1).coerceAtMost(MAX_COUNTED_FAILURES)
        lastFailureAt = clock()
        save()
    }

    @Synchronized
    fun recordSuccess() {
        load()
        if (failures == 0) return
        failures = 0
        lastFailureAt = 0L
        save()
    }

    /** Milliseconds until the next attempt is allowed (0 = now). */
    @Synchronized
    fun remainingMs(): Long {
        load()
        val lockout = lockoutFor(failures)
        if (lockout == 0L) return 0L
        val now = clock()
        if (now < lastFailureAt) {
            // The clock went backwards: count the lockout from now.
            lastFailureAt = now
            save()
        }
        return (lastFailureAt + lockout - now).coerceAtLeast(0L)
    }

    private fun load() {
        if (loaded) return
        loaded = true
        val bytes = runCatching { storage.read() }.getOrNull() ?: return
        runCatching {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                if (i.readInt() != MAGIC) return
                failures = i.readInt().coerceIn(0, MAX_COUNTED_FAILURES)
                lastFailureAt = i.readLong()
            }
        }
    }

    private fun save() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(failures)
            o.writeLong(lastFailureAt)
        }
        runCatching { storage.write(out.toByteArray()) }
    }

    companion object {
        const val FREE_ATTEMPTS = 5
        const val FIRST_LOCKOUT_MS = 30_000L
        const val MAX_LOCKOUT_MS = 60 * 60_000L
        private const val MAX_COUNTED_FAILURES = 1_000
        private const val MAGIC = 0x5353_4154 // "SSAT"

        /** Lockout after [failures] consecutive wrong passwords. */
        fun lockoutFor(failures: Int): Long {
            if (failures < FREE_ATTEMPTS) return 0L
            val doublings = (failures - FREE_ATTEMPTS).coerceAtMost(20)
            return (FIRST_LOCKOUT_MS shl doublings).coerceAtMost(MAX_LOCKOUT_MS)
        }
    }
}
