package com.notokens.monolog.connect

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.notokens.monolog.backup.BackupCrypto
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.data.JournalCipher
import com.notokens.monolog.data.MemoryRecap
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.data.Note
import com.notokens.monolog.data.NotePhotoChanges
import com.notokens.monolog.data.PhotoCodec
import com.notokens.monolog.data.Reminder
import com.notokens.monolog.data.WebConnectReceipt
import com.notokens.monolog.ime.vox.VoiceNoteBody
import com.notokens.monolog.memory.MemoryRepository
import com.notokens.monolog.memory.readCachedBrief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

internal class ConnectRecords(private val context: Context) {
    private val db = MonologDatabase.get(context)
    private val repository = MonologData.repository(context)
    private val memory = MemoryRepository(context)
    val changes = db.invalidationTracker.createFlow("notes", "note_photos", "memories", "reminders", "memory_recaps")

    suspend fun snapshot(since: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val current = db.withTransaction {
            Snapshot(
                db.notes().observeAll().first(), db.memories().all(), db.reminders().all(),
                db.photos().observeRefs().first().groupBy { it.noteId }.mapValues { (_, rows) -> rows.map { it.id } },
                db.memories().recentDayRecaps()
            )
        }
        val zone = ZoneId.systemDefault().id
        val fingerprint = buildString {
            appendLine(zone)
            for (note in current.notes) append(note.id).append(':').append(note.version()).append(':').appendLine(current.photos[note.id])
            for (moment in current.memories) append(moment.id).append(':').appendLine(moment.revision)
            for (reminder in current.reminders) append(reminder.id).append(':').appendLine(reminder.version())
            for (recap in current.recaps) append(recap.period).append(':').appendLine(recap.payload)
        }.let { BackupCrypto.digest(it.toByteArray()) }
        if (fingerprint == since) return@withContext JSONObject().put("unchanged", true)

        var estimatedBytes = 0L
        fun budget(bytes: Long) {
            estimatedBytes += bytes
            require(estimatedBytes <= MAX_SNAPSHOT_BYTES) { "This journal is too large to share in one session." }
        }
        val notes = JSONArray()
        for (note in current.notes) {
            budget((note.title.length + note.body.length) * 2L + 256)
            val title = JournalCipher.open(note.title, "note-title")
            val body = JournalCipher.open(note.body, "note-body")
            val capture = TagMetadataCodec.decode(body)
            val photos = JSONArray()
            for (photoId in current.photos[note.id].orEmpty()) {
                photos.put(JSONObject().put("id", photoId.toString()).put("mime", "image/jpeg"))
            }
            val visible = if (capture.hasMetadata) capture.bottomComment else capture.cleanBody
            val row = JSONObject().put("id", note.id.toString()).put("version", note.version())
                .put("kind", if (capture.hasMetadata) "context" else "journal")
                .put("title", title).put("body", visible).put("createdAt", note.createdAt).put("updatedAt", note.updatedAt)
                .put("photos", photos).put("audio", capture.bottomAudio ?: VoiceNoteBody.audio(body))
            if (capture.hasMetadata) row.put("tags", JSONArray(capture.tags.map {
                JSONObject().put("xRatio", it.xRatio.toDouble()).put("yRatio", it.yRatio.toDouble()).put("text", it.text)
                    .put("audio", it.audio).put("isVoice", it.isVoice)
            })).put("analysis", capture.analysis).put("imageKeywords", JSONArray(capture.imageKeywords))
            notes.put(row)
        }
        val moments = JSONArray()
        for (moment in current.memories) {
            budget(moment.payload.length * 2L + 256)
            val evidence = MemoryRepository.decode(moment)
            moments.put(JSONObject().put("id", moment.id).put("version", moment.revision.toString())
                .put("body", evidence.summary).put("summary", evidence.summary).put("tags", JSONArray(evidence.tags))
                .put("revision", moment.revision).put("timestamp", moment.timestamp)
                .put("day", moment.localDay).put("source", moment.source).put("pinned", moment.pinned)
                .put("app", evidence.app).put("anger", evidence.anger).put("angerOrigin", evidence.angerOrigin)
                .put("mood", evidence.mood)
                .put("noteId", moment.noteId?.toString()).put("reminderId", moment.reminderId?.toString()))
        }
        val reminders = JSONArray(current.reminders.map {
            JSONObject().put("id", it.id.toString()).put("version", it.version())
                .put("title", it.title).put("dueAt", it.dueAt).put("createdAt", it.createdAt)
        })
        val dayBrief = current.recaps.mapNotNull { entry ->
            readCachedBrief(entry)?.brief?.takeIf { it.generated && it.updatedAt != null && it.text.isNotBlank() }
                ?.let { entry.period.substringAfterLast(':') to it }
        }.maxByOrNull { it.second.updatedAt ?: 0 }?.let { (day, brief) ->
            JSONObject().put("day", day).put("text", brief.text).put("updatedAt", brief.updatedAt)
        }
        JSONObject().put("snapshot", JSONObject().put("revision", fingerprint).put("zone", zone)
            .put("notes", notes).put("memories", moments).put("reminders", reminders)
            .put("notePhotoEditing", true).put("dayBrief", dayBrief ?: JSONObject.NULL))
    }

