package org.samesame.notes.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Security review M4 / L1: iteration upgrade, device binding, persisted attempt throttling. */
class LockHardeningTest {
    private class MemoryStorage : LockConfigStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes }
    }

    /** Stand-in for the Keystore key: AES-GCM with a key that never leaves this object. */
    private class FakeSealer : DeviceKeySealer {
        private val key = SecretKeySpec(LockCrypto.newKey(), "AES")
        var available = true
        override fun seal(data: ByteArray): ByteArray {
            check(available)
            val c = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, key) }
            return LockCrypto.wrapWithCipher(c, data)
        }
        override fun open(sealed: ByteArray): ByteArray {
            check(available)
            val (iv, _) = LockCrypto.splitCipherWrap(sealed)
            val c = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            return LockCrypto.unwrapWithCipher(c, sealed)
        }
    }

    /** A config exactly as the pre-2026-10-30 app wrote it (version 1, no flags). */
    private fun v1Config(password: String, master: ByteArray, iterations: Int): ByteArray {
        val salt = LockCrypto.randomBytes(16)
        val wrapped = LockCrypto.wrapWithPassword(password.toCharArray(), salt, iterations, master)
        return ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { o ->
                o.writeInt(0x5353_4C4B); o.writeInt(1); o.writeInt(iterations)
                o.writeInt(salt.size); o.write(salt)
                o.writeInt(wrapped.size); o.write(wrapped)
                o.writeInt(-1)
            }
        }.toByteArray()
    }

    private fun iterationsOf(bytes: ByteArray) = java.io.DataInputStream(bytes.inputStream()).run { readInt(); readInt(); readInt() }
    private fun versionOf(bytes: ByteArray) = java.io.DataInputStream(bytes.inputStream()).run { readInt(); readInt() }

    @Test fun defaultIterationsMeetOwasp() {
        assertThat(LockCrypto.DEFAULT_ITERATIONS).isAtLeast(600_000)
    }

    @Test fun legacyConfigStillVerifiesAndIsUpgraded() {
        val storage = MemoryStorage()
        val master = LockCrypto.newKey()
        storage.bytes = v1Config("abcd", master, iterations = 500)
        val sealer = FakeSealer()
        val vault = LockVault(storage, iterations = 2_000, deviceSealer = sealer)
        assertThat(vault.verifyPassword("abcd".toCharArray()).use { it.masterKey() }).isEqualTo(master)
        // Re-wrapped: version 2, the current iteration count, device-bound; same master key.
        assertThat(versionOf(storage.bytes!!)).isEqualTo(2)
        assertThat(iterationsOf(storage.bytes!!)).isEqualTo(2_000)
        val again = LockVault(storage, iterations = 2_000, deviceSealer = sealer)
        assertThat(again.verifyPassword("abcd".toCharArray()).use { it.masterKey() }).isEqualTo(master)
    }

    @Test fun deviceBoundConfigIsUselessWithoutTheDeviceKey() {
        val storage = MemoryStorage()
        val sealer = FakeSealer()
        LockVault(storage, iterations = 1_000, deviceSealer = sealer).createPassword("secret1".toCharArray()).close()
        // An attacker with only the file (no device key) cannot even test a password.
        val offline = LockVault(storage, iterations = 1_000, deviceSealer = null)
        assertThrows(TamperedDataException::class.java) { offline.verifyPassword("secret1".toCharArray()) }
        val otherDevice = LockVault(storage, iterations = 1_000, deviceSealer = FakeSealer())
        assertThrows(Exception::class.java) { otherDevice.verifyPassword("secret1".toCharArray()) }
        LockVault(storage, iterations = 1_000, deviceSealer = sealer).verifyPassword("secret1".toCharArray()).close()
    }

    @Test fun sealerUnavailableAtCreationFallsBackToUnbound() {
        val storage = MemoryStorage()
        val sealer = FakeSealer().apply { available = false }
        val master = LockVault(storage, iterations = 1_000, deviceSealer = sealer).createPassword("secret1".toCharArray()).use { it.masterKey() }
        assertThat(LockVault(storage, iterations = 1_000).verifyPassword("secret1".toCharArray()).use { it.masterKey() }).isEqualTo(master)
    }

    @Test fun wrongPasswordsAreThrottledAcrossRestarts() {
        var now = 1_000_000L
        val config = MemoryStorage()
        val attemptsFile = MemoryStorage()
        fun vault() = LockVault(config, iterations = 1_000, attempts = AttemptLimiter(attemptsFile) { now })
        vault().createPassword("secret1".toCharArray()).close()
        repeat(AttemptLimiter.FREE_ATTEMPTS) {
            assertThrows(WrongPasswordException::class.java) { vault().verifyPassword("nope".toCharArray()) }
        }
        // Locked out now, even for the right password, and even in a "new process".
        val e = assertThrows(LockedOutException::class.java) { vault().verifyPassword("secret1".toCharArray()) }
        assertThat(e.remainingMs).isEqualTo(AttemptLimiter.FIRST_LOCKOUT_MS)
        // Turning the clock back does not shorten it.
        now -= 10 * 60_000L
        assertThrows(LockedOutException::class.java) { vault().verifyPassword("secret1".toCharArray()) }
        now += AttemptLimiter.FIRST_LOCKOUT_MS
        // One more wrong password doubles the wait.
        assertThrows(WrongPasswordException::class.java) { vault().verifyPassword("nope".toCharArray()) }
        assertThat(vault().lockoutRemainingMs()).isEqualTo(2 * AttemptLimiter.FIRST_LOCKOUT_MS)
        now += 2 * AttemptLimiter.FIRST_LOCKOUT_MS
        vault().verifyPassword("secret1".toCharArray()).close()
        assertThat(vault().lockoutRemainingMs()).isEqualTo(0)
        // The count was reset by the correct password.
        assertThrows(WrongPasswordException::class.java) { vault().verifyPassword("nope".toCharArray()) }
        assertThat(vault().lockoutRemainingMs()).isEqualTo(0)
    }

    @Test fun lockoutGrowsAndIsCapped() {
        assertThat(AttemptLimiter.lockoutFor(4)).isEqualTo(0)
        assertThat(AttemptLimiter.lockoutFor(5)).isEqualTo(30_000)
        assertThat(AttemptLimiter.lockoutFor(6)).isEqualTo(60_000)
        assertThat(AttemptLimiter.lockoutFor(500)).isEqualTo(AttemptLimiter.MAX_LOCKOUT_MS)
    }
}
