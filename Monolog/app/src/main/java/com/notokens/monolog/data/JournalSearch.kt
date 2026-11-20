package com.notokens.monolog.data

private val searchWord = Regex("[\\p{L}\\p{N}]+")

internal fun List<Note>.matchingJournalEntries(query: String, limit: Int = Int.MAX_VALUE): List<Note> {
    val terms = searchWord.findAll(query).map { it.value }.distinct().toList()
    if (terms.isEmpty()) return emptyList()
    return asSequence().filter { note ->
        terms.all { term -> note.title.contains(term, ignoreCase = true) || note.body.contains(term, ignoreCase = true) }
    }.take(limit).toList()
}
