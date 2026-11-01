package org.samesame.notes.feature.lock

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.data.repository.LockRepository
import org.samesame.notes.core.security.LockToken
import org.samesame.notes.core.security.LockedOutException
import org.samesame.notes.core.security.WrongPasswordException
import javax.crypto.Cipher
import javax.inject.Inject

/** Thin UI-facing wrapper over [LockRepository]; passwords are converted to char arrays and wiped after use. */
@HiltViewModel
class LockViewModel @Inject constructor(private val repo: LockRepository) : ViewModel() {
    val hasPassword: Flow<Boolean> = repo.hasPassword
    val biometricsEnabled: Flow<Boolean> = repo.biometricsEnabled

    fun biometricsAvailable(): Boolean = repo.biometricsAvailable()
    fun biometricUnlockCipher(): Cipher? = repo.biometricUnlockCipher()
    fun biometricEnrollCipher(): Cipher? = repo.biometricEnrollCipher()
    fun consumeBiometricReset(): Boolean = repo.consumeBiometricReset()
    fun lockoutRemainingMs(): Long = repo.lockoutRemainingMs()

    suspend fun createPassword(password: String): LockToken = withChars(password) { repo.createPassword(it) }

    /** The token, [VerifyResult.Wrong], or [VerifyResult.LockedOut] while wrong passwords are throttled. */
    suspend fun verify(password: String): VerifyResult =
        try {
            VerifyResult.Ok(withChars(password) { repo.verifyPassword(it) })
        } catch (e: WrongPasswordException) {
            VerifyResult.Wrong
        } catch (e: LockedOutException) {
            VerifyResult.LockedOut(e.remainingMs)
        } catch (e: java.security.GeneralSecurityException) {
            VerifyResult.Wrong // the device key could not be used: never crash the flow
        }

    /** True on success, false when [old] is wrong (or attempts are throttled). */
    suspend fun changePassword(old: String, new: String): Boolean {
        val o = old.toCharArray()
        val n = new.toCharArray()
        return try {
            repo.changePassword(o, n)
            true
        } catch (e: java.security.GeneralSecurityException) {
            false
        } finally {
            o.fill('\u0000'); n.fill('\u0000')
        }
    }

    suspend fun completeBiometricUnlock(cipher: Cipher): LockToken = repo.completeBiometricUnlock(cipher)
    suspend fun completeBiometricEnroll(token: LockToken, cipher: Cipher) = repo.completeBiometricEnroll(token, cipher)
    suspend fun disableBiometrics() = repo.disableBiometrics()

    private suspend fun <T> withChars(s: String, block: suspend (CharArray) -> T): T {
        val chars = s.toCharArray()
        try {
            return block(chars)
        } finally {
            chars.fill('\u0000')
        }
    }
}

/** Outcome of checking a password. */
sealed interface VerifyResult {
    class Ok(val token: LockToken) : VerifyResult
    data object Wrong : VerifyResult
    /** Too many wrong passwords: the next attempt is allowed in [remainingMs]. */
    class LockedOut(val remainingMs: Long) : VerifyResult
}
