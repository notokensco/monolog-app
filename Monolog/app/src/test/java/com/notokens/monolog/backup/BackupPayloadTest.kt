package com.notokens.monolog.backup

import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BackupPayloadTest {
    private val time = Instant.parse("2025-03-01T12:00:00Z").toEpochMilli()
    private fun entry() = BackupEntry(UUID.randomUUID().toString(), "Synthetic note", "A private test",
        time, time + 1000, "journal", listOf(byteArrayOf(1, 2, 3)))

    @Test fun versionedPayloadKeepsPhotosDatesAndStableRecordIds() {
        val entry = entry()
        val payload = BackupPayload("test-owner", 2025, listOf(entry))
        val restored = BackupPayload.decode(payload.encode(), "test-owner", 2025).entries.single()
        assertEquals(entry.id, restored.id)
        assertEquals(entry.digest(), restored.digest())
        assertArrayEquals(entry.photos.single(), restored.photos.single())
    }

    @Test fun editedRecordOrAttachmentHasADifferentVersionHash() {
        val entry = entry()
        assertNotEquals(entry.digest(), entry.copy(body = "An edited local note").digest())
        assertNotEquals(entry.digest(), entry.copy(photos = listOf(byteArrayOf(4))).digest())
    }

    @Test fun wrongOwnerWrongYearAndDuplicateVersionsAreRejected() {
        val entry = entry()
        val bytes = BackupPayload("test-owner", 2025, listOf(entry)).encode()
        rejects { BackupPayload.decode(bytes, "other-owner", 2025) }
        rejects { BackupPayload.decode(bytes, "test-owner", 2026) }
        rejects { BackupPayload.decode(BackupPayload("test-owner", 2025, listOf(entry, entry)).encode(), "test-owner", 2025) }
    }

    private fun rejects(work: () -> Unit) {
        try { work(); fail("Invalid payload was accepted") } catch (_: Exception) { }
    }
}
