package com.notokens.monolog.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.notokens.monolog.ime.SensitiveDraft
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Entity(tableName = "remember_entries")
internal data class RememberEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val body: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
internal interface RememberDao {
    @Query("SELECT * FROM remember_entries ORDER BY updatedAt DESC, id DESC") fun observe(): Flow<List<RememberEntry>>
    @Query("SELECT * FROM remember_entries ORDER BY updatedAt DESC, id DESC") suspend fun all(): List<RememberEntry>
    @Insert suspend fun insert(entry: RememberEntry)
    @Query("UPDATE remember_entries SET body = :body, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: Long, body: String, updatedAt: Long): Int
    @Query("DELETE FROM remember_entries WHERE id = :id") suspend fun delete(id: Long)
}

internal class RememberNotebook(context: Context) {
    private val app = context.applicationContext
    private val dao = MonologDatabase.get(app).rememberEntries()
    private val settings = SettingsStore(app)

    val entries = dao.observe().map { rows -> rows.mapNotNull(::open) }.flowOn(Dispatchers.IO)

    suspend fun save(body: String, id: Long? = null) = withContext(Dispatchers.IO) {
        val text = body.trim()
        if (text.isEmpty() || text.length > RememberLanguage.MAX_LENGTH) return@withContext false
        val sensitive = SensitiveDraft().apply { setWords(settings.sensitiveWords.first()) }
        if (settings.incognito.first() || sensitive.matches(text)) return@withContext false
        val sealed = JournalCipher.seal(text, "remember-entry")
        if (id == null) dao.insert(RememberEntry(body = sealed))
        else check(dao.update(id, sealed, System.currentTimeMillis()) == 1) { "This entry was deleted." }
        true
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun recall(question: String): List<String> = withContext(Dispatchers.IO) {
        if (!RememberLanguage.isQuestion(question)) return@withContext emptyList()
        val terms = RememberLanguage.terms(question)
        if (terms.isEmpty()) return@withContext emptyList()
        dao.all().mapNotNull { stored ->
            val entry = open(stored) ?: return@mapNotNull null
            val score = RememberLanguage.relevance(terms, entry.body)
            if (score > 0) entry.body to score else null
        }.sortedByDescending { it.second }.take(3).map { it.first }
    }

    private fun open(entry: RememberEntry) = runCatching {
        entry.copy(body = JournalCipher.open(entry.body, "remember-entry"))
    }.getOrNull()
}

internal object RememberLanguage {
    const val MAX_LENGTH = 600
    private val command = Regex("""^(?:please\s+)?remember\b(?!\s+to\b)(?:[\s,:-]+that\b)?[\s,:-]+(.+)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val commandLead = Regex("""^(?:please\s+)?remember\b(?!\s+to\b)""", RegexOption.IGNORE_CASE)
    private val bareCommand = Regex("""^(?:please\s+)?remember(?:\s+that)?[.,:!]*$""", RegexOption.IGNORE_CASE)
    private val query = Regex("""^(?:(?:can|could) you (?:please )?tell me\s+|do you remember\s+)?(?:where|what|when|which|who|how)\b""", RegexOption.IGNORE_CASE)
    private val personal = Regex("""\b(?:i|we|my|our|mine|ours)\b|\bdo you remember\b""", RegexOption.IGNORE_CASE)
    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val common = setOf("where", "what", "when", "which", "who", "how", "did", "does", "was", "were", "the", "a", "an", "have",
        "has", "i", "we", "it", "my", "our", "mine", "ours", "in", "on", "at", "to", "of", "for", "do", "me", "is", "are", "can", "could",
        "you", "please", "tell", "remember", "kept", "keep", "put", "left", "leave", "stored", "store", "about", "last", "some", "that")

    fun body(text: String): String? = command.matchEntire(text.trim())?.groupValues?.get(1)?.trim()
        ?.takeIf { it.length <= MAX_LENGTH && !bareCommand.matches(text.trim()) && it.any(Char::isLetterOrDigit) && !it.endsWith('?') }

    fun startsCommand(text: CharSequence): Boolean = commandLead.containsMatchIn(text.trimStart())

    fun spokenBody(text: String): String? = if (bareCommand.matches(text.trim())) "" else body(text)

    fun isQuestion(text: String): Boolean = query.containsMatchIn(text.trimStart()) && personal.containsMatchIn(text)

    fun terms(text: String): Set<String> = word.findAll(text.lowercase(Locale.ROOT)).map { it.value }
        .filter { it.length >= 2 && it !in common }.map { if (it.length > 4 && it.endsWith('s') && !it.endsWith("ss")) it.dropLast(1) else it }
        .toSet()

    fun relevance(queryTerms: Set<String>, evidence: String): Int {
        val matching = terms(evidence).intersect(queryTerms)
        if (matching.size * 2 < queryTerms.size) return 0
        return matching.sumOf { it.length.coerceAtMost(10) }
    }
}
