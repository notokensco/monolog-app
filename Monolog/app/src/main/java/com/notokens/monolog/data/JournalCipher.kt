package com.notokens.monolog.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object JournalCipher {
    private const val keyAlias = "monolog-journal-v1"
    private const val ivSize = 12
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    fun seal(text: String, field: String): String = Base64.encodeToString(
        seal(text.toByteArray(Charsets.UTF_8), field), Base64.NO_WRAP
    )

    fun open(text: String, field: String): String =
        open(Base64.decode(text, Base64.NO_WRAP), field).toString(Charsets.UTF_8)

    fun seal(bytes: ByteArray, field: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == ivSize)
        return cipher.iv + cipher.doFinal(bytes)
    }

    fun open(bytes: ByteArray, field: String): ByteArray {
        require(bytes.size >= ivSize + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, ivSize))
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, ivSize, bytes.size - ivSize)
    }

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        check(create) { "Journal encryption key is unavailable." }
        val options = KeyGenParameterSpec.Builder(
            keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(options)
            generateKey()
        }
    }
}
