package com.notokens.monolog.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class BackupKeyStore(private val context: Context) {
    private fun file(owner: String) = AtomicFile(File(context.noBackupFilesDir,
        "drive-backup-" + BackupCrypto.digest(owner.toByteArray()) + ".key"))
    fun hasKey(owner: String) = file(owner).baseFile.isFile
    fun save(owner: String, secrets: BackupSecrets) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey())
        cipher.updateAAD(owner.toByteArray())
        val bytes = cipher.iv + cipher.doFinal(secrets.key + secrets.recovery)
        val target = file(owner)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }
    fun load(owner: String): BackupSecrets {
        val bytes = file(owner).readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deviceKey(), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(owner.toByteArray())
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        require(plain.size == 64)
        return BackupSecrets(plain.copyOfRange(0, 32), plain.copyOfRange(32, 64))
    }
    @Synchronized private fun deviceKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "monolog-drive-backup-wrapper-v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}
