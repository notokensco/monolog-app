package com.notokens.monolog.backup

import android.content.Context
import androidx.room.withTransaction
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.data.BackupArchive
import com.notokens.monolog.data.BackupRecord
import com.notokens.monolog.data.BackupVersion
import com.notokens.monolog.data.JournalCipher
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.data.Note
import com.notokens.monolog.data.NotePhoto
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class BackupSnapshot(val payloads: List<BackupPayload>, val archives: List<BackupArchive>)
internal data class RestoreReport(val imported: Int, val skipped: Int, val conflicts: Int)

internal class BackupCoordinator(
    private val context: Context,
    private val db: MonologDatabase = MonologDatabase.get(context),
    private val driveProvider: suspend (BackupAccount) -> DriveStorage = {
        DriveFiles(GoogleBackupAuth.accessToken(context, it), sourceId(context),
            "${it.journal}-${it.context}-${it.attachments}")
    }
) {
    private val accounts = BackupAccountStore.get(context)
    private val keys = BackupKeyStore(context)

    suspend fun files(account: BackupAccount): List<DriveBackup> {
        requireConnected(account)
        return authorized(account) { it.list() }
    }

    suspend fun backUp(account: BackupAccount): Int = operations.withLock {
        requireConnected(account)
        require(account.journal || account.context) { "Select journal entries or saved context." }
        val secrets = withContext(Dispatchers.IO) { keys.load(account.id) }
        val snapshot = snapshot(account)
        require(snapshot.payloads.isNotEmpty()) { "There are no selected entries to back up." }
        authorized(account) { drive ->
            for (payload in snapshot.payloads) {
                accounts.requireSession(account.session)
                val bytes = withContext(Dispatchers.Default) {
                    BackupCrypto.encrypt(payload.encode(), account.id, payload.year, secrets)
                }
                drive.upload(payload.year, bytes)
            }
        }
        accounts.requireSession(account.session)
        currentCoroutineContext().ensureActive()
        db.withTransaction {
            accounts.requireSession(account.session)
            db.backups().archive(snapshot.archives)
        }
        accounts.success(account.session)
        snapshot.payloads.sumOf { it.entries.size }
    }

    /** Validate every selected file before the first database write. The import is a single transaction. */
    suspend fun restore(account: BackupAccount, files: List<DriveBackup>, code: String? = null): RestoreReport =
        operations.withLock {
            requireConnected(account)
            require(files.isNotEmpty()) { "Select a year with a backup." }
            val recovery = withContext(Dispatchers.IO) {
                if (code != null) BackupCrypto.recoveryBytes(code) else keys.load(account.id).recovery
            }
            var recoveredSecrets: BackupSecrets? = null
            var downloadedBytes = 0L
            val payloads = authorized(account) { drive ->
                files.map { file ->
                    accounts.requireSession(account.session)
                    val bytes = drive.download(file)
                    downloadedBytes += bytes.size
                    require(downloadedBytes <= BackupCrypto.MAX_BYTES) { "Select fewer backup files to restore at once." }
                    withContext(Dispatchers.Default) {
                        val (plain, secrets) = BackupCrypto.decrypt(bytes, account.id, file.year, recovery)
                        recoveredSecrets = secrets
                        BackupPayload.decode(plain, account.id, file.year).also { payload ->
                            payload.entries.flatMap { it.photos }.forEach { photo ->
                                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeByteArray(photo, 0, photo.size, options)
                                require(options.outWidth > 0 && options.outHeight > 0 &&
                                    options.outWidth.toLong() * options.outHeight <= 40_000_000) {
                                    "Backup contains an unsupported attachment."
                                }
                            }
                        }
                    }
                }
            }
            accounts.requireSession(account.session)
            val report = importPayloads(account, payloads)
            accounts.requireSession(account.session)
            withContext(Dispatchers.IO) { keys.save(account.id, checkNotNull(recoveredSecrets)) }
            accounts.loaded(account.session, payloads.map { it.year }.toSet())
            report
        }

    /** Used only after the user has saved and confirmed the new recovery code. */
    suspend fun createRecovery(account: BackupAccount, secrets: BackupSecrets) = operations.withLock {
        requireConnected(account)
        require(files(account).isEmpty()) { "This account already has backups. Enter its existing recovery code." }
        withContext(Dispatchers.IO) { keys.save(account.id, secrets) }
    }

    private suspend fun snapshot(account: BackupAccount): BackupSnapshot = withContext(Dispatchers.IO) {
        db.withTransaction {
            val records = db.backups().records().associateBy { it.noteId }.toMutableMap()
            val refs = db.photos().observeRefs().first().groupBy { it.noteId }
            val archives = mutableListOf<BackupArchive>()
            val entries = db.notes().observeAll().first().mapNotNull { encrypted ->
                val note = open(encrypted)
                val kind = if (TagMetadataCodec.decode(note.body).hasMetadata) "context" else "journal"
                if (kind == "context" && !account.context || kind == "journal" && !account.journal) return@mapNotNull null
                val identity = records[note.id] ?: BackupRecord(note.id, UUID.randomUUID().toString()).also {
                    db.backups().insertRecord(it)
                    records[note.id] = it
                }
                val photos = if (account.attachments) refs[note.id].orEmpty().map {
                    JournalCipher.open(checkNotNull(db.photos().image(it.id)), "note-photo")
                } else emptyList()
                if (account.attachments || refs[note.id].isNullOrEmpty()) {
                    archives.add(BackupArchive(account.id, note.id, fingerprint(note)))
                }
                BackupEntry(identity.recordId, note.title, note.body, note.createdAt, note.updatedAt, kind, photos)
            }
            BackupSnapshot(entries.distinctBy { it.id to it.digest() }.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).year }.map { (year, rows) ->
                BackupPayload(account.id, year, rows, ZoneId.systemDefault().id) }, archives)
        }
    }

    private suspend fun importPayloads(account: BackupAccount, payloads: List<BackupPayload>): RestoreReport =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                accounts.requireSession(account.session)
                val records = db.backups().records().toMutableList()
                val notes = db.notes().observeAll().first().associate { it.id to open(it) }.toMutableMap()
                val photos = db.photos().observeRefs().first().groupBy { it.noteId }
                val currentDigests = mutableMapOf<Long, String>()
                var imported = 0
                var skipped = 0
                var conflicts = 0
                for (original in payloads.flatMap { it.entries }) {
                    currentCoroutineContext().ensureActive()
                    accounts.requireSession(account.session)
                    if (original.kind == "journal" && !account.journal ||
                        original.kind == "context" && !account.context) continue
                    val entry = if (account.attachments) original else original.copy(photos = emptyList())
                    val digest = entry.digest()
                    val existing = records.filter { it.recordId == entry.id }
                    val identical = existing.firstOrNull { identity ->
                        val localDigest = currentDigests[identity.noteId] ?: run {
                            val note = notes.getValue(identity.noteId)
                            val images = photos[note.id].orEmpty().map {
                                JournalCipher.open(checkNotNull(db.photos().image(it.id)), "note-photo")
                            }
                            BackupEntry(entry.id, note.title, note.body, note.createdAt, note.updatedAt, entry.kind, images)
                                .digest().also { currentDigests[note.id] = it }
                        }
                        localDigest == digest
                    }
                    val noteId = if (identical != null) {
                        skipped++
                        identical.noteId
                    } else {
                        if (existing.isNotEmpty()) conflicts++
                        val note = Note(title = JournalCipher.seal(entry.title, "note-title"),
                            body = JournalCipher.seal(entry.body, "note-body"),
                            createdAt = entry.createdAt, updatedAt = entry.updatedAt)
                        val id = db.notes().insert(note)
                        db.photos().insert(entry.photos.map {
                            NotePhoto(noteId = id, image = JournalCipher.seal(it, "note-photo"))
                        })
                        val record = BackupRecord(id, entry.id)
                        db.backups().insertRecord(record)
                        records.add(record)
                        notes[id] = note.copy(id = id, title = entry.title, body = entry.body)
                        currentDigests[id] = digest
                        imported++
                        id
                    }
                    db.backups().insertVersion(BackupVersion(account.id, entry.id, digest, noteId))
                }
                accounts.requireSession(account.session)
                RestoreReport(imported, skipped, conflicts)
            }
        }

    private suspend fun <T> authorized(account: BackupAccount, work: suspend (DriveStorage) -> T): T {
        accounts.requireSession(account.session)
        try {
            val drive = driveProvider(account)
            accounts.requireSession(account.session)
            return work(drive)
        } catch (error: DriveConsentRequired) {
            accounts.needsConsent(account.session)
            throw error
        } catch (error: com.google.android.gms.common.api.ApiException) {
            if (error.statusCode in listOf(4, 5, 16)) {
                accounts.needsConsent(account.session)
                throw DriveConsentRequired()
            }
            throw error
        }
    }
    private fun requireConnected(account: BackupAccount) {
        accounts.requireSession(account.session)
        check(account.connected && !account.reconnect) { "Connect Drive before backing up or restoring." }
    }
    private fun open(note: Note) = note.copy(title = JournalCipher.open(note.title, "note-title"),
        body = JournalCipher.open(note.body, "note-body"))

    companion object {
        private val operations = Mutex()
        private fun sourceId(context: Context): String {
            val prefs = context.getSharedPreferences("drive_backup_device", Context.MODE_PRIVATE)
            return prefs.getString("source", null) ?: UUID.randomUUID().toString().also {
                check(prefs.edit().putString("source", it).commit())
            }
        }
        fun year(note: Note) = Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault()).year
        fun fingerprint(note: Note) = BackupCrypto.digest(
            "${note.id}|${note.createdAt}|${note.updatedAt}|${note.title.length}|${note.title}${note.body}".toByteArray())
    }
}
