package com.notokens.monolog.backup

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupEntry(
    val id: String, val title: String, val body: String, val createdAt: Long, val updatedAt: Long,
    val kind: String, val photos: List<ByteArray>
) {
    val year get() = Instant.ofEpochMilli(createdAt).atZone(ZoneOffset.UTC).year
    fun digest(): String = BackupCrypto.digest(ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            for (field in listOf(id, title, body, kind)) {
                val bytes = field.toByteArray(Charsets.UTF_8)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
            data.writeLong(createdAt)
            data.writeLong(updatedAt)
            data.writeInt(photos.size)
            photos.forEach { data.write(BackupCrypto.digest(it).toByteArray(Charsets.US_ASCII)) }
        }
    }.toByteArray())
    fun toJson() = JSONObject().put("id", id).put("title", title).put("body", body)
        .put("createdAt", createdAt).put("updatedAt", updatedAt).put("kind", kind)
        .put("photos", JSONArray(photos.map { Base64.getEncoder().encodeToString(it) }))
}

internal data class BackupMemory(
    val id: String, val timestamp: Long, val day: String, val revision: Int, val source: String,
    val payload: String, val pinned: Boolean, val noteRef: String?,
    val reminderTitle: String? = null, val reminderDue: Long? = null
) {
    fun toJson() = JSONObject().put("id", id).put("timestamp", timestamp).put("day", day)
        .put("revision", revision).put("source", source).put("payload", payload).put("pinned", pinned)
        .put("noteRef", noteRef ?: JSONObject.NULL).put("reminderTitle", reminderTitle ?: JSONObject.NULL)
        .put("reminderDue", reminderDue ?: JSONObject.NULL)
    fun digest() = BackupCrypto.digest("$id|$timestamp|$day|$revision|$source|$payload|$pinned".toByteArray())
}

internal data class BackupPayload(val owner: String, val year: Int, val entries: List<BackupEntry>, val zone: String = "UTC",
    val memories: List<BackupMemory> = emptyList(), val keyboard: JSONObject? = null) {
    fun encode(): ByteArray = JSONObject().put("version", 2).put("owner", owner).put("year", year)
        .put("zone", zone).put("entries", JSONArray(entries.map { it.toJson() }))
        .put("memories", JSONArray(memories.map { it.toJson() })).put("keyboard", keyboard).toString().toByteArray()

    companion object {
        fun decode(bytes: ByteArray, owner: String, year: Int): BackupPayload {
            require(bytes.size <= BackupCrypto.MAX_BYTES)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val zone = ZoneId.of(json.optString("zone", "UTC"))
            require(json.getInt("version") in 1..2 && json.getString("owner") == owner && json.getInt("year") == year)
            val rows = json.getJSONArray("entries")
            require(rows.length() <= 50_000)
            var photoBytes = 0L
            val entries = (0 until rows.length()).map { i ->
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                UUID.fromString(id)
                val photos = row.getJSONArray("photos")
                require(photos.length() <= 8)
                val entry = BackupEntry(id, row.getString("title"), row.getString("body"),
                    row.getLong("createdAt"), row.getLong("updatedAt"), row.getString("kind"),
                    (0 until photos.length()).map {
                        Base64.getDecoder().decode(photos.getString(it)).also { image ->
                            photoBytes += image.size
                            require(image.isNotEmpty() && image.size <= 16 * 1024 * 1024 &&
                                photoBytes <= BackupCrypto.MAX_BYTES)
                        }
                    })
                require(entry.title.length <= 100_000 && entry.body.length <= 2_000_000 &&
                    entry.kind in listOf("journal", "context") &&
                    Instant.ofEpochMilli(entry.createdAt).atZone(zone).year == year &&
                    entry.updatedAt >= entry.createdAt && entry.createdAt > 0)
                entry
            }
            require(entries.map { it.id to it.digest() }.distinct().size == entries.size)
            val moments = json.optJSONArray("memories") ?: JSONArray()
            require(moments.length() <= 50_000)
            val memories = (0 until moments.length()).map {
                val row = moments.getJSONObject(it)
                val moment = BackupMemory(row.getString("id"), row.getLong("timestamp"), row.getString("day"),
                    row.getInt("revision"), row.getString("source"), row.getString("payload"), row.getBoolean("pinned"),
                    row.optString("noteRef").takeUnless { it == "null" || it.isBlank() },
                    row.optString("reminderTitle").takeUnless { it == "null" || it.isBlank() },
                    if (row.isNull("reminderDue")) null else row.getLong("reminderDue"))
                UUID.fromString(moment.id)
                require(java.time.LocalDate.parse(moment.day).year == year && moment.revision > 0 &&
                    moment.payload.length <= 100_000 && moment.source in listOf("Drafted", "Saved note", "Saved context", "Reminder"))
                com.notokens.monolog.memory.MemoryRepository.decodePayload(moment.payload)
                moment
            }
            val keyboard = json.optJSONObject("keyboard")?.also {
                require(it.toString().length <= 600_000)
                it.getJSONObject("settings")
                com.notokens.monolog.data.TypingProfile.decode(it.getJSONObject("profile"))
            }
            return BackupPayload(owner, year, entries, zone.id, memories, keyboard)
        }
    }
}
