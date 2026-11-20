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

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

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
    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1") fun observe(id: Long): Flow<Note?>
    @Insert suspend fun insert(note: Note): Long
    @Update suspend fun update(note: Note): Int
    @Delete suspend fun delete(note: Note)
}

@Dao
interface NotePhotoDao {
    @Query("SELECT id, noteId FROM note_photos ORDER BY id")
    fun observeRefs(): Flow<List<NotePhotoRef>>

    @Query("SELECT image FROM note_photos WHERE id = :id")
    suspend fun image(id: Long): ByteArray?

    @Query("DELETE FROM note_photos WHERE noteId = :noteId AND id IN (:removed)")
    suspend fun remove(noteId: Long, removed: List<Long>)

    @Insert suspend fun insert(photos: List<NotePhoto>)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY dueAt ASC") fun observeAll(): Flow<List<Reminder>>
    @Query("SELECT * FROM reminders") suspend fun all(): List<Reminder>
    @Query("SELECT * FROM reminders WHERE dueAt > :now ORDER BY dueAt ASC") suspend fun future(now: Long): List<Reminder>
    @Insert suspend fun insert(reminder: Reminder): Long
    @Update suspend fun update(reminder: Reminder)
    @Delete suspend fun delete(reminder: Reminder)
}

@Database(entities = [Note::class, Reminder::class, NotePhoto::class], version = 2, exportSchema = false)
abstract class MonologDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun photos(): NotePhotoDao
    abstract fun reminders(): ReminderDao

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

        fun get(context: Context): MonologDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, MonologDatabase::class.java, "monolog.db"
            ).addMigrations(photosMigration).build().also { instance = it }
        }
    }
}
