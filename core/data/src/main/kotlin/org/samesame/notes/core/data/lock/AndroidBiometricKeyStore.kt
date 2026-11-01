package org.samesame.notes.core.data.lock

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.samesame.notes.core.security.BiometricKeyStore
import org.samesame.notes.core.security.LockCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore AES-256-GCM key that needs a fresh strong-biometric authentication for every use
 * (BiometricPrompt CryptoObject) and is destroyed by the system when biometrics are enrolled or removed.
 * Device-only: not exercisable on the JVM, see docs/checkpoints/locked-notes.md for the device checks.
 */
@Singleton
internal class AndroidBiometricKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : BiometricKeyStore {

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    override fun newEnrollCipher(): Cipher? = runCatching {
        deleteKey()
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(builder.build()) }.generateKey()
        Cipher.getInstance(TRANSFORM).also { it.init(Cipher.ENCRYPT_MODE, key()) }
    }.getOrNull()

    override fun unlockCipher(wrapped: ByteArray): Cipher? {
        val key = key() ?: return null
        return try {
            val (iv, _) = LockCrypto.splitCipherWrap(wrapped)
            Cipher.getInstance(TRANSFORM).also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(LockCrypto.TAG_BITS, iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteKey()
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun deleteKey() {
        runCatching { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun key(): SecretKey? = runCatching {
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(ALIAS, null) as? SecretKey
    }.getOrNull()

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "samesame.notes.lock.biometric.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
