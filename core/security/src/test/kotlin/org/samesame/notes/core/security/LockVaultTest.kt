package org.samesame.notes.core.security

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LockVaultTest {
    private class MemoryStorage : LockConfigStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(bytes: ByteArray) { this.bytes = bytes }
    }

    private fun vault(storage: LockConfigStorage = MemoryStorage()) = LockVault(storage, iterations = 1_000)

    @Test fun createThenVerifyGivesSameMaster() {
        val storage = MemoryStorage()
        val master = vault(storage).createPassword("abcd".toCharArray()).use { it.masterKey() }
        // A fresh vault instance (process restart) reads the persisted config.
        val again = vault(storage)
        assertThat(again.hasPassword()).isTrue()
        assertThat(again.verifyPassword("abcd".toCharArray()).use { it.masterKey() }).isEqualTo(master)
    }

    @Test fun wrongPasswordThrows() {
        val v = vault()
        v.createPassword("abcd".toCharArray()).close()
        assertThrows(WrongPasswordException::class.java) { v.verifyPassword("abce".toCharArray()) }
    }

    @Test fun changePasswordKeepsMasterKey() {
        val v = vault()
        val master = v.createPassword("old1".toCharArray()).use { it.masterKey() }
        assertThrows(WrongPasswordException::class.java) { v.changePassword("nope".toCharArray(), "new1".toCharArray()) }
        v.changePassword("old1".toCharArray(), "new1".toCharArray())
        assertThrows(WrongPasswordException::class.java) { v.verifyPassword("old1".toCharArray()) }
        assertThat(v.verifyPassword("new1".toCharArray()).use { it.masterKey() }).isEqualTo(master)
    }

    @Test fun closedTokenIsUnusable() {
        val t = vault().createPassword("abcd".toCharArray())
        t.close()
        assertThrows(IllegalStateException::class.java) { t.masterKey() }
    }

    @Test fun biometricWrapRoundTripAndPasswordChangeKeepsIt() {
        val v = vault()
        val token = v.createPassword("abcd".toCharArray())
        val key = SecretKeySpec(LockCrypto.newKey(), "AES")
        val enc = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, key) }
        v.enableBiometric(token, enc)
        assertThat(v.hasBiometric()).isTrue()
        v.changePassword("abcd".toCharArray(), "efgh".toCharArray())
        assertThat(v.hasBiometric()).isTrue()
        val (iv, _) = LockCrypto.splitCipherWrap(v.biometricWrapped()!!)
        val dec = Cipher.getInstance("AES/GCM/NoPadding").also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        assertThat(v.tokenFromBiometric(dec).use { it.masterKey() }).isEqualTo(token.masterKey())
        v.disableBiometric()
        assertThat(v.hasBiometric()).isFalse()
    }

    @Test fun corruptedConfigFailsLoudly() {
        val s = MemoryStorage().also { it.bytes = byteArrayOf(1, 2, 3, 4, 5) }
        assertThrows(IllegalStateException::class.java) { vault(s).hasPassword() }
    }
}
