package com.notokens.monolog.data

import com.notokens.monolog.capture.TagMetadataCodec
import java.util.Locale

private val searchWord = Regex("[\\p{L}\\p{N}]+")

internal fun List<Note>.matchingJournalEntries(query: String, limit: Int = Int.MAX_VALUE): List<Note> {
    val terms = searchWord.findAll(query).map { it.value }.distinct().toList()
    if (terms.isEmpty()) return emptyList()
    return asSequence().filter { note ->
        terms.all { term -> note.title.contains(term, ignoreCase = true) || note.body.contains(term, ignoreCase = true) }
    }.take(limit).toList()
}

private val captureReference = Regex("""\b(?:my|journal|capture|screenshot)\b""", RegexOption.IGNORE_CASE)
private val questionWords = setOf("what", "which", "when", "where", "were", "does", "this", "that", "with", "have", "show", "screenshot", "capture", "journal")

internal fun String.asksAboutJournalCapture(): Boolean = captureReference.containsMatchIn(this)

internal fun List<Note>.captureEvidence(query: String): String {
    val terms = searchWord.findAll(query.lowercase(Locale.ROOT))
        .map { it.value }.filter { it.length >= 3 && it !in questionWords }.toSet()
    if (terms.isEmpty()) return ""
    return asSequence().mapNotNull { note ->
        val decoded = TagMetadataCodec.decode(note.body)
        val analysis = (decoded.analysis + "\n" + decoded.imageKeywords.joinToString(", ")).trim()
        if (analysis.isBlank()) return@mapNotNull null
        val lines = analysis.lineSequence().map { it.removePrefix("> ") }.toList()
        val matches = lines.filter { line -> terms.any { line.contains(it, ignoreCase = true) } }
        val rank = terms.count { term -> note.title.contains(term, ignoreCase = true) || matches.any { it.contains(term, ignoreCase = true) } }
        if (rank == 0) null else rank to (listOf(note.title) + matches + lines).distinct().joinToString("\n")
    }.maxByOrNull { it.first }?.second?.take(1_400).orEmpty()
}
