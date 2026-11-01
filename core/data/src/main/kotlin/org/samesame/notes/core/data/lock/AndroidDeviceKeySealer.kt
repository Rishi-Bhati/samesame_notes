package org.samesame.notes.core.data.lock

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.samesame.notes.core.security.DeviceKeySealer
import org.samesame.notes.core.security.LockCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Non-exportable Android Keystore AES-256-GCM key (StrongBox when the device has one) that binds the lock config
 * to this device: `lock.bin` copied elsewhere cannot be brute-forced offline. No user authentication is needed,
 * so the password stays the only credential. Device-only (the Keystore is not available on the JVM).
 * The key is created on first use and lives as long as the app's data (it is removed on uninstall).
 */
internal class AndroidDeviceKeySealer : DeviceKeySealer {

    override fun seal(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).also { it.init(Cipher.ENCRYPT_MODE, key() ?: createKey()) }
        return LockCrypto.wrapWithCipher(cipher, data)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val key = key() ?: throw org.samesame.notes.core.security.TamperedDataException()
        val (iv, _) = LockCrypto.splitCipherWrap(sealed)
        val cipher = Cipher.getInstance(TRANSFORM).also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(LockCrypto.TAG_BITS, iv)) }
        return LockCrypto.unwrapWithCipher(cipher, sealed)
    }

    private fun key(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(ALIAS, null) as? SecretKey

    private fun createKey(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            generator.init(spec(strongBox = Build.VERSION.SDK_INT >= 28))
            generator.generateKey()
        } catch (e: Exception) {
            // No (working) StrongBox: fall back to the TEE-backed key.
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "samesame.notes.lock.device.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
