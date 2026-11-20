package com.notokens.monolog.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

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
    @Update suspend fun update(note: Note)
    @Delete suspend fun delete(note: Note)
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

@Database(entities = [Note::class, Reminder::class], version = 1, exportSchema = false)
abstract class MonologDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun reminders(): ReminderDao

    companion object {
        @Volatile private var instance: MonologDatabase? = null
        fun get(context: Context): MonologDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, MonologDatabase::class.java, "monolog.db"
            ).build().also { instance = it }
        }
    }
}
