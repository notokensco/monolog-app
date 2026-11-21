package com.notokens.monolog.backup

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notokens.monolog.data.JournalCipher
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.data.Note
import com.notokens.monolog.data.NotePhoto
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against isolated in-memory Room databases and a fake Drive transport. */
@RunWith(AndroidJUnit4::class)
class BackupCoordinatorTest {
    private lateinit var context: Context
    private lateinit var source: MonologDatabase
    private lateinit var destination: MonologDatabase
    private lateinit var store: BackupAccountStore
    private lateinit var account: BackupAccount
    private lateinit var secrets: BackupSecrets
    private lateinit var drive: MemoryDrive
    private val year = LocalDate.now().year

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        source = Room.inMemoryDatabaseBuilder(context, MonologDatabase::class.java).build()
        destination = Room.inMemoryDatabaseBuilder(context, MonologDatabase::class.java).build()
        store = BackupAccountStore.get(context)
        store.select("synthetic-" + UUID.randomUUID(), "synthetic@example.invalid", "Synthetic test")
        store.connected(store.state.value.session)
        account = store.state.value
        secrets = BackupCrypto.create()
        BackupKeyStore(context).save(account.id, secrets)
        drive = MemoryDrive()
    }

    @After fun cleanup() {
        source.close()
        destination.close()
        store.signOut()
    }

    @Test fun attachmentRestoresWithRecoveryCodeAndRepeatedRestoreKeepsLocalEdit() = runBlocking {
        val id = addSynthetic(source, photo = true)
        val sourceCoordinator = coordinator(source)
        assertEquals(1, sourceCoordinator.backUp(account))
        assertFalse(drive.bytes.values.single().toString(Charsets.ISO_8859_1).contains("Synthetic attachment"))
        val target = coordinator(destination)
        val report = target.restore(account, drive.list(), secrets.recoveryCode())
        assertEquals(1, report.imported)
        val restored = destination.notes().observeAll().first().single()
        assertEquals("Synthetic attachment", JournalCipher.open(restored.title, "note-title"))
        val photoRef = destination.photos().observeRefs().first().single()
        assertArrayEquals(JournalCipher.open(source.photos().image(source.photos().observeRefs(id).first().single().id)!!, "note-photo"),
            JournalCipher.open(destination.photos().image(photoRef.id)!!, "note-photo"))
        destination.notes().update(restored.copy(body = JournalCipher.seal("A local edit", "note-body")))
        assertEquals(1, target.restore(account, drive.list(), secrets.recoveryCode()).imported)
        assertEquals("A local edit", JournalCipher.open(destination.notes().observeAll().first()
            .first { it.id == restored.id }.body, "note-body"))
        assertEquals(0, target.restore(account, drive.list(), secrets.recoveryCode()).imported)
        assertEquals(2, destination.notes().observeAll().first().size)
    }

    @Test fun conflictPreservesBothVersionsAndRepeatDoesNotDuplicateThem() = runBlocking {
        addSynthetic(source)
        coordinator(source).backUp(account)
        val first = drive.list().single()
        val target = coordinator(destination)
        target.restore(account, listOf(first), secrets.recoveryCode())
        val local = destination.notes().observeAll().first().single()
        destination.notes().update(local.copy(body = JournalCipher.seal("Edited on second phone", "note-body"),
            updatedAt = local.updatedAt + 1000))
        val original = source.notes().observeAll().first().single()
        source.notes().update(original.copy(body = JournalCipher.seal("Edited on first phone", "note-body"),
            updatedAt = original.updatedAt + 2000))
        coordinator(source).backUp(account)
        val latest = drive.list().last()
        val report = target.restore(account, listOf(latest), secrets.recoveryCode())
        assertEquals(1, report.conflicts)
        assertEquals(2, destination.notes().observeAll().first().size)
        assertEquals(0, target.restore(account, listOf(latest), secrets.recoveryCode()).imported)
        assertEquals(2, destination.notes().observeAll().first().size)
    }

    @Test fun wrongCodeAndInterruptedDownloadKeepExistingDataIntact() = runBlocking {
        addSynthetic(source)
        coordinator(source).backUp(account)
        addSynthetic(destination)
        try {
            coordinator(destination).restore(account, drive.list(), BackupCrypto.create().recoveryCode())
            fail("Wrong code succeeded")
        } catch (_: javax.crypto.AEADBadTagException) { }
        assertEquals(1, destination.notes().observeAll().first().size)
        drive.pause = CompletableDeferred()
        drive.downloadStarted = CompletableDeferred()
        val operation = async {
            coordinator(destination).restore(account, drive.list(), secrets.recoveryCode())
        }
        drive.downloadStarted.await()
        operation.cancelAndJoin()
        assertEquals(1, destination.notes().observeAll().first().size)
    }

    @Test fun failedUploadAndRevokedConsentDoNotArchiveLocalNotes() = runBlocking {
        addSynthetic(source)
        drive.failUpload = true
        try { coordinator(source).backUp(account); fail("Upload should fail") } catch (_: IOException) { }
        assertEquals(0L, store.state.value.lastBackup)
        assertTrue(source.backups().archives(account.id).first().isEmpty())
        try {
            BackupCoordinator(context, source) { throw DriveConsentRequired() }.backUp(account)
            fail("Revoked grant should fail")
        } catch (_: DriveConsentRequired) { }
        assertTrue(store.state.value.reconnect)
        assertEquals(1, source.notes().observeAll().first().size)
    }

    @Test fun accountSwitchDuringUploadCannotMarkAnotherAccountBackedUp() = runBlocking {
        addSynthetic(source)
        drive.onUpload = { store.select("other-owner", "other@example.invalid", "Other test") }
        try { coordinator(source).backUp(account); fail("Stale account succeeded") }
        catch (_: IllegalStateException) { }
        assertEquals(0L, store.state.value.lastBackup)
        assertFalse(store.state.value.connected)
        assertTrue(source.backups().archives(account.id).first().isEmpty())
        assertEquals(1, source.notes().observeAll().first().size)
    }

    @Test fun versionThreeJournalMigratesWithoutLosingEncryptedEntries() = runBlocking {
        val name = "synthetic-backup-migration-${UUID.randomUUID()}.db"
        val encryptedTitle = JournalCipher.seal("Migration sample", "note-title")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            old.execSQL("CREATE TABLE notes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE reminders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, dueAt INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE note_photos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, noteId INTEGER NOT NULL, image BLOB NOT NULL, FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE)")
            old.execSQL("CREATE INDEX index_note_photos_noteId ON note_photos(noteId)")
            old.execSQL("INSERT INTO notes (title, body, createdAt, updatedAt) VALUES (?, ?, 1000, 1000)",
                arrayOf(encryptedTitle, JournalCipher.seal("Existing guest data", "note-body")))
            old.version = 3
        }
        val migrated = Room.databaseBuilder(context, MonologDatabase::class.java, name)
            .addMigrations(MonologDatabase.backupMigration).build()
        try {
            assertEquals(encryptedTitle, migrated.notes().observeAll().first().single().title)
            assertTrue(migrated.backups().records().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun coordinator(db: MonologDatabase) = BackupCoordinator(context, db) { drive }
    private suspend fun addSynthetic(db: MonologDatabase, photo: Boolean = false): Long {
        val time = LocalDate.of(year, 1, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val id = db.notes().insert(Note(title = JournalCipher.seal("Synthetic attachment", "note-title"),
            body = JournalCipher.seal("Backup verification only", "note-body"), createdAt = time, updatedAt = time))
        if (photo) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle()
            db.photos().insert(listOf(NotePhoto(noteId = id, image = JournalCipher.seal(bytes, "note-photo"))))
        }
        return id
    }

    private class MemoryDrive : DriveStorage {
        val bytes = linkedMapOf<String, ByteArray>()
        var failUpload = false
        var onUpload: () -> Unit = {}
        var pause: CompletableDeferred<Unit>? = null
        var downloadStarted = CompletableDeferred<Unit>()
        override suspend fun list() = bytes.keys.map { DriveBackup(it, LocalDate.now().year, "", it) }
        override suspend fun upload(year: Int, bytes: ByteArray) {
            if (failUpload) throw IOException("Synthetic failed upload")
            this.bytes[UUID.randomUUID().toString()] = bytes
            onUpload()
        }
        override suspend fun download(file: DriveBackup): ByteArray {
            downloadStarted.complete(Unit)
            pause?.await()
            return bytes.getValue(file.id)
        }
    }
}
