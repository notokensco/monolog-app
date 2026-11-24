package com.notokens.monolog.ime

import android.content.Context
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.data.TypingProfile
import com.notokens.monolog.data.wordKey
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WordSuggestion(
    val typed: String,
    val replacement: String,
    val label: String = replacement.trimEnd(),
    val emphasized: Boolean = false,
    val nextWord: Boolean = typed.isEmpty()
)

internal class EnglishSuggestions(private val context: Context) {
    private val token = Regex("""[\p{L}\p{M}]+(?:'[\p{L}\p{M}]+)?\z""")
    private val indic = IndicTyping(context)
    fun warm(language: KeyboardLanguage) { indic.warm(language) }
    fun automaticTransliteration(before: String, language: KeyboardLanguage): WordSuggestion? =
        indic.exact(before, language)

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
        suggestions.addAll(TypingVocabulary.english)
        swipeWords.addAll(TypingVocabulary.english)
        (suggestions.sorted() to SwipeDecoder(swipeWords.toList(), TypingVocabulary.english))
    }
    private val dictionary: List<String> get() = wordLists.first
    val swipeDecoder: SwipeDecoder get() = wordLists.second
    val hinglishSwipeDecoder by lazy {
        SwipeDecoder((TypingVocabulary.hinglish + TypingVocabulary.english).toList(), TypingVocabulary.hinglish)
    }
    private data class PreparedCorrection(val context: String, val replacement: String)
    @Volatile private var preparedCorrection: PreparedCorrection? = null
    private data class PersonalRank(
        val wordsByPrefix: List<Pair<String, String>> = emptyList(),
        val known: Set<String> = emptySet(),
        val hidden: Set<String> = emptySet(),
        val frequency: Map<String, Int> = emptyMap()
    )

    @Volatile private var personalRank = PersonalRank()

    fun setTypingProfile(words: Set<String>, profile: TypingProfile, learning: Boolean) {
        val hidden = profile.words.filterValues { it.uses == 0 }.keys
        val counts = if (learning) profile.words.filterValues { it.uses > 0 }.mapValues { it.value.uses } else emptyMap()
        val learned = if (learning) profile.words.values.filter { it.uses > 0 }.map { it.text } else emptyList()
        val manual = words.mapTo(hashSetOf(), ::wordKey)
        val ranked = (words + learned).distinctBy(::wordKey).filterNot { wordKey(it) in hidden }
            .sortedWith(compareByDescending<String> { wordKey(it) in manual }
                .thenByDescending { counts[wordKey(it)] ?: 0 }.thenBy { it })
        personalRank = PersonalRank(ranked.map { wordKey(it) to it }, ranked.mapTo(hashSetOf(), ::wordKey), hidden, counts)
    }

    fun inPersonalDictionary(word: String): Boolean = wordKey(word) in personalRank.known
    fun hidesSuggestion(word: String): Boolean = wordKey(word) in personalRank.hidden
    private fun personalCompletions(typed: String): List<String> {
        val prefix = wordKey(typed)
        return personalRank.wordsByPrefix.asSequence().filter { it.first.startsWith(prefix) }.take(3).map { it.second }.toList()
    }

    private fun rankPublicWords(words: List<String>, personalize: Boolean): List<String> {
        if (!personalize) return words
        val profile = personalRank
        return words.filterNot { wordKey(it) in profile.hidden }.sortedByDescending { profile.frequency[wordKey(it)] ?: 0 }
    }

    fun continuations(before: String, hinglish: Boolean): List<String> {
        val previous = token.find(before.trimEnd())?.value?.lowercase(Locale.ROOT)
        if (previous == null) return emptyList()
        val fallback = (if (hinglish) TypingVocabulary.hinglishPairs[previous] ?: nextWords[previous]
            else nextWords[previous]).orEmpty()
        return (NativeTyping.nextWords(before, hinglish) + fallback).distinct().take(3)
    }

    fun nextAfter(word: String, language: KeyboardLanguage): List<String> = when {
        language.transliterationTarget != null || language.script != null -> indic.nextAfter(word, language)
        language.isEnglish || language.id == "hi-Latn" -> continuations(word, language.id == "hi-Latn")
        else -> emptyList()
    }

    /** Called on the key path: only a small lookup or a precomputed candidate, no dictionary work. */
    fun automaticCorrection(beforeCursor: String): WordSuggestion? {
        val match = token.find(beforeCursor) ?: return null
        val typed = match.value
        if (typed.length !in 2..24 || typed.all(Char::isUpperCase)) return null
        val preceding = beforeCursor.getOrNull(match.range.first - 1)
        if (preceding != null && !preceding.isWhitespace() && preceding !in "([\"“‘") return null
        val lower = typed.lowercase(Locale.ROOT)
        if (lower in personalRank.known) return null
        val replacement = commonTypos[lower]
            ?: preparedCorrection?.takeIf { it.context == beforeCursor.takeLast(128) }?.replacement
        if (replacement == null || hidesSuggestion(replacement)) return null
        val cased = if (typed.first().isUpperCase()) replacement.replaceFirstChar { it.titlecase(Locale.ROOT) } else replacement
        return WordSuggestion(typed, cased)
    }

    fun swipe(language: KeyboardLanguage, points: List<SwipePoint>, before: String): List<String> {
        if (language.transliterationTarget != null) return indic.swipe(points, language, before)
        val hinglish = language.id == "hi-Latn"
        NativeTyping.swipe(context, language, points, before, continuations(before, hinglish))?.let { return it }
        return when {
            language.isEnglish -> listOfNotNull(swipeDecoder.decode(points))
            hinglish -> listOfNotNull(hinglishSwipeDecoder.decode(points))
            else -> emptyList()
        }
    }

    suspend fun suggest(beforeCursor: String, language: String, personalized: Boolean = true): List<WordSuggestion> = withContext(Dispatchers.Default) {
        val hinglish = language == "hi-Latn"
        val layout = KeyboardLanguage.of(language)
        val legacy = layout.isEnglish || hinglish
        preparedCorrection = null
        if (layout.transliterationTarget != null || layout.script != null) {
            val regional = indic.suggest(beforeCursor, layout)
            if (beforeCursor.lastOrNull()?.isWhitespace() == true) return@withContext regional
            val typed = token.find(beforeCursor)?.value ?: return@withContext regional
            val personal = if (personalized) personalCompletions(typed)
                else emptyList()
            val predictions = rankPublicWords(nextAfter(typed, layout), personalized)
            return@withContext toolbarCandidates(typed, personal.firstOrNull() ?:
                regional.firstOrNull()?.label?.takeIf { predictions.isEmpty() },
                predictions, personal + regional.map { it.label })
        }
        if (beforeCursor.endsWith(' ')) {
            return@withContext (if (legacy) rankPublicWords(continuations(beforeCursor, hinglish), personalized) else emptyList()).map { WordSuggestion("", "$it ") }
        }
        val match = token.find(beforeCursor) ?: return@withContext emptyList()
        val typed = match.value
        if (typed.length !in 2..24 || beforeCursor.getOrNull(match.range.first - 1) in listOf('@', '/', '.')) {
            return@withContext emptyList()
        }
        val word = typed.lowercase(Locale.ROOT)
        val before = beforeCursor.take(match.range.first)
        val next = if (legacy) continuations(before, hinglish) else emptyList()
        val personal = if (personalized) personalCompletions(typed) else emptyList()
        NativeTyping.suggestions(context, if (layout.isEnglish) "en" else language, word, before, next)?.let { ranked ->
            val correction = ranked.correction?.takeUnless { personalized && hidesSuggestion(it) }
            if (layout.isEnglish && correction != null && typed == word && word.length >= 4) {
                preparedCorrection = PreparedCorrection(beforeCursor.takeLast(128), correction)
            }
            val alternatives = personal + rankPublicWords(ranked.words, personalized).map { cased(typed, it) }
            val primary = personal.firstOrNull() ?: automaticCorrection(beforeCursor)?.replacement
                ?: correction?.let { cased(typed, it) }
                ?: alternatives.firstOrNull().takeUnless { ranked.known }
            val predictions = rankPublicWords(nextAfter(primary ?: typed, layout), personalized)
            return@withContext toolbarCandidates(typed, primary, predictions, alternatives)
        }
        if (personal.isNotEmpty()) return@withContext toolbarCandidates(typed, personal.first(),
            nextAfter(personal.first(), layout), personal)
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
            if (candidates.isNotEmpty() || word in TypingVocabulary.hinglish) {
                val primary = candidates.firstOrNull()?.label.takeUnless { word in TypingVocabulary.hinglish }
                return@withContext toolbarCandidates(typed, primary, nextAfter(primary ?: typed, layout),
                    candidates.map { it.label })
            }
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
        val alternatives = (corrections + completions).map { cased(typed, it) }
        val primary = automaticCorrection(beforeCursor)?.replacement
            ?: alternatives.firstOrNull().takeIf { dictionary.binarySearch(word) < 0 }
        toolbarCandidates(typed, primary, nextAfter(primary ?: typed, layout), alternatives)
    }

    private fun toolbarCandidates(typed: String, primary: String?, next: List<String>,
        alternatives: List<String>): List<WordSuggestion> {
        val current = primary ?: typed.takeIf { next.isEmpty() && alternatives.isEmpty() }
        return buildList {
            current?.let { add(WordSuggestion(typed, "$it ", emphasized = true)) }
            next.take(2).forEachIndexed { index, word ->
                add(WordSuggestion(typed, "${current ?: typed} $word ", label = word,
                    emphasized = current == null && index == 0, nextWord = true))
            }
            alternatives.take(3).forEach { add(WordSuggestion(typed, "$it ")) }
        }.distinctBy { it.label.lowercase(Locale.ROOT) }
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
