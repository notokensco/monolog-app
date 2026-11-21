package com.notokens.monolog.backup

import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    @Test fun secondDeviceRecoversBothThePayloadAndBackupKey() {
        val secrets = BackupCrypto.create()
        val bytes = BackupCrypto.encrypt("synthetic journal".toByteArray(), "test-owner", 2026, secrets)
        val (plain, recovered) = BackupCrypto.decrypt(bytes, "test-owner", 2026,
            BackupCrypto.recoveryBytes(secrets.recoveryCode()))
        assertEquals("synthetic journal", plain.toString(Charsets.UTF_8))
        assertArrayEquals(secrets.key, recovered.key)
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("synthetic journal"))
    }

    @Test fun wrongCodeOwnerYearAndTamperedBytesFailAuthentication() {
        val secrets = BackupCrypto.create()
        val bytes = BackupCrypto.encrypt("private".toByteArray(), "test-owner", 2025, secrets)
        rejects { BackupCrypto.decrypt(bytes, "test-owner", 2025, BackupCrypto.create().recovery) }
        rejects { BackupCrypto.decrypt(bytes, "another-owner", 2025, secrets.recovery) }
        rejects { BackupCrypto.decrypt(bytes, "test-owner", 2026, secrets.recovery) }
        val damaged = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rejects { BackupCrypto.decrypt(damaged, "test-owner", 2025, secrets.recovery) }
        rejects { BackupCrypto.decrypt(bytes.copyOf(bytes.size - 5), "test-owner", 2025, secrets.recovery) }
    }

    @Test fun repeatedBackupsUseFreshNoncesAndRecoverWithTheSameCode() {
        val secrets = BackupCrypto.create()
        val one = BackupCrypto.encrypt("private".toByteArray(), "test-owner", 2026, secrets)
        val two = BackupCrypto.encrypt("private".toByteArray(), "test-owner", 2026, secrets)
        assertFalse(one.contentEquals(two))
        assertArrayEquals(BackupCrypto.decrypt(one, "test-owner", 2026, secrets.recovery).first,
            BackupCrypto.decrypt(two, "test-owner", 2026, secrets.recovery).first)
    }

    private fun rejects(work: () -> Unit) {
        try { work(); fail("Invalid backup was accepted") } catch (_: Exception) { }
    }
}