    suspend fun photo(id: String): JSONObject {
        val image = photoBytes(id) ?: return error("This photo was removed.")
        return JSONObject().put("mime", "image/jpeg").put("data", Base64.getEncoder().encodeToString(image))
    }

    suspend fun photoBytes(id: String): ByteArray? = id.toLongOrNull()?.let { repository.photoImage(it) }

    suspend fun mutate(session: String, expiresAt: Long, command: JSONObject): JSONObject {
        val operation = command.optString("operationId")
        if (!validOperation(operation) || command.optString("op") !in setOf("save", "delete")) return error("Invalid operation.")
        return db.withTransaction {
            val receipts = db.webConnect()
            receipts.receipt(session, operation)?.let { return@withTransaction JSONObject(it) }
            val type = command.optString("type")
            val after = command.optString("after")
            val previous = after.takeIf(::validOperation)?.let { receipts.receipt(session, it) }?.let(::JSONObject)
            val usableChain = previous?.optBoolean("ok") == true && previous.optString("type") == type &&
                previous.optString("version").isNotBlank() && (command.optString("id").isEmpty() ||
                    command.optString("id") == previous.optString("id"))
            val resolved = if (after.isNotEmpty() && usableChain) JSONObject(command.toString())
                .put("id", previous.optString("id")).put("base", previous.optString("version")) else command
            val result = if (after.isNotEmpty() && !usableChain) conflict() else when (type) {
                "note" -> noteChange(resolved)
                "reminder" -> reminderChange(resolved)
                "memory" -> memoryChange(resolved)
                else -> error("Unknown record type.")
            }
            result.put("operationId", operation).put("type", type)
            receipts.insert(WebConnectReceipt(session, operation, result.toString(), expiresAt))
            result
        }
    }

    suspend fun clearReceipts(session: String) = db.webConnect().clear(System.currentTimeMillis(), session)

    private suspend fun noteChange(command: JSONObject): JSONObject {
        val id = command.optString("id")
        val current = id.toLongOrNull()?.let { db.notes().current(it) }
        if (id.isNotEmpty() && (current == null || command.optString("base") != current.version())) return conflict()
        if (command.optString("op") == "delete") {
            if (current == null) return conflict()
            repository.deleteNote(current)
            return saved(id)
        }
        val title = command.optString("title")
        val body = command.optString("body")
        if (title.length > 500 || body.length > 262_144) return error("Enter a title or note within the size limit.")
        val photoChanges = if (command.has("photos")) {
            val photos = command.optJSONArray("photos") ?: return error("Invalid note photos.")
            val existingIds = current?.let { db.photos().ids(it.id) }.orEmpty()
            parseNotePhotos(photos, existingIds) ?: return error("Keep up to eight valid images in this note.")
        } else null
        if (current == null && title.isBlank() && body.isBlank() && photoChanges?.added.isNullOrEmpty()) {
            return error("Enter a title, write a note, or add an image.")
        }
        val existing = current?.copy(title = JournalCipher.open(current.title, "note-title"),
            body = JournalCipher.open(current.body, "note-body"))
        val stored = when {
            existing != null && TagMetadataCodec.decode(existing.body).hasMetadata ->
                TagMetadataCodec.replaceCaptureComment(existing.body, body)
            existing != null -> TagMetadataCodec.replaceVisibleBody(existing.body, body)
            command.optString("kind") == "context" -> TagMetadataCodec.encodeCapture(emptyList(), false, body, "")
            else -> body
        }
        val noteId = repository.saveNote(title, stored, existing, photoChanges)
        return saved(noteId.toString(), db.notes().current(noteId)?.version()).apply {
            if (photoChanges != null) put("photoIds", JSONArray(db.photos().ids(noteId).map(Long::toString)))
        }
    }

