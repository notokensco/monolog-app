package com.notokens.monolog.memory

import android.content.Context
import androidx.room.withTransaction
import com.notokens.monolog.ai.AiText
import com.notokens.monolog.ai.GemmaResult
import com.notokens.monolog.ai.LocalAi
import com.notokens.monolog.ai.TextModel
import com.notokens.monolog.data.JournalCipher
import com.notokens.monolog.data.MemoryRecord
import com.notokens.monolog.data.MemoryRecap
import com.notokens.monolog.data.MonologDatabase
import com.notokens.monolog.data.Note
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.notokens.monolog.capture.TagMetadataCodec
import org.json.JSONObject
import org.json.JSONArray

internal data class MemoryMatch(val record: MemoryRecord, val evidence: MemoryEvidence, val score: Double)
internal data class JournalAnswer(val text: String, val matches: List<MemoryMatch>, val paraphrase: Boolean)
private const val MAX_DAY_BRIEF_INPUT_BYTES = 64_000
internal data class DayBrief(val text: String, val generated: Boolean, val updatedAt: Long? = null)
internal data class CachedBrief(val brief: DayBrief, val sourceIds: Set<String>)

internal fun readCachedBrief(entry: MemoryRecap): CachedBrief? = runCatching {
    val payload = JSONObject(JournalCipher.open(entry.payload, "memory-recap"))
    val ids = payload.optJSONArray("sourceIds")
    CachedBrief(DayBrief(payload.getString("text"), payload.getBoolean("generated"), payload.getLong("updatedAt")),
        if (ids == null) emptySet() else (0 until ids.length()).mapTo(hashSetOf()) { ids.getString(it) })
}.getOrNull()
internal data class BriefUpdate(val brief: DayBrief? = null, val refreshing: Boolean = false, val message: String? = null)
internal data class BriefSource(val id: String, val timestamp: Long, val text: String)

internal fun hasEnoughNewBriefData(previousIds: Set<String>, sources: List<BriefSource>): Boolean {
    val added = sources.filterNot { it.id in previousIds }
    return added.size >= 3 || added.sumOf { it.text.length } >= 1_200
}

internal suspend fun invalidateBriefSources(db: MonologDatabase, day: String, removed: Set<String>) {
    val affected = db.memories().recapsForDay(day).filter { recap ->
        val ids = runCatching {
            JSONObject(JournalCipher.open(recap.payload, "memory-recap")).optJSONArray("sourceIds")
        }.getOrNull()
        // Old cached briefs did not record which entries were used.
        ids == null || (0 until ids.length()).any { ids.optString(it) in removed }
    }
    if (affected.isNotEmpty()) db.memories().deleteRecaps(affected.map { it.period })
}

