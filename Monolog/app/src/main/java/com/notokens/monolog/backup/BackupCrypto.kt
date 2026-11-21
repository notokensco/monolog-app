package com.notokens.monolog.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class BackupSecrets(val key: ByteArray, val recovery: ByteArray) {
    fun recoveryCode(): String = recovery.joinToString("") { "%02X".format(it) }.chunked(8).joinToString("-")
}

/** Version 1 authenticates the owner, year, wrapped key and payload with AES-256-GCM. */
internal object BackupCrypto {
    const val MAX_BYTES = 128 * 1024 * 1024
    private val random = SecureRandom()
    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    fun create() = BackupSecrets(randomBytes(32), randomBytes(32))
    fun recoveryBytes(code: String): ByteArray {
        val normalized = code.replace("-", "").replace(" ", "").trim()
        require(normalized.matches(Regex("[0-9a-fA-F]{64}"))) { "Enter the complete recovery code." }
        return normalized.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun encrypt(payload: ByteArray, owner: String, year: Int, secrets: BackupSecrets): ByteArray {
        require(payload.size <= MAX_BYTES)
        val salt = randomBytes(16)
        val wrapIv = randomBytes(12)
        val dataIv = randomBytes(12)
        val aad = aad(owner, year)
        val wrapped = crypt(Cipher.ENCRYPT_MODE, secrets.key, recoveryKey(secrets.recovery, salt), wrapIv, aad)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, payload, secrets.key, dataIv, aad + wrapped)
        return ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(0x4D4F4E42)
                it.writeInt(1)
                it.writeInt(year)
                it.write(salt)
                it.write(wrapIv)
                it.write(wrapped)
                it.write(dataIv)
                it.writeInt(encrypted.size)
                it.write(encrypted)
            }
        }.toByteArray()
    }

    fun decrypt(bytes: ByteArray, owner: String, year: Int, recovery: ByteArray): Pair<ByteArray, BackupSecrets> {
        require(bytes.size <= MAX_BYTES + 256) { "Backup is too large." }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 0x4D4F4E42 && input.readInt() == 1) { "Unsupported backup format." }
            require(input.readInt() == year) { "Backup year does not match." }
            fun read(size: Int) = ByteArray(size).also(input::readFully)
            val salt = read(16)
            val wrapIv = read(12)
            val wrapped = read(48)
            val dataIv = read(12)
            val length = input.readInt()
            require(length in 16..(MAX_BYTES + 16) && length == input.available()) { "Incomplete backup." }
            val aad = aad(owner, year)
            val key = crypt(Cipher.DECRYPT_MODE, wrapped, recoveryKey(recovery, salt), wrapIv, aad)
            crypt(Cipher.DECRYPT_MODE, read(length), key, dataIv, aad + wrapped) to BackupSecrets(key, recovery.copyOf())
        }
    }

    private fun aad(owner: String, year: Int) = "Monolog backup v1|$owner|$year".toByteArray()
    private fun recoveryKey(recovery: ByteArray, salt: ByteArray): ByteArray {
        require(recovery.size == 32)
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(recovery, "HmacSHA256"))
            doFinal("Monolog recovery v1".toByteArray() + salt)
        }
    }
    private fun crypt(mode: Int, data: ByteArray, key: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(aad)
            doFinal(data)
        }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
