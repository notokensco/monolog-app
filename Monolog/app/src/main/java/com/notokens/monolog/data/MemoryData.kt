package com.notokens.monolog.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories", indices = [Index(value = ["dedup"], unique = true), Index("year"),
    Index("noteId"), Index("reminderId")],
    foreignKeys = [
        ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Reminder::class, parentColumns = ["id"], childColumns = ["reminderId"], onDelete = ForeignKey.CASCADE)
    ])
data class MemoryRecord(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val localDay: String,
    val year: Int,
    val revision: Int,
    val source: String,
    val payload: String,
    val dedup: String,
    val pinned: Boolean = false,
    val noteId: Long? = null,
    val reminderId: Long? = null,
    val embedding: ByteArray? = null,
    val encoderVersion: String? = null,
    val vectorRevision: Int = 0,
    val cacheOwner: String? = null,
    val cacheYear: Int? = null,
    val cacheExpiresAt: Long? = null
)

@Entity(tableName = "memory_recaps")
data class MemoryRecap(@PrimaryKey val period: String, val fingerprint: String, val payload: String)

@Entity(tableName = "memory_backup_receipts", primaryKeys = ["owner", "memoryId"],
    foreignKeys = [ForeignKey(entity = MemoryRecord::class, parentColumns = ["id"],
        childColumns = ["memoryId"], onDelete = ForeignKey.CASCADE)], indices = [Index("memoryId")])
data class MemoryBackupReceipt(val owner: String, val memoryId: String, val revision: Int, val digest: String)

@Entity(tableName = "year_downloads", primaryKeys = ["owner", "year"])
data class YearDownload(val owner: String, val year: Int, val expiresAt: Long)

@Entity(tableName = "year_download_notes", primaryKeys = ["owner", "year", "noteId"],
    foreignKeys = [ForeignKey(entity = Note::class, parentColumns = ["id"], childColumns = ["noteId"],
        onDelete = ForeignKey.CASCADE)], indices = [Index("noteId")])
data class YearDownloadNote(val owner: String, val year: Int, val noteId: Long, val fingerprint: String)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY timestamp DESC") fun observeAll(): Flow<List<MemoryRecord>>
    @Query("SELECT * FROM memories WHERE year = :year ORDER BY timestamp DESC") fun observeYear(year: Int): Flow<List<MemoryRecord>>
    @Query("SELECT * FROM memories WHERE year = :year ORDER BY timestamp DESC") suspend fun year(year: Int): List<MemoryRecord>
    @Query("SELECT * FROM memories ORDER BY timestamp DESC") suspend fun all(): List<MemoryRecord>
    @Query("SELECT * FROM memories WHERE id = :id") suspend fun get(id: String): MemoryRecord?
    @Query("SELECT * FROM memories WHERE dedup = :dedup LIMIT 1") suspend fun duplicate(dedup: String): MemoryRecord?
    @Query("SELECT * FROM memories WHERE noteId = :id LIMIT 1") suspend fun forNote(id: Long): MemoryRecord?
    @Query("SELECT * FROM memories WHERE reminderId = :id LIMIT 1") suspend fun forReminder(id: Long): MemoryRecord?
    @Query("SELECT * FROM memories WHERE embedding IS NULL OR vectorRevision != revision OR encoderVersion != :version LIMIT :limit")
    suspend fun unindexed(version: String, limit: Int): List<MemoryRecord>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(record: MemoryRecord)
    @Update suspend fun update(record: MemoryRecord)
    @Query("UPDATE memories SET embedding = :vector, encoderVersion = :version, vectorRevision = :revision WHERE id = :id AND revision = :revision")
    suspend fun vector(id: String, revision: Int, version: String, vector: ByteArray)
    @Query("DELETE FROM memories WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE memories SET noteId = NULL WHERE noteId = :id") suspend fun detachNote(id: Long)
    @Query("SELECT * FROM memory_recaps WHERE period = :period") suspend fun recap(period: String): MemoryRecap?
    @Query("SELECT * FROM memory_recaps WHERE period LIKE ('brief-v2:%:' || :period)")
    suspend fun legacyRecaps(period: String): List<MemoryRecap>
    @Query("SELECT * FROM memory_recaps WHERE period LIKE 'brief-v3:Day:%' OR period LIKE 'brief-v2:%:Day:%' ORDER BY rowid DESC LIMIT 8")
    suspend fun recentDayRecaps(): List<MemoryRecap>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRecap(recap: MemoryRecap)
    @Query("SELECT * FROM memory_recaps WHERE period LIKE ('%:Day:' || :day) OR period LIKE ('%:Month:' || substr(:day, 1, 7)) OR period LIKE ('%:Year:' || substr(:day, 1, 4))")
    suspend fun recapsForDay(day: String): List<MemoryRecap>
    @Query("DELETE FROM memory_recaps WHERE period IN (:periods)") suspend fun deleteRecaps(periods: List<String>)
    @Query("SELECT COUNT(*) FROM memories WHERE id IN (:ids)") suspend fun countExisting(ids: List<String>): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun receipt(receipts: List<MemoryBackupReceipt>)
    @Query("SELECT * FROM memory_backup_receipts WHERE owner = :owner") fun receipts(owner: String): Flow<List<MemoryBackupReceipt>>
    @Query("SELECT * FROM year_downloads WHERE owner = :owner") fun downloads(owner: String): Flow<List<YearDownload>>
    @Query("SELECT * FROM year_downloads WHERE expiresAt <= :now") suspend fun expiredDownloads(now: Long): List<YearDownload>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun download(download: YearDownload)
    @Query("DELETE FROM year_downloads WHERE owner = :owner AND year = :year") suspend fun removeDownload(owner: String, year: Int)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun downloadedNote(note: YearDownloadNote)
    @Query("SELECT * FROM year_download_notes WHERE owner = :owner AND year = :year") suspend fun downloadedNotes(owner: String, year: Int): List<YearDownloadNote>
    @Query("DELETE FROM year_download_notes WHERE owner = :owner AND year = :year") suspend fun clearDownloadedNotes(owner: String, year: Int)
    @Query("SELECT * FROM memories WHERE cacheOwner = :owner AND cacheYear = :year") suspend fun cachedMemories(owner: String, year: Int): List<MemoryRecord>
}
