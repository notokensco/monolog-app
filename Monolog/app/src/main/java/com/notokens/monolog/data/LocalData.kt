package com.notokens.monolog.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes", foreignKeys = [ForeignKey(entity = JournalBook::class, parentColumns = ["id"],
    childColumns = ["bookId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("bookId")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val bookId: Long? = null
)

@Entity(tableName = "journal_books")
data class JournalBook(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Dao
interface BookDao {
    @Query("SELECT * FROM journal_books ORDER BY id") fun observe(): Flow<List<JournalBook>>
    @Query("SELECT * FROM journal_books") suspend fun all(): List<JournalBook>
    @Query("SELECT * FROM journal_books WHERE id = :id") suspend fun get(id: Long): JournalBook?
    @Insert suspend fun insert(book: JournalBook): Long
    @Delete suspend fun delete(book: JournalBook)
}

@Entity(
    tableName = "note_photos",
    foreignKeys = [ForeignKey(
        entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("noteId")]
)
data class NotePhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val noteId: Long,
    val image: ByteArray
)

data class NotePhotoRef(val id: Long, val noteId: Long)

@Entity(tableName = "backup_records", foreignKeys = [ForeignKey(
    entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("recordId")])
data class BackupRecord(@PrimaryKey val noteId: Long, val recordId: String)

@Entity(tableName = "backup_versions", primaryKeys = ["owner", "recordId", "digest"],
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE)], indices = [Index("noteId")])
data class BackupVersion(val owner: String, val recordId: String, val digest: String, val noteId: Long)

@Entity(tableName = "backup_archives", primaryKeys = ["owner", "noteId"],
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE)], indices = [Index("noteId")])
data class BackupArchive(val owner: String, val noteId: Long, val fingerprint: String)

@Entity(tableName = "web_connect_receipts", primaryKeys = ["session", "operation"])
internal data class WebConnectReceipt(val session: String, val operation: String, val result: String, val expiresAt: Long)

@Dao
internal interface WebConnectDao {
    @Query("SELECT result FROM web_connect_receipts WHERE session = :session AND operation = :operation")
    suspend fun receipt(session: String, operation: String): String?
    @Insert suspend fun insert(receipt: WebConnectReceipt)
    @Query("DELETE FROM web_connect_receipts WHERE expiresAt <= :now OR session = :session")
    suspend fun clear(now: Long, session: String = "")
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backup_records") suspend fun records(): List<BackupRecord>
    @Insert suspend fun insertRecord(record: BackupRecord)
    @Query("SELECT * FROM backup_versions WHERE owner = :owner") suspend fun versions(owner: String): List<BackupVersion>
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertVersion(version: BackupVersion)
    @Query("SELECT * FROM backup_archives WHERE owner = :owner") fun archives(owner: String): Flow<List<BackupArchive>>
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun archive(archives: List<BackupArchive>)
}

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dueAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun observeAll(): Flow<List<Note>>
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<Note?>
    @Query("SELECT * FROM notes WHERE updatedAt >= :start AND updatedAt < :end ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestBetween(start: Long, end: Long): Flow<Note?>
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1") fun observe(id: Long): Flow<Note?>
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1") suspend fun current(id: Long): Note?
    @Query("SELECT COUNT(*) FROM notes WHERE id IN (:ids)") suspend fun countExisting(ids: List<Long>): Int
    @Query("SELECT * FROM notes WHERE bookId = :bookId") suspend fun inBook(bookId: Long): List<Note>
    @Query("UPDATE notes SET bookId = :bookId WHERE id = :id") suspend fun move(id: Long, bookId: Long?)
    @Insert suspend fun insert(note: Note): Long
    @Update suspend fun update(note: Note): Int
    @Delete suspend fun delete(note: Note)
    @Delete suspend fun delete(notes: List<Note>)
}

@Dao
interface NotePhotoDao {
    @Query("SELECT id, noteId FROM note_photos ORDER BY id")
    fun observeRefs(): Flow<List<NotePhotoRef>>
    @Query("SELECT id, noteId FROM note_photos WHERE noteId = :noteId ORDER BY id")
    fun observeRefs(noteId: Long): Flow<List<NotePhotoRef>>
    @Query("SELECT id FROM note_photos WHERE noteId = :noteId ORDER BY id")
    suspend fun ids(noteId: Long): List<Long>

    @Query("SELECT image FROM note_photos WHERE id = :id")
    suspend fun image(id: Long): ByteArray?

    @Query("DELETE FROM note_photos WHERE noteId = :noteId AND id IN (:removed)")
    suspend fun remove(noteId: Long, removed: List<Long>)

    @Insert suspend fun insert(photos: List<NotePhoto>)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY dueAt ASC") fun observeAll(): Flow<List<Reminder>>
    @Query("SELECT * FROM reminders WHERE id = :id") fun observe(id: Long): Flow<Reminder?>
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun current(id: Long): Reminder?
    @Query("SELECT * FROM reminders WHERE dueAt > :now ORDER BY dueAt ASC LIMIT 1")
    fun observeNext(now: Long): Flow<Reminder?>
    @Query("SELECT * FROM reminders") suspend fun all(): List<Reminder>
    @Query("SELECT * FROM reminders WHERE dueAt > :now ORDER BY dueAt ASC") suspend fun future(now: Long): List<Reminder>
    @Insert suspend fun insert(reminder: Reminder): Long
    @Update suspend fun update(reminder: Reminder): Int
    @Delete suspend fun delete(reminder: Reminder)
}

