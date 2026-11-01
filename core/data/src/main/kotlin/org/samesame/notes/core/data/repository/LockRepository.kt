package org.samesame.notes.core.data.repository

import kotlinx.coroutines.flow.Flow
import org.samesame.notes.core.security.LockToken
import javax.crypto.Cipher

/** Max password length (the reference's create-password field limit). */
const val LOCK_PASSWORD_MAX_LENGTH = 17
/** Minimum length of a new password (raised from 4 on 2026-10-30, security review M4). */
const val LOCK_PASSWORD_MIN_LENGTH = 6
/** Shortest password that can exist (created before the minimum was raised): what the verify pages accept. */
const val LOCK_PASSWORD_LEGACY_MIN_LENGTH = 4

/**
 * The lock password and biometric unlock. Operations that need a credential return a [LockToken]; close
 * it as soon as the lock / unlock / open operation it was obtained for is done.
 */
interface LockRepository {
    /** Whether a lock password exists (the "Note unlock methods" row is shown once it does). */
    val hasPassword: Flow<Boolean>

    /** Whether unlocking with biometrics is switched on. */
    val biometricsEnabled: Flow<Boolean>

    /** Strong biometrics are present and enrolled on this device. */
    fun biometricsAvailable(): Boolean

    /** Creates the first password. Throws IllegalArgumentException for an invalid password. */
    suspend fun createPassword(password: CharArray): LockToken

    /**
     * @throws org.samesame.notes.core.security.WrongPasswordException
     * @throws org.samesame.notes.core.security.LockedOutException after too many wrong passwords (not checked then)
     */
    suspend fun verifyPassword(password: CharArray): LockToken

    /** Milliseconds until another password attempt is allowed (0 = now). */
    fun lockoutRemainingMs(): Long

    /** @throws org.samesame.notes.core.security.WrongPasswordException when [old] is wrong. */
    suspend fun changePassword(old: CharArray, new: CharArray)

    /** Cipher for a BiometricPrompt CryptoObject, or null when biometrics are off or the key was invalidated. */
    fun biometricUnlockCipher(): Cipher?

    /** Turns the stored token out of an authenticated unlock cipher. */
    suspend fun completeBiometricUnlock(cipher: Cipher): LockToken

    /** Cipher to authenticate for switching biometrics on (needs a verified [token] afterwards). */
    fun biometricEnrollCipher(): Cipher?

    suspend fun completeBiometricEnroll(token: LockToken, cipher: Cipher)

    suspend fun disableBiometrics()

    /** True once after biometrics were reset because the enrolled biometrics changed. */
    fun consumeBiometricReset(): Boolean

    companion object {
        fun isValidPassword(password: CharArray): Boolean =
            password.size in LOCK_PASSWORD_MIN_LENGTH..LOCK_PASSWORD_MAX_LENGTH && password.none { it.isISOControl() }
    }
}
