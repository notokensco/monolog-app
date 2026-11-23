package com.notokens.monolog.ime

import android.content.Context
import com.notokens.monolog.ai.KeyboardLanguage
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WordSuggestion(val typed: String, val replacement: String)

internal class EnglishSuggestions(private val context: Context) {
    private val token = Regex("""[\p{L}\p{M}]+(?:'[\p{L}\p{M}]+)?\z""")
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
        (suggestions.sorted() to SwipeDecoder(swipeWords.toList(), TypingVocabulary.english))
    }
    private val dictionary: List<String> get() = wordLists.first
    val swipeDecoder: SwipeDecoder get() = wordLists.second
    val hinglishSwipeDecoder by lazy {
        SwipeDecoder((TypingVocabulary.hinglish + TypingVocabulary.english).toList(), TypingVocabulary.hinglish)
    }
    private data class PreparedCorrection(val context: String, val replacement: String)
    @Volatile private var preparedCorrection: PreparedCorrection? = null
    @Volatile private var personalWords: Map<String, String> = emptyMap()

    fun setPersonalWords(words: Set<String>) {
        personalWords = words.sorted().associateBy { it.lowercase(Locale.ROOT) }
    }

    fun continuations(before: String, hinglish: Boolean): List<String> {
        val previous = token.find(before.trimEnd())?.value?.lowercase(Locale.ROOT)
        if (previous == null) return emptyList()
        val fallback = (if (hinglish) TypingVocabulary.hinglishPairs[previous] ?: nextWords[previous]
            else nextWords[previous]).orEmpty()
        return (NativeTyping.nextWords(before, hinglish) + fallback).distinct().take(3)
    }

    /** Called on the key path: only a small lookup or a precomputed candidate, no dictionary work. */
    fun automaticCorrection(beforeCursor: String): WordSuggestion? {
        val match = token.find(beforeCursor) ?: return null
        val typed = match.value
        if (typed.length !in 2..24 || typed.all(Char::isUpperCase)) return null
        val preceding = beforeCursor.getOrNull(match.range.first - 1)
        if (preceding != null && !preceding.isWhitespace() && preceding !in "([\"“‘") return null
        val lower = typed.lowercase(Locale.ROOT)
        if (lower in personalWords) return null
        val replacement = commonTypos[lower]
            ?: preparedCorrection?.takeIf { it.context == beforeCursor.takeLast(128) }?.replacement
        if (replacement == null) return null
        val cased = if (typed.first().isUpperCase()) replacement.replaceFirstChar { it.titlecase(Locale.ROOT) } else replacement
        return WordSuggestion(typed, cased)
    }

    fun swipe(language: KeyboardLanguage, points: List<SwipePoint>, before: String): List<String> {
        val hinglish = language.id == "hi-Latn"
        NativeTyping.swipe(context, language, points, before, continuations(before, hinglish))?.let { return it }
        return when (language.id) {
            "en" -> listOfNotNull(swipeDecoder.decode(points))
            "hi-Latn" -> listOfNotNull(hinglishSwipeDecoder.decode(points))
            else -> emptyList()
        }
    }

    suspend fun suggest(beforeCursor: String, language: String, personalized: Boolean = true): List<WordSuggestion> = withContext(Dispatchers.Default) {
        val hinglish = language == "hi-Latn"
        val legacy = language == "en" || hinglish
        preparedCorrection = null
        if (beforeCursor.endsWith(' ')) {
            return@withContext (if (legacy) continuations(beforeCursor, hinglish) else emptyList()).map { WordSuggestion("", "$it ") }
        }
        val match = token.find(beforeCursor) ?: return@withContext emptyList()
        val typed = match.value
        if (typed.length !in 2..24 || beforeCursor.getOrNull(match.range.first - 1) in listOf('@', '/', '.')) {
            return@withContext emptyList()
        }
        val word = typed.lowercase(Locale.ROOT)
        val before = beforeCursor.take(match.range.first)
        val next = if (legacy) continuations(before, hinglish) else emptyList()
        val personal = (if (personalized) personalWords.entries else emptySet()).asSequence()
            .filter { (key, _) -> key.startsWith(word) && key != word }
            .map { it.value }.take(3).toList()
        NativeTyping.suggestions(context, language, word, before, next)?.let { ranked ->
            val correction = ranked.correction
            if (language == "en" && correction != null && typed == word && word.length >= 4) {
                preparedCorrection = PreparedCorrection(beforeCursor.takeLast(128), correction)
            }
            return@withContext (personal + ranked.words.map { cased(typed, it) })
                .distinctBy { it.lowercase(Locale.ROOT) }.take(3).map { WordSuggestion(typed, "$it ") }
        }
        if (personal.isNotEmpty()) return@withContext personal.map { WordSuggestion(typed, "$it ") }
        if (!legacy) return@withContext emptyList()
        if (hinglish) {
            val matches = (TypingVocabulary.hinglish + TypingVocabulary.english).asSequence()
                .filter { it != word && it.startsWith(word) }
                .sortedWith(compareBy<String> { it !in TypingVocabulary.hinglish }.thenBy { it.length })
                .toList()
            val candidates = matches.take(3).map { candidate ->
                val cased = if (typed.first().isUpperCase()) candidate.replaceFirstChar { it.titlecase() } else candidate
                WordSuggestion(typed, "$cased ")
            }
            if (candidates.isNotEmpty() || word in TypingVocabulary.hinglish) return@withContext candidates
        }
        val corrections = if (word.length >= 3 && dictionary.binarySearch(word) < 0) oneEdit(word) else emptyList()
        // Ambiguous replacements remain suggestions. Never infer a correction for a name.
        if (typed == word && word.length >= 4 && corrections.size == 1) {
            preparedCorrection = PreparedCorrection(beforeCursor.takeLast(128), corrections.single())
        }
        val index = dictionary.binarySearch(word).let { if (it >= 0) it else -it - 1 }
        val completions = dictionary.subList(index, dictionary.size).asSequence().takeWhile { it.startsWith(word) }
            .filter { it != word }
            .sortedWith(compareBy<String> { it !in TypingVocabulary.english }.thenBy { it.length })
            .take(128).toList()
        (corrections + completions).distinct().take(3).map { candidate ->
            WordSuggestion(typed, cased(typed, candidate) + " ")
        }
    }

    private fun cased(typed: String, word: String) = when {
        typed.all(Char::isUpperCase) -> word.uppercase(Locale.ROOT)
        typed.first().isUpperCase() -> word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        else -> word
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
        return found.sortedWith(compareBy<String> { it !in TypingVocabulary.english }.thenBy { it.length }).take(3)
    }

    private companion object {
        val commonTypos = mapOf(
            "teh" to "the", "thsi" to "this", "taht" to "that", "tihs" to "this",
            "adn" to "and", "nad" to "and", "hte" to "the", "wiht" to "with",
            "woudl" to "would", "coudl" to "could", "shoudl" to "should",
            "recieve" to "receive", "recieved" to "received", "becuase" to "because",
            "beacuse" to "because", "definately" to "definitely", "seperate" to "separate",
            "alot" to "a lot", "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
            "isnt" to "isn't", "wasnt" to "wasn't", "couldnt" to "couldn't",
            "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "youre" to "you're",
            "youve" to "you've", "thats" to "that's", "whats" to "what's"
        )
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