    private fun parseNotePhotos(photos: JSONArray, existing: List<Long>): NotePhotoChanges? {
        if (photos.length() > PhotoCodec.MAX_ATTACHMENTS) return null
        val kept = mutableSetOf<Long>()
        val added = mutableListOf<ByteArray>()
        for (index in 0 until photos.length()) {
            val entry = photos.optJSONObject(index) ?: return null
            if (entry.has("id")) {
                val id = entry.optString("id").toLongOrNull() ?: return null
                if (entry.has("data") || id !in existing || !kept.add(id)) return null
            } else {
                val encoded = entry.optString("data")
                if (encoded.length !in 4..(PhotoCodec.MAX_PHOTO_BYTES / 3 * 4)) return null
                val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return null }
                val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
                if (info.outMimeType != "image/jpeg" || info.outWidth !in 1..1600 || info.outHeight !in 1..1600) return null
                added += bytes
            }
        }
        return NotePhotoChanges(existing.filterNot { it in kept }, added)
    }

    private suspend fun reminderChange(command: JSONObject): JSONObject {
        val id = command.optString("id")
        val current = id.toLongOrNull()?.let { db.reminders().current(it) }
        if (id.isNotEmpty() && (current == null || command.optString("base") != current.version())) return conflict()
        if (command.optString("op") == "delete") {
            if (current == null) return conflict()
            repository.deleteReminder(current)
            return saved(id)
        }
        val title = command.optString("title")
        val due = command.optLong("dueAt")
        if (title.isBlank() || title.length > 500 || due <= System.currentTimeMillis()) return error("Enter a title and a future time.")
        val reminderId = repository.saveReminder(title, due, current)
        return saved(reminderId.toString(), db.reminders().current(reminderId)?.version())
    }

    private suspend fun memoryChange(command: JSONObject): JSONObject {
        val id = command.optString("id")
        val moment = db.memories().get(id) ?: return conflict()
        if (moment.revision.toString() != command.optString("base")) return conflict()
        if (command.optString("op") == "delete") {
            memory.delete(id)
            return saved(id)
        }
        val body = command.optString("body")
        if (body.isBlank() || body.length > 600) return error("Enter a memory summary up to 600 characters.")
        memory.edit(moment, body, MemoryRepository.decode(moment).anger, command.optBoolean("pinned", moment.pinned))
        return saved(id, (moment.revision + 1).toString())
    }

    private data class Snapshot(
        val notes: List<Note>,
        val memories: List<com.notokens.monolog.data.MemoryRecord>,
        val reminders: List<Reminder>,
        val photos: Map<Long, List<Long>>,
        val recaps: List<MemoryRecap>
    )

    private fun Note.version() = BackupCrypto.digest("$updatedAt|$title|$body".toByteArray())
    private fun Reminder.version() = BackupCrypto.digest("$dueAt|$title".toByteArray())
    private fun saved(id: String, version: String? = null) = JSONObject().put("ok", true).put("id", id).put("version", version)
    private fun conflict() = error("This item changed on the phone. Refresh and try again.")

    companion object {
        const val MAX_SNAPSHOT_BYTES = 36 * 1024 * 1024
        fun error(message: String) = JSONObject().put("ok", false).put("error", message)
        fun validOperation(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
