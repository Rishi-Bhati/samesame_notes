package org.samesame.notes.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher

/**
 * A verified credential: the app master key, in memory only. Close it as soon as the operation it was
 * obtained for is done.
 */
class LockToken(master: ByteArray) : AutoCloseable {
    private var key: ByteArray? = master.copyOf()

    /** The master key. Callers must not retain it. */
    fun masterKey(): ByteArray = key?.copyOf() ?: error("LockToken is closed")

    override fun close() {
        key?.fill(0)
        key = null
    }
}

/** Where the vault persists its (non-secret) config blob. */
interface LockConfigStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** Android Keystore + BiometricPrompt, isolated so the vault can be tested on the JVM. */
interface BiometricKeyStore {
    /** Strong biometric hardware present and enrolled. */
    fun isAvailable(): Boolean

    /** Creates (replacing) the auth-bound key and returns an ENCRYPT cipher to put in a CryptoObject; null if unavailable. */
    fun newEnrollCipher(): Cipher?

    /** DECRYPT cipher initialised with the IV in [wrapped]; null when the key was permanently invalidated (biometrics changed). */
    fun unlockCipher(wrapped: ByteArray): Cipher?

    fun deleteKey()
}

/**
 * Master-key vault. The master key is random; it is stored only wrapped by the password-derived key and,
 * optionally, by the biometric Keystore key. Changing the password rewraps the master key, so locked notes
 * are never re-encrypted.
 *
 * Hardening (security review M4, L1):
 * - new password wraps use [iterations] (600k by default); each config stores its own count, so older configs
 *   still verify and are re-wrapped with the current count after the next correct password;
 * - with a [deviceSealer], the password wrap is sealed again with a non-exportable device key, so `lock.bin`
 *   alone gives no offline oracle; an unbound config is bound on the next correct password;
 * - with [attempts], wrong passwords are throttled ([AttemptLimiter]) across restarts.
 *
 * Config file: `MAGIC, version` then v1: `iterations, salt, pw, bio`; v2 adds `flags` after `iterations`
 * (bit 0: the password wrap is device-sealed). Both versions are read.
 */