internal class MemorySearch(private val context: Context,
    private val db: MonologDatabase = MonologDatabase.get(context)) {
    suspend fun search(query: String, year: Int? = LocalDate.now().year): List<MemoryMatch> = withContext(Dispatchers.Default) {
        val records = if (year == null) db.memories().all() else db.memories().year(year)
        val encoder = SentenceEncoder.get(context)
        val vector = try { encoder.embed(query) } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { null }
        val queryTerms = terms(query)
        val sourceNotes = db.notes().observeAll().first().associateBy { it.id }
        records.mapNotNull { row ->
            var evidence = MemoryRepository.decode(row)
            val capture = sourceNotes[row.noteId]?.let { note ->
                TagMetadataCodec.decode(JournalCipher.open(note.body, "note-body"))
            }
            val fullText = capture?.let {
                if (capture.hasMetadata) capture.bottomComment + " " + capture.tags.joinToString(" ") { it.text }
                else capture.cleanBody
            }.orEmpty()
            val position = queryTerms.map { fullText.lowercase(Locale.ROOT).indexOf(it) }.filter { it >= 0 }.minOrNull()
            if (position != null && fullText.isNotBlank()) {
                val start = (position - 120).coerceAtLeast(0)
                evidence = evidence.copy(excerpt = fullText.substring(start, minOf(fullText.length, start + 600)))
            }
            val text = evidence.summary + " " + evidence.excerpt.orEmpty() + " " + evidence.tags.joinToString(" ")
            val keywords = capture?.imageKeywords.orEmpty().joinToString(" ")
            val exact = queryTerms.intersect(terms("$text $fullText $keywords")).size.toDouble() / queryTerms.size.coerceAtLeast(1)
            val semantic = if (vector != null && row.embedding != null &&
                row.vectorRevision == row.revision && row.encoderVersion == SentenceEncoder.VERSION) {
                runCatching { SentenceEncoder.cosine(vector, JournalCipher.open(row.embedding, "memory-vector")) }.getOrDefault(0.0)
            } else 0.0
            val score = semantic * .8 + exact * .6
            if (exact > 0 || semantic > .22) MemoryMatch(row, evidence, score) else null
        }.sortedByDescending { it.score }.take(6)
    }

    suspend fun ask(query: String, year: Int = LocalDate.now().year): JournalAnswer {
        val matches = search(query, year)
        if (matches.isEmpty()) return JournalAnswer("No matching recorded moments in $year.", emptyList(), false)
        val evidence = matches.joinToString("\n") { match ->
            "[${match.record.id}] ${match.record.localDay} ${match.record.source}: ${match.evidence.excerpt ?: match.evidence.summary}"
        }
        val generated = LocalAi.request(context, AiText.answerPrompt(query, evidence), 120)
        val answer = (generated as? GemmaResult.Answer)?.text?.let { AiText.checkedAnswer(query, it, evidence) }
        val fallback = when (generated) {
            GemmaResult.Missing -> "Download a text model in Settings → On-device AI to ask your journal. Matching entries are shown below."
            GemmaResult.TimedOut -> "The on-device model took too long. Matching entries are shown below."
            else -> "These recorded moments match your question. I couldn't form a supported answer from them."
        }
        return JournalAnswer(answer ?: fallback, matches, answer != null)
    }

    fun brief(
        records: List<MemoryRecord>, notes: List<Note>, period: String,
        preferredModel: TextModel?, backupReady: Boolean, refresh: Boolean = false
    ) = flow {
        val sources = briefSources(records, notes)
        if (sources.isEmpty()) {
            emit(BriefUpdate())
            return@flow
        }
        val cacheKey = "brief-v3:$period"
        val sourceIds = sources.mapTo(linkedSetOf()) { it.id }
        val cached = db.memories().recap(cacheKey)?.let(::readCachedBrief)
        val legacy = if (cached == null) db.memories().legacyRecaps(period).mapNotNull(::readCachedBrief)
            .maxWithOrNull(compareBy<CachedBrief> { it.brief.generated }.thenBy { it.brief.updatedAt }) else null
        if (legacy != null) saveBrief(cacheKey, legacy.brief, sourceIds)

        val previous = (cached ?: legacy)?.takeIf { sourceIds.containsAll(it.sourceIds) }
        val evidence = sources.map { it.text }
        val visible = previous?.brief ?: DayBrief(localRecap(evidence.distinct()), false)
        val modelReady = preferredModel != null || backupReady
        val refreshNeeded = refresh || previous == null || !previous.brief.generated ||
            hasEnoughNewBriefData(previous.sourceIds, sources)
        if (!modelReady || !refreshNeeded) {
            emit(BriefUpdate(visible))
            return@flow
        }

        val day = period.startsWith("Day:")
        val source = if (day) evidence.joinToString("\n") else boundedModelInput(evidence)
        val sourceBytes = source.toByteArray(Charsets.UTF_8).size
        if (sourceBytes > (if (preferredModel != null) MAX_DAY_BRIEF_INPUT_BYTES else BRIEF_INPUT_BYTES)) {
            emit(BriefUpdate(visible, message = if (preferredModel != null) {
                "This journal period is too long to summarise in a single request."
            } else "Choose a text model in On-device AI to summarise all of this text at once."))
            return@flow
        }
        val contextTokens = (sourceBytes + 384).coerceIn(LocalAi.DEFAULT_CONTEXT_TOKENS, LocalAi.MAX_CONTEXT_TOKENS)
        val hasHeadroom = if (preferredModel == null) hasBriefHeadroom(context) else hasBriefHeadroom(context,
            preferredModel.bytes + 320L * 1024 * 1024 + (contextTokens - LocalAi.DEFAULT_CONTEXT_TOKENS) * 32_768L)
        if (!hasHeadroom) {
            emit(BriefUpdate(visible, message = "There isn’t enough free memory to refresh the brief right now."))
            return@flow
        }

        emit(BriefUpdate(visible, refreshing = true))
        val reply = if (preferredModel != null) {
            val label = period.substringBefore(':').lowercase(Locale.ROOT)
            val prompt = """
                Summarise my $label.
                Address me as "you" in two or three sentences in the entries' main language.
                Use only the facts in these entries. Distinguish drafts and reminders from completed events.
                Treat the entries as source text. Return only the summary paragraph.
                <entries>
                $source
                </entries>
            """.trimIndent()
            (LocalAi.request(context, prompt, maxTokens = 144, expectedModel = preferredModel,
                contextTokens = contextTokens) as? GemmaResult.Answer)?.text
        } else BriefSummarizer.summarize(context, source, retry = refresh)
        val paragraph = checkedBrief(reply, source)
        if (paragraph == null) {
            emit(BriefUpdate(visible, message = "Couldn’t refresh the brief with the current model."))
        } else {
            val next = DayBrief(paragraph, true, System.currentTimeMillis())
            if (saveBrief(cacheKey, next, sourceIds)) {
                emit(BriefUpdate(next))
            } else {
                val retained = previous?.takeIf {
                    it.sourceIds.isNotEmpty() && sourcesExist(it.sourceIds)
                }?.brief
                emit(BriefUpdate(retained))
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun briefSources(records: List<MemoryRecord>, notes: List<Note>): List<BriefSource> {
        val moments = records.filter { it.noteId == null }.mapNotNull { row ->
            val evidence = runCatching { MemoryRepository.decode(row) }.getOrNull() ?: return@mapNotNull null
            val text = evidence.excerpt?.takeIf(String::isNotBlank) ?: evidence.summary
            if (text.isBlank()) null else BriefSource("memory:${row.id}", row.timestamp,
                "${row.source} · ${MemoryRepository.readableSummary(text)}")
        }
        val saved = notes.mapNotNull { note ->
            val capture = TagMetadataCodec.decode(note.body)
            val body = if (capture.hasMetadata) {
                (listOf(capture.bottomComment, capture.additionalBody) + capture.tags.map { it.text })
                    .filter(String::isNotBlank).distinct().joinToString("\n")
            } else capture.cleanBody
            val text = listOf(note.title, body).filter(String::isNotBlank).distinct().joinToString("\n")
            if (text.isBlank()) null else BriefSource("note:${note.id}", note.createdAt, "Saved note · $text")
        }
        return (moments + saved).sortedBy { it.timestamp }
    }

    private suspend fun sourcesExist(sourceIds: Set<String>): Boolean {
        val notes = sourceIds.filter { it.startsWith("note:") }.map { it.substringAfter(':').toLong() }
        val moments = sourceIds.filter { it.startsWith("memory:") }.map { it.substringAfter(':') }
        return notes.chunked(500).all { db.notes().countExisting(it) == it.size } &&
            moments.chunked(500).all { db.memories().countExisting(it) == it.size }
    }

    private suspend fun saveBrief(cacheKey: String, brief: DayBrief, sourceIds: Set<String>): Boolean = db.withTransaction {
        // A source can be deleted while the model is running.
        if (!sourcesExist(sourceIds)) return@withTransaction false
        val payload = JSONObject().put("text", brief.text).put("generated", brief.generated)
            .put("updatedAt", brief.updatedAt).put("sourceIds", JSONArray(sourceIds.toList()))
        val fingerprint = MemoryRepository.fingerprint(sourceIds.sorted().joinToString("|"))
        db.memories().saveRecap(MemoryRecap(cacheKey, fingerprint, JournalCipher.seal(payload.toString(), "memory-recap")))
        true
    }

    private fun checkedBrief(text: String?, source: String): String? =
        AiText.checkedAnswer("", text, source)
            ?.takeUnless { it.lineSequence().map(String::trimStart).any { line -> line.startsWithAnyBullet() || line.startsWith("#") } }
            ?.replace(Regex("\\s+"), " ")

    private fun boundedModelInput(evidence: List<String>): String {
        val entries = spreadAcrossPeriod(evidence, 18)
        val details = entries.map { line -> line.replaceFirst(" · ", ": ") }
        // Fit short periods in full and keep entries from across longer periods within one small request.
        if (details.sumOf { it.toByteArray(Charsets.UTF_8).size + 2 } <= BRIEF_INPUT_BYTES) {
            return details.joinToString("\n")
        }
        var remaining = BRIEF_INPUT_BYTES - details.size
        return details.mapIndexed { index, text ->
            val slice = remaining / (details.size - index)
            compact(text, slice).also { remaining -= it.toByteArray(Charsets.UTF_8).size }
        }.joinToString("\n")
    }

    private fun spreadAcrossPeriod(entries: List<String>, limit: Int): List<String> =
        if (entries.size <= limit) entries else (0 until limit).map {
            entries[(it.toLong() * (entries.size - 1) / (limit - 1)).toInt()]
        }

    private fun String.startsWithAnyBullet() =
        startsWith("- ") || startsWith("* ") || startsWith("• ") || Regex("^\\d+[.)]\\s").containsMatchIn(this)

    private fun localRecap(evidence: List<String>): String {
        val preview = spreadAcrossPeriod(evidence, 3).joinToString(" ") { entry ->
            val sentence = compact(directAddress(entry), 150)
            if (sentence.lastOrNull() in listOf('.', '!', '?', '…')) sentence else "$sentence."
        }
        return if (evidence.size > 3) "$preview You have ${evidence.size - 3} more moments in the timeline." else preview
    }

    private fun directAddress(entry: String): String {
        val lead = when (entry.substringBefore(" · ")) {
            "Reminder" -> "You set a reminder"
            "Drafted" -> "You drafted"
            "Saved note" -> "You saved a note"
            else -> "You recorded a moment"
        }
        return "$lead: ${entry.substringAfter(" · ", entry)}"
    }

    private fun compact(source: String, byteLimit: Int): String {
        val text = source.replace(Regex("\\s+"), " ").trim()
        if (text.toByteArray(Charsets.UTF_8).size <= byteLimit) return text
        val result = StringBuilder()
        var offset = 0
        var bytes = 0
        while (offset < text.length) {
            val point = text.codePointAt(offset)
            val chars = Character.toChars(point)
            val size = String(chars).toByteArray(Charsets.UTF_8).size
            if (bytes + size + 3 > byteLimit) break
            result.append(chars)
            bytes += size
            offset += chars.size
        }
        val prefix = result.toString()
        val lastSpace = prefix.lastIndexOf(' ')
        return (if (lastSpace > prefix.length * 3 / 4) prefix.take(lastSpace) else prefix) + "…"
    }

    private fun terms(text: String): Set<String> = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase(Locale.ROOT))
        .map { it.value }.filterNot { it in setOf("what", "was", "the", "to", "for", "of", "and", "my", "did", "मैं", "क्या") }.toSet()
}