@Database(entities = [Note::class, Reminder::class, NotePhoto::class, BackupRecord::class,
    BackupVersion::class, BackupArchive::class, MemoryRecord::class, MemoryRecap::class,
    MemoryBackupReceipt::class, YearDownload::class, YearDownloadNote::class, WebConnectReceipt::class, JournalBook::class,
    JournalHobby::class, HobbyDay::class, RememberEntry::class], version = 9, exportSchema = false)
abstract class MonologDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun books(): BookDao
    abstract fun photos(): NotePhotoDao
    abstract fun reminders(): ReminderDao
    abstract fun backups(): BackupDao
    abstract fun memories(): MemoryDao
    internal abstract fun webConnect(): WebConnectDao
    internal abstract fun hobbies(): JournalHobbyDao
    internal abstract fun rememberEntries(): RememberDao

    companion object {
        @Volatile private var instance: MonologDatabase? = null
        private val photosMigration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS note_photos (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        noteId INTEGER NOT NULL,
                        image BLOB NOT NULL,
                        FOREIGN KEY(noteId) REFERENCES notes(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_note_photos_noteId ON note_photos (noteId)")
            }
        }

        private val journalEncryption = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
                var id = 0L
                while (true) {
                    val row = db.query("SELECT id, title, body FROM notes WHERE id > ? ORDER BY id LIMIT 1", arrayOf(id))
                        .use { if (it.moveToFirst()) Triple(it.getLong(0), it.getString(1), it.getString(2)) else null }
                        ?: break
                    id = row.first
                    db.execSQL("UPDATE notes SET title = ?, body = ? WHERE id = ?", arrayOf<Any>(
                        JournalCipher.seal(row.second, "note-title"),
                        JournalCipher.seal(row.third, "note-body"),
                        id
                    ))
                }
                id = 0L
                while (true) {
                    val row = db.query("SELECT id, image FROM note_photos WHERE id > ? ORDER BY id LIMIT 1", arrayOf(id))
                        .use { if (it.moveToFirst()) it.getLong(0) to it.getBlob(1) else null }
                        ?: break
                    id = row.first
                    db.execSQL("UPDATE note_photos SET image = ? WHERE id = ?",
                        arrayOf<Any>(JournalCipher.seal(row.second, "note-photo"), id))
                }
            }
        }

        internal val backupMigration: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS backup_records (noteId INTEGER NOT NULL PRIMARY KEY, recordId TEXT NOT NULL, FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_records_recordId ON backup_records(recordId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS backup_versions (owner TEXT NOT NULL, recordId TEXT NOT NULL, digest TEXT NOT NULL, noteId INTEGER NOT NULL, PRIMARY KEY(owner, recordId, digest), FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_versions_noteId ON backup_versions(noteId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS backup_archives (owner TEXT NOT NULL, noteId INTEGER NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(owner, noteId), FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_archives_noteId ON backup_archives(noteId)")
            }
        }
        internal val memoryMigration: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS memories (id TEXT NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, localDay TEXT NOT NULL, year INTEGER NOT NULL, revision INTEGER NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, dedup TEXT NOT NULL, pinned INTEGER NOT NULL, noteId INTEGER, reminderId INTEGER, embedding BLOB, encoderVersion TEXT, vectorRevision INTEGER NOT NULL, cacheOwner TEXT, cacheYear INTEGER, cacheExpiresAt INTEGER, FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE, FOREIGN KEY(reminderId) REFERENCES reminders(id) ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memories_dedup ON memories(dedup)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_year ON memories(year)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_noteId ON memories(noteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_reminderId ON memories(reminderId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_recaps (period TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_backup_receipts (owner TEXT NOT NULL, memoryId TEXT NOT NULL, revision INTEGER NOT NULL, digest TEXT NOT NULL, PRIMARY KEY(owner, memoryId), FOREIGN KEY(memoryId) REFERENCES memories(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_backup_receipts_memoryId ON memory_backup_receipts(memoryId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS year_downloads (owner TEXT NOT NULL, year INTEGER NOT NULL, expiresAt INTEGER NOT NULL, PRIMARY KEY(owner, year))")
                db.execSQL("CREATE TABLE IF NOT EXISTS year_download_notes (owner TEXT NOT NULL, year INTEGER NOT NULL, noteId INTEGER NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(owner, year, noteId), FOREIGN KEY(noteId) REFERENCES notes(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_year_download_notes_noteId ON year_download_notes(noteId)")
            }
        }

        internal val webConnectMigration: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS web_connect_receipts (session TEXT NOT NULL, operation TEXT NOT NULL, result TEXT NOT NULL, expiresAt INTEGER NOT NULL, PRIMARY KEY(session, operation))")
            }
        }
        internal val booksMigration: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS journal_books (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                db.execSQL("ALTER TABLE notes ADD COLUMN bookId INTEGER REFERENCES journal_books(id) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_notes_bookId ON notes(bookId)")
            }
        }

        internal val hobbiesMigration: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS journal_hobbies (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS hobby_days (hobbyId INTEGER NOT NULL, day TEXT NOT NULL, PRIMARY KEY(hobbyId, day), FOREIGN KEY(hobbyId) REFERENCES journal_hobbies(id) ON DELETE CASCADE)")
            }
        }

        internal val rememberMigration: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS remember_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, body TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
            }
        }

        fun get(context: Context): MonologDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, MonologDatabase::class.java, "monolog.db"
            ).addMigrations(photosMigration, journalEncryption, backupMigration, memoryMigration, webConnectMigration,
                booksMigration, hobbiesMigration, rememberMigration)
                .build().also { instance = it }
        }
    }
}