class LockVault(
    private val storage: LockConfigStorage,
    private val iterations: Int = LockCrypto.DEFAULT_ITERATIONS,
    private val deviceSealer: DeviceKeySealer? = null,
    private val attempts: AttemptLimiter? = null,
) {
    private class Config(
        val iterations: Int,
        val salt: ByteArray,
        /** The password wrap of the master key; device-sealed when [deviceBound]. */
        val passwordWrapped: ByteArray,
        val biometricWrapped: ByteArray?,
        val deviceBound: Boolean = false,
    )

    @Synchronized
    fun hasPassword(): Boolean = load() != null

    @Synchronized
    fun hasBiometric(): Boolean = load()?.biometricWrapped != null

    @Synchronized
    fun biometricWrapped(): ByteArray? = load()?.biometricWrapped

    /** Milliseconds until another password attempt is allowed (0 = now). */
    @Synchronized
    fun lockoutRemainingMs(): Long = attempts?.remainingMs() ?: 0L

    /** Sets the first password. Fails if one exists. */
    @Synchronized
    fun createPassword(password: CharArray): LockToken {
        check(load() == null) { "A password already exists" }
        val master = LockCrypto.newKey()
        try {
            save(newPasswordConfig(password, master, biometricWrapped = null))
            attempts?.recordSuccess()
            return LockToken(master)
        } finally {
            master.fill(0)
        }
    }

    /**
     * @throws WrongPasswordException
     * @throws LockedOutException while too many wrong passwords are being throttled (the password is not checked)
     */
    @Synchronized
    fun verifyPassword(password: CharArray): LockToken {
        val c = load() ?: error("No password set")
        val master = unwrapMaster(c, password)
        try {
            upgradeIfNeeded(c, password, master)
            return LockToken(master)
        } finally {
            master.fill(0)
        }
    }

    /** @throws WrongPasswordException when [old] is wrong. Keeps the biometric wrap (the master key is unchanged). */
    @Synchronized
    fun changePassword(old: CharArray, new: CharArray) {
        val c = load() ?: error("No password set")
        val master = unwrapMaster(c, old)
        try {
            save(newPasswordConfig(new, master, c.biometricWrapped))
        } finally {
            master.fill(0)
        }
    }

    /** Stores the biometric wrap of the master key held by [token] (see [LockCrypto.wrapWithCipher]). */
    @Synchronized
    fun enableBiometric(token: LockToken, enrollCipher: Cipher) {
        val c = load() ?: error("No password set")
        val master = token.masterKey()
        try {
            save(Config(c.iterations, c.salt, c.passwordWrapped, LockCrypto.wrapWithCipher(enrollCipher, master), c.deviceBound))
        } finally {
            master.fill(0)
        }
    }

    @Synchronized
    fun disableBiometric() {
        val c = load() ?: return
        if (c.biometricWrapped != null) save(Config(c.iterations, c.salt, c.passwordWrapped, null, c.deviceBound))
    }

    /** @throws TamperedDataException when the unlocked cipher does not decrypt the stored wrap. */
    @Synchronized
    fun tokenFromBiometric(unlockCipher: Cipher): LockToken {
        val wrapped = load()?.biometricWrapped ?: error("Biometrics are not enabled")
        val master = LockCrypto.unwrapWithCipher(unlockCipher, wrapped)
        attempts?.recordSuccess() // the owner proved who they are: wrong-password throttling starts over
        return LockToken(master).also { master.fill(0) }
    }

    /** Checks the throttle, then the password; counts the outcome. */
    private fun unwrapMaster(c: Config, password: CharArray): ByteArray {
        attempts?.checkAllowed()
        val inner = if (c.deviceBound) {
            val sealer = deviceSealer ?: throw TamperedDataException()
            sealer.open(c.passwordWrapped) // a device-key failure is not a wrong password: not counted
        } else {
            c.passwordWrapped
        }
        val master = try {
            LockCrypto.unwrapWithPassword(password, c.salt, c.iterations, inner)
        } catch (e: WrongPasswordException) {
            attempts?.recordFailure()
            throw e
        }
        attempts?.recordSuccess()
        return master
    }

    private fun newPasswordConfig(password: CharArray, master: ByteArray, biometricWrapped: ByteArray?): Config {
        val salt = LockCrypto.randomBytes(LockCrypto.SALT_BYTES)
        val wrapped = LockCrypto.wrapWithPassword(password, salt, iterations, master)
        val sealed = deviceSealer?.let { s -> runCatching { s.seal(wrapped) }.getOrNull() }
        return Config(iterations, salt, sealed ?: wrapped, biometricWrapped, deviceBound = sealed != null)
    }

    /** Re-wraps a config made with fewer iterations, or not yet bound to the device; best effort. */
    private fun upgradeIfNeeded(c: Config, password: CharArray, master: ByteArray) {
        val weak = c.iterations < iterations
        val unbound = !c.deviceBound && deviceSealer != null
        if (!weak && !unbound) return
        runCatching {
            val next = newPasswordConfig(password, master, c.biometricWrapped)
            if (next.iterations > c.iterations || next.deviceBound) save(next)
        }
    }

    private fun load(): Config? {
        val bytes = storage.read() ?: return null
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { i ->
                if (i.readInt() != MAGIC) throw IOException("Bad lock config")
                val version = i.readInt()
                if (version !in 1..VERSION) throw IOException("Unsupported lock config version")
                val it = i.readInt()
                val flags = if (version >= 2) i.readInt() else 0
                val salt = readBlob(i)
                val pw = readBlob(i)
                val bioLen = i.readInt()
                val bio = if (bioLen < 0) null else ByteArray(checkLen(bioLen)).also(i::readFully)
                return Config(it, salt, pw, bio, deviceBound = flags and FLAG_DEVICE_BOUND != 0)
            }
        } catch (e: IOException) {
            throw IllegalStateException("Corrupt lock config", e)
        }
    }

    private fun readBlob(i: DataInputStream): ByteArray = ByteArray(checkLen(i.readInt())).also(i::readFully)

    private fun checkLen(n: Int): Int {
        if (n !in 0..MAX_BLOB) throw IOException("Bad lock config length")
        return n
    }

    private fun save(c: Config) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeInt(MAGIC)
            o.writeInt(VERSION)
            o.writeInt(c.iterations)
            o.writeInt(if (c.deviceBound) FLAG_DEVICE_BOUND else 0)
            o.writeInt(c.salt.size); o.write(c.salt)
            o.writeInt(c.passwordWrapped.size); o.write(c.passwordWrapped)
            if (c.biometricWrapped == null) o.writeInt(-1) else { o.writeInt(c.biometricWrapped.size); o.write(c.biometricWrapped) }
        }
        storage.write(out.toByteArray())
    }

    private companion object {
        const val MAGIC = 0x5353_4C4B // "SSLK"
        const val VERSION = 2
        const val FLAG_DEVICE_BOUND = 0x1
        const val MAX_BLOB = 64 * 1024
    }
}
