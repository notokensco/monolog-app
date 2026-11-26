package com.notokens.monolog.data

import androidx.room.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Entity(tableName = "journal_hobbies")
internal data class JournalHobby(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "hobby_days", primaryKeys = ["hobbyId", "day"], foreignKeys = [
    ForeignKey(entity = JournalHobby::class, parentColumns = ["id"], childColumns = ["hobbyId"], onDelete = ForeignKey.CASCADE)
])
internal data class HobbyDay(val hobbyId: Long, val day: String)

internal data class TrackedHobby(
    @Embedded val hobby: JournalHobby,
    @Relation(parentColumn = "id", entityColumn = "hobbyId", entity = HobbyDay::class, projection = ["day"])
    val days: List<String>
)

@Dao
internal interface JournalHobbyDao {
    @Transaction @Query("SELECT * FROM journal_hobbies ORDER BY id") fun observe(): Flow<List<TrackedHobby>>
    @Query("SELECT * FROM journal_hobbies") suspend fun all(): List<JournalHobby>
    @Insert suspend fun insert(hobby: JournalHobby)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun complete(day: HobbyDay)
    @Query("DELETE FROM hobby_days WHERE hobbyId = :id AND day = :day") suspend fun uncomplete(id: Long, day: String)
}

internal class JournalHobbies(private val db: MonologDatabase) {
    val entries = db.hobbies().observe().map { entries ->
        entries.mapNotNull { entry ->
            runCatching { entry.copy(hobby = entry.hobby.copy(name = openName(entry.hobby))) }.getOrNull()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun add(name: String): Boolean = withContext(Dispatchers.IO) {
        val title = name.trim().take(40)
        if (title.isEmpty()) return@withContext false
        db.withTransaction {
            if (db.hobbies().all().any { runCatching { openName(it).equals(title, ignoreCase = true) }.getOrDefault(false) }) false
            else {
                db.hobbies().insert(JournalHobby(name = JournalCipher.seal(title, "hobby-name")))
                true
            }
        }
    }

    suspend fun setCompleted(id: Long, day: LocalDate, done: Boolean) {
        if (done) db.hobbies().complete(HobbyDay(id, day.toString())) else db.hobbies().uncomplete(id, day.toString())
    }

    private fun openName(hobby: JournalHobby) = JournalCipher.open(hobby.name, "hobby-name")
}

internal fun hobbyStreak(days: Set<String>, today: LocalDate): Int {
    var date = if (today.toString() in days) today else today.minusDays(1)
    var count = 0
    while (date.toString() in days) {
        count++
        date = date.minusDays(1)
    }
    return count
}
