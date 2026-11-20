package com.notokens.monolog.ime

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WordSuggestion(val typed: String, val replacement: String)

internal class EnglishSuggestions(private val context: Context) {
    private val token = Regex("[A-Za-z]+(?:'[A-Za-z]+)?$")
    private val wordLists by lazy {
        val suggestions = mutableSetOf<String>()
        val swipeWords = mutableSetOf<String>()
        runCatching {
            context.assets.open("english-4.0.0.dic").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val entry = line.substringBefore('\t')
                    val word = entry.substringBefore('/')
                    if (word.length in 2..24 && word.all { it in 'a'..'z' || it == '\'' }) {
                        suggestions.add(word)
                        val flags = entry.substringAfter('/', "")
                        if ('!' !in flags) swipeWords.addAll(swipeWordForms(word, flags))
                    }
                }
            }
        }
        suggestions.sorted() to SwipeDecoder(swipeWords.toList())
    }
    private val dictionary: List<String> get() = wordLists.first
    val swipeDecoder: SwipeDecoder get() = wordLists.second

    suspend fun suggest(beforeCursor: String): List<WordSuggestion> = withContext(Dispatchers.Default) {
        if (beforeCursor.endsWith(' ')) {
            val previous = token.find(beforeCursor.trimEnd())?.value?.lowercase(Locale.ROOT)
            return@withContext previous?.let(nextWords::get).orEmpty().map { WordSuggestion("", "$it ") }
        }
        val match = token.find(beforeCursor) ?: return@withContext emptyList()
        val typed = match.value
        if (typed.length !in 2..24 || beforeCursor.getOrNull(match.range.first - 1) in listOf('@', '/', '.')) {
            return@withContext emptyList()
        }
        val word = typed.lowercase(Locale.ROOT)
        val corrections = if (word.length >= 3 && dictionary.binarySearch(word) < 0) oneEdit(word) else emptyList()
        val index = dictionary.binarySearch(word).let { if (it >= 0) it else -it - 1 }
        val completions = dictionary.subList(index, dictionary.size).asSequence().takeWhile { it.startsWith(word) }
            .filter { it != word }.take(48).sortedBy { it.length }.take(3).toList()
        (corrections + completions).distinct().take(3).map { candidate ->
            val cased = when {
                typed.all { it.isUpperCase() } -> candidate.uppercase(Locale.ROOT)
                typed[0].isUpperCase() -> candidate.replaceFirstChar { it.titlecase(Locale.ROOT) }
                else -> candidate
            }
            WordSuggestion(typed, "$cased ")
        }
    }

    private fun oneEdit(word: String): List<String> {
        val found = linkedSetOf<String>()
        fun add(candidate: String) { if (dictionary.binarySearch(candidate) >= 0) found.add(candidate) }
        for (i in 0 until word.lastIndex) {
            add(word.take(i) + word[i + 1] + word[i] + word.drop(i + 2))
        }
        for (i in word.indices) add(word.removeRange(i, i + 1))
        for (i in 0..word.length) for (c in 'a'..'z') {
            add(word.take(i) + c + word.drop(i))
            if (i < word.length && word[i] != c) add(word.take(i) + c + word.drop(i + 1))
        }
        return found.take(3)
    }

    private companion object {
        val nextWords = mapOf(
            "i" to listOf("am", "will", "have"), "you" to listOf("are", "can", "will"),
            "we" to listOf("are", "can", "will"), "thank" to listOf("you"),
            "how" to listOf("are", "is", "do"), "good" to listOf("morning", "afternoon", "night"),
            "please" to listOf("send", "let", "check"), "will" to listOf("be", "have", "do"),
            "see" to listOf("you", "the", "if"), "let" to listOf("me", "us"),
            "want" to listOf("to", "some"), "going" to listOf("to", "out"),
            "the" to listOf("same", "next", "first"), "to" to listOf("be", "have", "get")
        )
    }
}
