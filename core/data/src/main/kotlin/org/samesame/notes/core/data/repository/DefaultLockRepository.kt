package org.samesame.notes.core.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.samesame.notes.core.security.BiometricKeyStore
import org.samesame.notes.core.security.LockToken
import org.samesame.notes.core.security.LockVault
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultLockRepository @Inject constructor(
    private val vault: LockVault,
    private val keys: BiometricKeyStore,
) : LockRepository {
    private val password = MutableStateFlow(runCatching { vault.hasPassword() }.getOrDefault(false))
    private val biometrics = MutableStateFlow(runCatching { vault.hasBiometric() }.getOrDefault(false))
    private val reset = AtomicBoolean(false)

    override val hasPassword: Flow<Boolean> get() = password
    override val biometricsEnabled: Flow<Boolean> get() = biometrics

    override fun biometricsAvailable(): Boolean = keys.isAvailable()

    override suspend fun createPassword(password: CharArray): LockToken = withContext(Dispatchers.Default) {
        require(LockRepository.isValidPassword(password)) { "Invalid password" }
        vault.createPassword(password).also { this@DefaultLockRepository.password.value = true }
    }

    override suspend fun verifyPassword(password: CharArray): LockToken =
        withContext(Dispatchers.Default) { org.samesame.notes.core.data.traced("lock:verifyPassword") { vault.verifyPassword(password) } }

    override fun lockoutRemainingMs(): Long = runCatching { vault.lockoutRemainingMs() }.getOrDefault(0L)

    override suspend fun changePassword(old: CharArray, new: CharArray) = withContext(Dispatchers.Default) {
        require(LockRepository.isValidPassword(new)) { "Invalid password" }
        vault.changePassword(old, new)
    }

    override fun biometricUnlockCipher(): Cipher? {
        val wrapped = vault.biometricWrapped() ?: return null
        val cipher = keys.unlockCipher(wrapped)
        if (cipher == null) {
            // The Keystore key is gone (biometrics enrolled/removed): turn the feature off like the reference does.
            vault.disableBiometric()
            keys.deleteKey()
            biometrics.value = false
            reset.set(true)
        }
        return cipher
    }

    override suspend fun completeBiometricUnlock(cipher: Cipher): LockToken =
        withContext(Dispatchers.Default) { vault.tokenFromBiometric(cipher) }

    override fun biometricEnrollCipher(): Cipher? = if (vault.hasPassword()) keys.newEnrollCipher() else null

    override suspend fun completeBiometricEnroll(token: LockToken, cipher: Cipher) = withContext(Dispatchers.Default) {
        vault.enableBiometric(token, cipher)
        biometrics.value = true
    }

    override suspend fun disableBiometrics() = withContext(Dispatchers.Default) {
        vault.disableBiometric()
        keys.deleteKey()
        biometrics.value = false
    }

    override fun consumeBiometricReset(): Boolean = reset.getAndSet(false)
}
