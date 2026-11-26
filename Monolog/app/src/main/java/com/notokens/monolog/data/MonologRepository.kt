package com.notokens.monolog.data

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.room.withTransaction
import com.notokens.monolog.memory.invalidateBriefSources
import com.notokens.monolog.reminders.ReminderScheduler
import com.notokens.monolog.reminders.ReminderCalendar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

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
    suspend fun saveNote(title: String, body: String, existing: Note? = null, photos: NotePhotoChanges? = null,
        book: BookPlacement? = null): Long
    suspend fun deleteNote(note: Note)
    suspend fun deleteNotes(notes: List<Note>)
    suspend fun saveReminder(title: String, dueAt: Long, existing: Reminder? = null): Long
    suspend fun deleteReminder(reminder: Reminder)
    suspend fun rescheduleFutureReminders()
    suspend fun clearAllData()
}

data class NotePhotoChanges(val removed: List<Long> = emptyList(), val added: List<ByteArray> = emptyList())

class RoomMonologRepository(private val context: Context) : MonologRepository {
    private val database = MonologDatabase.get(context)
    private val scheduler = ReminderScheduler(context.applicationContext)
    private val calendar = ReminderCalendar(context.applicationContext)
    private val reminderWrites = Mutex()
    override val notes = database.notes().observeAll().map { rows -> rows.map(::openNote) }.flowOn(Dispatchers.IO)
    override val latestNote = database.notes().observeLatest().map { it?.let(::openNote) }.flowOn(Dispatchers.IO)
    override val notePhotos = database.photos().observeRefs()
    override val reminders = database.reminders().observeAll()
    override fun note(id: Long) = database.notes().observe(id).map { it?.let(::openNote) }.flowOn(Dispatchers.IO)
    override fun latestNoteBetween(start: Long, end: Long) = database.notes().observeLatestBetween(start, end)
        .map { it?.let(::openNote) }.flowOn(Dispatchers.IO)
    override fun notePhotos(id: Long) = database.photos().observeRefs(id)
    override fun reminder(id: Long) = database.reminders().observe(id)
    override fun nextReminder(now: Long) = database.reminders().observeNext(now)
    override suspend fun photoImage(id: Long) = withContext(Dispatchers.IO) {
        database.photos().image(id)?.let { encrypted ->
            runCatching { JournalCipher.open(encrypted, "note-photo") }.getOrNull()
        }
    }

    private fun openNote(note: Note): Note = try {
        note.copy(title = JournalCipher.open(note.title, "note-title"), body = JournalCipher.open(note.body, "note-body"))
    } catch (_: Exception) {
        note.copy(title = "Unable to open note", body = "")
    }

    override suspend fun saveNote(title: String, body: String, existing: Note?, photos: NotePhotoChanges?, book: BookPlacement?): Long {
        require(title.isNotBlank() || body.isNotBlank() || photos?.added?.isNotEmpty() == true || existing != null)
        val cleanTitle = title.trim().ifBlank { body.trim().lineSequence().first().take(60).ifBlank { "Untitled note" } }
        val savedId = database.withTransaction {
            val current = existing?.let { database.notes().current(it.id) ?: error("This note was deleted") }
            val bookId = if (book == null) current?.bookId else book.id?.let { database.books().get(it)?.id }
            val sealedTitle = JournalCipher.seal(cleanTitle, "note-title")
            val sealedBody = JournalCipher.seal(body, "note-body")
            val id = if (existing == null) {
                database.notes().insert(Note(title = sealedTitle, body = sealedBody, bookId = bookId))
            } else {
                val updated = existing.copy(title = sealedTitle, body = sealedBody, updatedAt = System.currentTimeMillis(), bookId = bookId)
                check(database.notes().update(updated) == 1) { "This note was deleted" }
                updated.id
            }
            if (photos != null) {
                if (photos.removed.isNotEmpty()) database.photos().remove(id, photos.removed)
                database.photos().insert(photos.added.map {
                    NotePhoto(noteId = id, image = JournalCipher.seal(it, "note-photo"))
                })
            }
            id
        }
        com.notokens.monolog.memory.MemoryRepository.queueNote(context, savedId)
        return savedId
    }

    override suspend fun deleteNote(note: Note) = deleteNotes(listOf(note))
    override suspend fun deleteNotes(notes: List<Note>) = database.withTransaction {
        database.notes().delete(notes)
        notes.groupBy { journalDay(it.createdAt) }.forEach { (day, deleted) ->
            invalidateBriefSources(database, day, deleted.mapTo(hashSetOf()) { "note:${it.id}" })
        }
    }

    override suspend fun saveReminder(title: String, dueAt: Long, existing: Reminder?): Long = reminderWrites.withLock {
        require(title.isNotBlank())
        require(dueAt > System.currentTimeMillis())
        val reminder = existing?.copy(title = title.trim(), dueAt = dueAt)
        val id = if (reminder == null) database.reminders().insert(Reminder(title = title.trim(), dueAt = dueAt))
        else {
            check(database.reminders().update(reminder) == 1) { "This reminder was deleted" }
            scheduler.cancel(reminder.id)
            reminder.id
        }
        val saved = Reminder(id = id, title = title.trim(), dueAt = dueAt)
        scheduler.schedule(saved)
        com.notokens.monolog.memory.MemoryRepository.queueReminder(context, id)
        if (!calendar.save(saved)) withContext(Dispatchers.Main.immediate) {
            android.widget.Toast.makeText(context, "Reminder saved in Monolog. Its calendar entry couldn’t be updated.",
                android.widget.Toast.LENGTH_LONG).show()
        }
        id
    }

    override suspend fun deleteReminder(reminder: Reminder) = reminderWrites.withLock {
        scheduler.cancel(reminder.id)
        database.withTransaction {
            val memory = database.memories().forReminder(reminder.id)
            database.reminders().delete(reminder)
            if (memory != null) invalidateBriefSources(database, memory.localDay, setOf("memory:${memory.id}"))
        }
        calendar.remove(reminder.id)
    }

    override suspend fun rescheduleFutureReminders() = reminderWrites.withLock {
        database.reminders().future(System.currentTimeMillis()).forEach(scheduler::schedule)
    }

    override suspend fun clearAllData() = reminderWrites.withLock {
        com.notokens.monolog.memory.MemoryPreferences(context).markCleared()
        database.reminders().all().forEach { scheduler.cancel(it.id); calendar.remove(it.id) }
        NotificationManagerCompat.from(context).cancelAll()
        withContext(Dispatchers.IO) { database.clearAllTables() }
    }

    private fun journalDay(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

object MonologData {
    @Volatile private var repository: MonologRepository? = null
    fun repository(context: Context): MonologRepository = repository ?: synchronized(this) {
        repository ?: RoomMonologRepository(context.applicationContext).also { repository = it }
    }
}
