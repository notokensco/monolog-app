package com.notokens.monolog.data

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.notokens.monolog.reminders.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MonologRepository {
    val notes: Flow<List<Note>>
    val reminders: Flow<List<Reminder>>
    fun note(id: Long): Flow<Note?>
    suspend fun saveNote(title: String, body: String, existing: Note? = null): Long
    suspend fun deleteNote(note: Note)
    suspend fun saveReminder(title: String, dueAt: Long, existing: Reminder? = null): Long
    suspend fun deleteReminder(reminder: Reminder)
    suspend fun rescheduleFutureReminders()
    suspend fun clearAllData()
}

class RoomMonologRepository(private val context: Context) : MonologRepository {
    private val database = MonologDatabase.get(context)
    private val scheduler = ReminderScheduler(context.applicationContext)
    override val notes = database.notes().observeAll()
    override val reminders = database.reminders().observeAll()
    override fun note(id: Long) = database.notes().observe(id)

    override suspend fun saveNote(title: String, body: String, existing: Note?): Long {
        require(title.isNotBlank() || body.isNotBlank())
        val cleanTitle = title.trim().ifBlank { body.trim().lineSequence().first().take(60) }
        val updated = existing?.copy(title = cleanTitle, body = body.trim(), updatedAt = System.currentTimeMillis())
        return if (updated == null) database.notes().insert(Note(title = cleanTitle, body = body.trim()))
        else { database.notes().update(updated); updated.id }
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
