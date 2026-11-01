package org.samesame.notes.core.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The password was wrong (the wrapped key failed its authentication tag). */
class WrongPasswordException : GeneralSecurityException("Incorrect password")

/** Ciphertext failed authentication: it was modified, truncated, or belongs to another context. */
class TamperedDataException(cause: Throwable? = null) : GeneralSecurityException("Data failed authentication", cause)

/**
 * Primitives for locked notes (our own design, not the reference's):
 *
 * - key-encryption key = PBKDF2-HMAC-SHA256(password, salt, iterations)
 * - everything sealed with AES-256-GCM: `nonce(12) || ciphertext || tag(16)`, with associated data
 *   binding the blob to its purpose (so a wrapped key cannot be swapped for another one)
 */
object LockCrypto {
    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128
    /** PBKDF2-HMAC-SHA256 cost of new password wraps (OWASP 2023+). Older configs keep their stored count until upgraded. */
    const val DEFAULT_ITERATIONS = 600_000
    /** The count used before 2026-10-30; configs written with it are upgraded on the next correct password. */
    const val LEGACY_ITERATIONS = 210_000

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun newKey(): ByteArray = randomBytes(KEY_BYTES)

    fun deriveKek(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    /** @throws TamperedDataException when the tag does not verify (wrong key, wrong aad, or modified data). */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) throw TamperedDataException()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
            )
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: AEADBadTagException) {
            throw TamperedDataException(e)
        }
    }

    /** Wraps [secret] under a password-derived key. */
    fun wrapWithPassword(password: CharArray, salt: ByteArray, iterations: Int, secret: ByteArray): ByteArray {
        val kek = deriveKek(password, salt, iterations)
        try {
            return seal(kek, secret, AAD_PASSWORD)
        } finally {
            kek.fill(0)
        }
    }

    /** @throws WrongPasswordException when the password does not unwrap [wrapped]. */
    fun unwrapWithPassword(password: CharArray, salt: ByteArray, iterations: Int, wrapped: ByteArray): ByteArray {
        val kek = deriveKek(password, salt, iterations)
        try {
            return open(kek, wrapped, AAD_PASSWORD)
        } catch (e: TamperedDataException) {
            throw WrongPasswordException()
        } finally {
            kek.fill(0)
        }
    }

    /** Per-note data key wrapped by the master key; bound to [noteId]. */
    fun wrapNoteKey(master: ByteArray, noteId: String, dek: ByteArray): ByteArray =
        seal(master, dek, AAD_NOTE_KEY + noteId.encodeToByteArray())

    fun unwrapNoteKey(master: ByteArray, noteId: String, wrapped: ByteArray): ByteArray =
        open(master, wrapped, AAD_NOTE_KEY + noteId.encodeToByteArray())

    /** Note payload sealed with the note key; bound to [noteId]. */
    fun sealPayload(dek: ByteArray, noteId: String, plaintext: ByteArray): ByteArray =
        seal(dek, plaintext, AAD_PAYLOAD + noteId.encodeToByteArray())

    fun openPayload(dek: ByteArray, noteId: String, sealed: ByteArray): ByteArray =
        open(dek, sealed, AAD_PAYLOAD + noteId.encodeToByteArray())

    /**
     * Biometric wrap: [cipher] is an authenticated (BiometricPrompt CryptoObject) Keystore ENCRYPT cipher.
     * Layout: `ivLen(1) || iv || ciphertext+tag`.
     */
    fun wrapWithCipher(cipher: Cipher, secret: ByteArray): ByteArray {
        val ct = cipher.doFinal(secret)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    /** Splits a [wrapWithCipher] blob into (iv, ciphertext). */
    fun splitCipherWrap(wrapped: ByteArray): Pair<ByteArray, ByteArray> {
        if (wrapped.isEmpty()) throw TamperedDataException()
        val ivLen = wrapped[0].toInt() and 0xFF
        if (wrapped.size <= 1 + ivLen) throw TamperedDataException()
        return wrapped.copyOfRange(1, 1 + ivLen) to wrapped.copyOfRange(1 + ivLen, wrapped.size)
    }

    fun unwrapWithCipher(cipher: Cipher, wrapped: ByteArray): ByteArray {
        val (_, ct) = splitCipherWrap(wrapped)
        try {
            return cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw TamperedDataException(e)
        }
    }

    private val AAD_PASSWORD = "samesame.lock.master.v1".encodeToByteArray()
    private val AAD_NOTE_KEY = "samesame.lock.notekey.v1:".encodeToByteArray()
    private val AAD_PAYLOAD = "samesame.lock.payload.v1:".encodeToByteArray()
}
