package com.notokens.monolog.memory

import android.content.Context
import com.notokens.monolog.ai.AiText
import com.notokens.monolog.ai.GemmaResult
import com.notokens.monolog.ai.LocalAi
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

internal data class MemoryMatch(val record: MemoryRecord, val evidence: MemoryEvidence, val score: Double)
internal data class JournalAnswer(val text: String, val matches: List<MemoryMatch>, val paraphrase: Boolean)
internal data class DayBrief(val text: String, val sourceIds: List<String>, val generated: Boolean)

internal class MemorySearch(private val context: Context,
    private val db: MonologDatabase = MonologDatabase.get(context)) {
    suspend fun search(query: String, year: Int = LocalDate.now().year): List<MemoryMatch> = withContext(Dispatchers.Default) {
        val records = db.memories().year(year)
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
            GemmaResult.Missing -> "Download Gemma in Settings → On-device AI to ask your journal. Matching entries are shown below."
            GemmaResult.TimedOut -> "The on-device model took too long. Matching entries are shown below."
            else -> "These recorded moments match your question. I couldn't form a supported answer from them."
        }
        return JournalAnswer(answer ?: fallback, matches, answer != null)
    }

    fun brief(records: List<MemoryRecord>, notes: List<Note>, period: String, refresh: Boolean = false) =
        flow {
            val revisions = records.sortedBy { it.id }.joinToString("|") { "memory:${it.id}:${it.revision}" }
            val noteRevisions = notes.sortedBy { it.id }.joinToString("|") { "note:${it.id}:${it.updatedAt}" }
            val cacheKey = "litert-brief-v1:$period"
            val fingerprint = MemoryRepository.fingerprint("$revisions|$noteRevisions")
            val sourceIds = records.map { it.id }
            if (!refresh) {
                db.memories().recap(cacheKey)?.takeIf { it.fingerprint == fingerprint }?.let {
                    runCatching { JournalCipher.open(it.payload, "memory-recap") }.getOrNull()?.let { cached ->
                        emit(DayBrief(cached, sourceIds, true))
                        return@flow
                    }
                }
            }

            val moments = records.filter { it.noteId == null }.mapNotNull { row ->
                val summary = runCatching { MemoryRepository.decode(row).summary }.getOrNull().orEmpty()
                summary.takeIf(String::isNotBlank)?.let {
                    row.timestamp to "${row.source} · ${MemoryRepository.readableSummary(it)}"
                }
            }
            val saved = notes.mapNotNull { note ->
                val capture = TagMetadataCodec.decode(note.body)
                val body = if (capture.hasMetadata) {
                    (listOf(capture.bottomComment) + capture.tags.map { it.text }).filter(String::isNotBlank).joinToString(". ")
                } else capture.cleanBody
                val text = listOf(note.title, body).filter(String::isNotBlank).distinct().joinToString(". ")
                text.takeIf(String::isNotBlank)?.let { note.createdAt to "Saved note · $it" }
            }
            val evidence = (moments + saved).sortedBy { it.first }.map { compact(it.second, 380) }.distinct()
            if (evidence.isEmpty()) {
                emit(DayBrief("Your saved moments will build a picture of this period.", sourceIds, false))
                return@flow
            }

            emit(DayBrief(localRecap(evidence), sourceIds, false))
            if (evidence.size == 1 && evidence.single().length < 180) return@flow
            val source = boundedModelInput(evidence)
            val result = BriefSummarizer.summarize(context, source, retry = refresh)
            val paragraph = AiText.checkedAnswer("", result, source)
                ?.takeUnless { it.lineSequence().any { line -> line.trimStart().startsWithAnyBullet() } }
                ?: return@flow
            db.memories().saveRecap(MemoryRecap(cacheKey, fingerprint, JournalCipher.seal(paragraph, "memory-recap")))
            emit(DayBrief(paragraph, sourceIds, true))
        }.flowOn(Dispatchers.Default)

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
        val details = evidence.map { it.substringAfter(" · ", it).trim().trimEnd('.', '!', '?') }
        val preview = spreadAcrossPeriod(details, 6).joinToString(" ") { compact(it, 112).replaceFirstChar(Char::titlecase) + "." }
        return if (details.size > 6) "$preview The timeline includes ${details.size - 6} more moments." else preview
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
