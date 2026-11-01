package org.samesame.notes.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LockCryptoTest {
    private val fastIterations = 1_000

    @Test fun wrapUnwrapRoundTrip() {
        val master = LockCrypto.newKey()
        val salt = LockCrypto.randomBytes(16)
        val wrapped = LockCrypto.wrapWithPassword("hunter2".toCharArray(), salt, fastIterations, master)
        assertThat(LockCrypto.unwrapWithPassword("hunter2".toCharArray(), salt, fastIterations, wrapped)).isEqualTo(master)
    }

    @Test fun wrongPasswordIsRejected() {
        val salt = LockCrypto.randomBytes(16)
        val wrapped = LockCrypto.wrapWithPassword("right".toCharArray(), salt, fastIterations, LockCrypto.newKey())
        assertThrows(WrongPasswordException::class.java) {
            LockCrypto.unwrapWithPassword("wrong".toCharArray(), salt, fastIterations, wrapped)
        }
    }

    @Test fun tamperedCiphertextIsRejected() {
        val key = LockCrypto.newKey()
        val sealed = LockCrypto.seal(key, "secret note".encodeToByteArray(), "aad".encodeToByteArray())
        for (i in sealed.indices step 7) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(TamperedDataException::class.java) { LockCrypto.open(key, bad, "aad".encodeToByteArray()) }
        }
        assertThrows(TamperedDataException::class.java) { LockCrypto.open(key, sealed.copyOf(sealed.size - 1), "aad".encodeToByteArray()) }
        assertThrows(TamperedDataException::class.java) { LockCrypto.open(key, sealed, "other".encodeToByteArray()) }
        assertThrows(TamperedDataException::class.java) { LockCrypto.open(key, ByteArray(3)) }
    }

    @Test fun noncesAreFresh() {
        val key = LockCrypto.newKey()
        assertThat(LockCrypto.seal(key, byteArrayOf(1))).isNotEqualTo(LockCrypto.seal(key, byteArrayOf(1)))
    }

    @Test fun noteKeyIsBoundToNoteId() {
        val master = LockCrypto.newKey()
        val dek = LockCrypto.newKey()
        val wrapped = LockCrypto.wrapNoteKey(master, "a", dek)
        assertThat(LockCrypto.unwrapNoteKey(master, "a", wrapped)).isEqualTo(dek)
        assertThrows(TamperedDataException::class.java) { LockCrypto.unwrapNoteKey(master, "b", wrapped) }
        assertThrows(TamperedDataException::class.java) { LockCrypto.unwrapNoteKey(LockCrypto.newKey(), "a", wrapped) }
    }

    @Test fun payloadIsBoundToNoteId() {
        val dek = LockCrypto.newKey()
        val sealed = LockCrypto.sealPayload(dek, "n1", "hello".encodeToByteArray())
        assertThat(LockCrypto.openPayload(dek, "n1", sealed).decodeToString()).isEqualTo("hello")
        assertThrows(TamperedDataException::class.java) { LockCrypto.openPayload(dek, "n2", sealed) }
    }

    @Test fun pbkdf2IsDeterministicAndSaltSensitive() {
        val salt = ByteArray(16) { it.toByte() }
        val a = LockCrypto.deriveKek("pw".toCharArray(), salt, fastIterations)
        assertThat(a).isEqualTo(LockCrypto.deriveKek("pw".toCharArray(), salt, fastIterations))
        assertThat(a).isNotEqualTo(LockCrypto.deriveKek("pw".toCharArray(), ByteArray(16) { 9 }, fastIterations))
        assertThat(a.size).isEqualTo(32)
    }

    // A stand-in for the Keystore key: a plain software AES key used through the same Cipher API.
    @Test fun cipherWrapRoundTrip() {
        val key = SecretKeySpec(LockCrypto.newKey(), "AES")
        val master = LockCrypto.newKey()
        val enc = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, key) }
        val wrapped = LockCrypto.wrapWithCipher(enc, master)
        val (iv, _) = LockCrypto.splitCipherWrap(wrapped)
        val dec = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        assertThat(LockCrypto.unwrapWithCipher(dec, wrapped)).isEqualTo(master)
    }
}
