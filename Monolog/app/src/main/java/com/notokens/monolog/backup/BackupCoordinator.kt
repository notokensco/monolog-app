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
import com.notokens.monolog.data.MemoryBackupReceipt
import com.notokens.monolog.data.MemoryRecord
import com.notokens.monolog.data.Reminder
import com.notokens.monolog.data.YearDownload
import com.notokens.monolog.data.YearDownloadNote
import com.notokens.monolog.memory.MemoryRepository
import com.notokens.monolog.memory.MemoryIndexWork
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

internal data class BackupSnapshot(val payloads: List<BackupPayload>, val archives: List<BackupArchive>,
    val memoryReceipts: List<MemoryBackupReceipt> = emptyList())
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
    private val settings = com.notokens.monolog.data.SettingsStore(context.applicationContext)

    suspend fun files(account: BackupAccount): List<DriveBackup> {
        requireConnected(account)
        return authorized(account) { it.list() }
    }

    suspend fun linkExistingKey(account: BackupAccount) = operations.withLock {
        requireConnected(account)
        val local = withContext(Dispatchers.IO) { if (keys.hasKey(account.id)) keys.load(account.id) else null }
        if (local != null) authorized(account) { it.linkRecovery(local.recovery) }
    }

    suspend fun backUp(account: BackupAccount): Int = operations.withLock {
        requireConnected(account)
        require(account.hasSelection) { "Select an item to back up." }
        val snapshot = snapshot(account)
        require(snapshot.payloads.isNotEmpty()) { "There are no selected entries to back up." }
        authorized(account) { drive ->
            val secrets = withContext(Dispatchers.IO) {
                if (keys.hasKey(account.id)) keys.load(account.id)
                else BackupCrypto.create().also { keys.save(account.id, it) }
            }
            accounts.requireSession(account.session)
            drive.linkRecovery(secrets.recovery)
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
            db.memories().receipt(snapshot.memoryReceipts)
        }
        accounts.success(account.session)
        snapshot.payloads.sumOf { it.entries.size + it.memories.size + if (it.keyboard == null) 0 else 1 }
    }

    /** Validate every selected file before the first database write. The import is a single transaction. */
    suspend fun restore(account: BackupAccount, files: List<DriveBackup>, code: String? = null): RestoreReport =
        operations.withLock {
            requireConnected(account)
            require(files.isNotEmpty()) { "Select a year with a backup." }
            val localRecovery = withContext(Dispatchers.IO) {
                if (code != null) BackupCrypto.recoveryBytes(code)
                else if (keys.hasKey(account.id)) keys.load(account.id).recovery else null
            }
            var recoveredSecrets: BackupSecrets? = null
            var downloadedBytes = 0L
            val payloads = authorized(account) { drive ->
                val seeds = if (code == null) (listOfNotNull(localRecovery) + drive.recoverySeeds())
                    .distinctBy { BackupCrypto.digest(it) } else listOfNotNull(localRecovery)
                check(seeds.isNotEmpty()) { "These older backups are not linked yet. Back up from the original device to enable account restore." }
                files.map { file ->
                    accounts.requireSession(account.session)
                    val bytes = drive.download(file)
                    downloadedBytes += bytes.size
                    require(downloadedBytes <= BackupCrypto.MAX_BYTES) { "Select fewer backup files to restore at once." }
                    withContext(Dispatchers.Default) {
                        val (plain, secrets) = seeds.firstNotNullOfOrNull { seed ->
                            try { BackupCrypto.decrypt(bytes, account.id, file.year, seed) }
                            catch (_: javax.crypto.AEADBadTagException) { null }
                        } ?: error("This backup could not be unlocked by the linked account. Back up from the original device to link older backups.")
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
            if (account.keyboard) {
                val snapshots = payloads.mapNotNull { it.keyboard }.sortedBy { it.optLong("savedAt") }
                val latest = snapshots.lastOrNull()
                if (latest != null) {
                    val profile = snapshots.fold(com.notokens.monolog.data.TypingProfile()) { accumulated, snapshot ->
                        accumulated.merge(com.notokens.monolog.data.TypingProfile.decode(snapshot.getJSONObject("profile")))
                    }
                    latest.put("profile", profile.encode())
                    settings.importKeyboard(latest) { accounts.requireSession(account.session) }
                }
            }
            withContext(Dispatchers.IO) {
                if (!keys.hasKey(account.id) || code != null) keys.save(account.id, checkNotNull(recoveredSecrets))
            }
            accounts.loaded(account.session, payloads.map { it.year }.toSet())
            MemoryIndexWork.enqueue(context)
            report
        }

    /** Used only after the user has saved and confirmed the new recovery code. */
    suspend fun createRecovery(account: BackupAccount, secrets: BackupSecrets) = operations.withLock {
        requireConnected(account)
        require(files(account).isEmpty()) { "This account already has backups. Enter its existing recovery code." }
        withContext(Dispatchers.IO) { keys.save(account.id, secrets) }
    }

    private suspend fun snapshot(account: BackupAccount): BackupSnapshot = withContext(Dispatchers.IO) {
        val keyboard = if (account.keyboard) settings.exportKeyboard().put("savedAt", System.currentTimeMillis()) else null
        val currentYear = java.time.LocalDate.now().year
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
                val body = if (account.attachments) note.body else TagMetadataCodec.withoutAudio(note.body)
                if (account.attachments || refs[note.id].isNullOrEmpty() && body == note.body) {
                    archives.add(BackupArchive(account.id, note.id, fingerprint(note)))
                }
                BackupEntry(identity.recordId, note.title, body, note.createdAt, note.updatedAt, kind, photos)
            }
            val reminders = db.reminders().all().associateBy { it.id }
            val memories = db.memories().all().filter {
                if (it.source == "Saved context") account.context else account.journal
            }.map { row ->
                val reminder = reminders[row.reminderId]
                BackupMemory(row.id, row.timestamp, row.localDay, row.revision, row.source,
                    JournalCipher.open(row.payload, "memory-payload"), row.pinned,
                    records[row.noteId]?.recordId, reminder?.title, reminder?.dueAt)
            }
            val noteYears = entries.distinctBy { it.id to it.digest() }.groupBy {
                Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).year }
            val memoryYears = memories.groupBy { java.time.LocalDate.parse(it.day).year }
            val receipts = memories.map { MemoryBackupReceipt(account.id, it.id, it.revision, it.digest()) }
            val years = noteYears.keys + memoryYears.keys + listOfNotNull(currentYear.takeIf { keyboard != null })
            BackupSnapshot(years.sorted().map { year ->
                BackupPayload(account.id, year, noteYears[year].orEmpty(), ZoneId.systemDefault().id,
                    memoryYears[year].orEmpty(), keyboard.takeIf { year == currentYear }) }, archives, receipts)
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
                    val entry = if (account.attachments) original else original.copy(
                        photos = emptyList(), body = TagMetadataCodec.withoutAudio(original.body))
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
                        val oldYear = java.time.LocalDate.now().year
                        val entryYear = Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault()).year
                        if (entryYear < oldYear) db.memories().downloadedNote(
                            YearDownloadNote(account.id, entryYear, id, fingerprint(notes.getValue(id))))
                        imported++
                        id
                    }
                    db.backups().insertVersion(BackupVersion(account.id, entry.id, digest, noteId))
                }
                val reminders = db.reminders().all().toMutableList()
                for (moment in payloads.flatMap { it.memories }) {
                    accounts.requireSession(account.session)
                    currentCoroutineContext().ensureActive()
                    if (moment.source == "Saved context" && !account.context ||
                        moment.source != "Saved context" && !account.journal) continue
                    var id = moment.id
                    var local = db.memories().get(id)
                    fun same(row: MemoryRecord) = row.payload.let { JournalCipher.open(it, "memory-payload") } == moment.payload &&
                        row.timestamp == moment.timestamp && row.source == moment.source && row.pinned == moment.pinned
                    if (local != null && !same(local)) {
                        conflicts++
                        id = UUID.nameUUIDFromBytes("${moment.id}|${moment.digest()}".toByteArray()).toString()
                        local = db.memories().get(id)
                        // A locally edited conflict must also be preserved on repeated restores.
                        var suffix = 0
                        while (local != null && !same(local)) {
                            id = UUID.nameUUIDFromBytes("${moment.id}|${moment.digest()}|${++suffix}".toByteArray()).toString()
                            local = db.memories().get(id)
                        }
                    }
                    if (local != null) { skipped++; continue }
                    val noteId = moment.noteRef?.let { ref -> records.lastOrNull { it.recordId == ref }?.noteId }
                    val reminderId = if (moment.reminderTitle != null && moment.reminderDue != null) {
                        reminders.firstOrNull { it.title == moment.reminderTitle && it.dueAt == moment.reminderDue }?.id
                            ?: Reminder(title = moment.reminderTitle, dueAt = moment.reminderDue,
                                createdAt = moment.timestamp).let { reminder ->
                                db.reminders().insert(reminder).also { reminders.add(reminder.copy(id = it)) }
                            }
                    } else null
                    val year = java.time.LocalDate.parse(moment.day).year
                    val cached = year < java.time.LocalDate.now().year
                    db.memories().insert(MemoryRecord(id, moment.timestamp, moment.day, year, moment.revision,
                        moment.source, JournalCipher.seal(moment.payload, "memory-payload"),
                        MemoryRepository.fingerprint("restored|$id"), moment.pinned, noteId, reminderId,
                        cacheOwner = if (cached) account.id else null, cacheYear = if (cached) year else null,
                        cacheExpiresAt = if (cached) System.currentTimeMillis() + 7L * 86400000 else null))
                    imported++
                }
                for (year in payloads.map { it.year }.distinct().filter { it < java.time.LocalDate.now().year }) {
                    db.memories().download(YearDownload(account.id, year, System.currentTimeMillis() + 7L * 86400000))
                }
                db.memories().clearRecaps()
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
