package com.notokens.monolog.data

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.notokens.monolog.reminders.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MonologRepository {
    val notes: Flow<List<Note>>
    val latestNote: Flow<Note?>
    val notePhotos: Flow<List<NotePhotoRef>>
    val reminders: Flow<List<Reminder>>
    fun note(id: Long): Flow<Note?>
    fun latestNoteBetween(start: Long, end: Long): Flow<Note?>
    fun notePhotos(id: Long): Flow<List<NotePhotoRef>>
    fun reminder(id: Long): Flow<Reminder?>
    fun nextReminder(now: Long): Flow<Reminder?>
    suspend fun photoImage(id: Long): ByteArray?
    suspend fun saveNote(title: String, body: String, existing: Note? = null, photos: NotePhotoChanges? = null): Long
    suspend fun deleteNote(note: Note)
    suspend fun saveReminder(title: String, dueAt: Long, existing: Reminder? = null): Long
    suspend fun deleteReminder(reminder: Reminder)
    suspend fun rescheduleFutureReminders()
    suspend fun clearAllData()
}

data class NotePhotoChanges(val removed: List<Long> = emptyList(), val added: List<ByteArray> = emptyList())

class RoomMonologRepository(private val context: Context) : MonologRepository {
    private val database = MonologDatabase.get(context)
    private val scheduler = ReminderScheduler(context.applicationContext)
    override val notes = database.notes().observeAll()
    override val latestNote = database.notes().observeLatest()
    override val notePhotos = database.photos().observeRefs()
    override val reminders = database.reminders().observeAll()
    override fun note(id: Long) = database.notes().observe(id)
    override fun latestNoteBetween(start: Long, end: Long) = database.notes().observeLatestBetween(start, end)
    override fun notePhotos(id: Long) = database.photos().observeRefs(id)
    override fun reminder(id: Long) = database.reminders().observe(id)
    override fun nextReminder(now: Long) = database.reminders().observeNext(now)
    override suspend fun photoImage(id: Long) = database.photos().image(id)

    override suspend fun saveNote(title: String, body: String, existing: Note?, photos: NotePhotoChanges?): Long {
        require(title.isNotBlank() || body.isNotBlank() || photos?.added?.isNotEmpty() == true || existing != null)
        val cleanTitle = title.trim().ifBlank { body.trim().lineSequence().first().take(60).ifBlank { "Untitled note" } }
        return database.withTransaction {
            val id = if (existing == null) {
                database.notes().insert(Note(title = cleanTitle, body = body))
            } else {
                val updated = existing.copy(title = cleanTitle, body = body, updatedAt = System.currentTimeMillis())
                check(database.notes().update(updated) == 1) { "This note was deleted" }
                updated.id
            }
            if (photos != null) {
                if (photos.removed.isNotEmpty()) database.photos().remove(id, photos.removed)
                database.photos().insert(photos.added.map { NotePhoto(noteId = id, image = it) })
            }
            id
        }
    }

    override suspend fun deleteNote(note: Note) = database.notes().delete(note)

    override suspend fun saveReminder(title: String, dueAt: Long, existing: Reminder?): Long {
        require(title.isNotBlank())
        require(dueAt > System.currentTimeMillis())
        val reminder = existing?.copy(title = title.trim(), dueAt = dueAt)
        val id = if (reminder == null) database.reminders().insert(Reminder(title = title.trim(), dueAt = dueAt))
        else { scheduler.cancel(reminder.id); database.reminders().update(reminder); reminder.id }
        scheduler.schedule(Reminder(id = id, title = title.trim(), dueAt = dueAt))
        return id
    }

    override suspend fun deleteReminder(reminder: Reminder) {
        scheduler.cancel(reminder.id)
        database.reminders().delete(reminder)
    }

    override suspend fun rescheduleFutureReminders() {
        database.reminders().future(System.currentTimeMillis()).forEach(scheduler::schedule)
    }

    override suspend fun clearAllData() {
        database.reminders().all().forEach { scheduler.cancel(it.id) }
        NotificationManagerCompat.from(context).cancelAll()
        withContext(Dispatchers.IO) { database.clearAllTables() }
    }
}

object MonologData {
    @Volatile private var repository: MonologRepository? = null
    fun repository(context: Context): MonologRepository = repository ?: synchronized(this) {
        repository ?: RoomMonologRepository(context.applicationContext).also { repository = it }
    }
}
