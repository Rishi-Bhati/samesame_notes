package org.samesame.notes.core.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.samesame.notes.core.security.BiometricKeyStore
import org.samesame.notes.core.security.LockConfigStorage
import org.samesame.notes.core.security.LockCrypto
import org.samesame.notes.core.security.LockVault
import org.samesame.notes.core.security.WrongPasswordException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LockRepositoryTest {
    private class Memory : LockConfigStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes }
    }

    /** Software stand-in for the Keystore key; [invalidate] simulates "biometrics were re-enrolled". */
    private class FakeKeys : BiometricKeyStore {
        var key: SecretKeySpec? = null
        var invalidated = false
        override fun isAvailable() = true
        override fun newEnrollCipher(): Cipher {
            key = SecretKeySpec(LockCrypto.newKey(), "AES")
            invalidated = false
            return Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, key) }
        }
        override fun unlockCipher(wrapped: ByteArray): Cipher? {
            if (invalidated || key == null) return null
            val (iv, _) = LockCrypto.splitCipherWrap(wrapped)
            return Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        }
        override fun deleteKey() { key = null }
    }

    private fun repo(storage: LockConfigStorage = Memory(), keys: FakeKeys = FakeKeys()) =
        DefaultLockRepository(LockVault(storage, iterations = 1_000), keys)

    @Test fun passwordFlow() = runBlocking<Unit> {
        val r = repo()
        assertThat(r.hasPassword.first()).isFalse()
        r.createPassword("abcdef".toCharArray()).close()
        assertThat(r.hasPassword.first()).isTrue()
        r.verifyPassword("abcdef".toCharArray()).close()
        assertThrows(WrongPasswordException::class.java) { runBlocking { r.verifyPassword("abcdeg".toCharArray()) } }
    }

    @Test fun invalidPasswordsAreRejected() {
        assertThat(LockRepository.isValidPassword("abc".toCharArray())).isFalse()
        assertThat(LockRepository.isValidPassword("abcde".toCharArray())).isFalse() // minimum raised to 6
        assertThat(LockRepository.isValidPassword("abcdef".toCharArray())).isTrue()
        assertThat(LockRepository.isValidPassword("a".repeat(17).toCharArray())).isTrue()
        assertThat(LockRepository.isValidPassword("a".repeat(18).toCharArray())).isFalse()
        assertThat(LockRepository.isValidPassword("abc\ndef".toCharArray())).isFalse()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo().createPassword("abc".toCharArray()) } }
    }

    @Test fun biometricUnlockAndAutoResetWhenKeyIsInvalidated() = runBlocking<Unit> {
        val keys = FakeKeys()
        val r = repo(keys = keys)
        val token = r.createPassword("abcdef".toCharArray())
        val master = token.masterKey()
        val enroll = r.biometricEnrollCipher()!!
        r.completeBiometricEnroll(token, enroll)
        assertThat(r.biometricsEnabled.first()).isTrue()

        val unlock = r.biometricUnlockCipher()!!
        assertThat(r.completeBiometricUnlock(unlock).use { it.masterKey() }).isEqualTo(master)

        keys.invalidated = true // biometrics re-enrolled
        assertThat(r.biometricUnlockCipher()).isNull()
        assertThat(r.biometricsEnabled.first()).isFalse()
        assertThat(r.consumeBiometricReset()).isTrue()
        assertThat(r.consumeBiometricReset()).isFalse()
        // The password still works.
        r.verifyPassword("abcdef".toCharArray()).close()
    }

    @Test fun changePasswordKeepsBiometrics() = runBlocking<Unit> {
        val r = repo()
        val token = r.createPassword("abcdef".toCharArray())
        r.completeBiometricEnroll(token, r.biometricEnrollCipher()!!)
        r.changePassword("abcdef".toCharArray(), "uvwxyz".toCharArray())
        assertThat(r.biometricsEnabled.first()).isTrue()
        assertThat(r.biometricUnlockCipher()).isNotNull()
    }
}
