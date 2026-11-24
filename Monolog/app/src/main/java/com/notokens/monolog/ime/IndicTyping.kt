package com.notokens.monolog.ime

import android.content.Context
import android.icu.text.Transliterator
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ime.vox.RegionalVox
import com.notokens.monolog.ime.vox.VoxLanguage
import java.text.Normalizer
import java.util.Locale

internal data class RomanWord(val latin: String, val native: String, val votes: Int)

internal class IndicLexicon(
    private val frequency: Map<String, Int>,
    private val continuations: Map<String, List<String>>,
    romanWords: List<RomanWord>,
    voicePieces: List<String> = emptyList(),
    private val phoneticFallback: (String) -> String? = { null }
) {
    private val nativeWords = (frequency.keys + romanWords.map { it.native } + voicePieces).distinct().sorted()
    private val byLatin = romanWords.groupBy { it.latin }.mapValues { (_, candidates) ->
        candidates.distinctBy { it.native }.sortedWith(compareByDescending<RomanWord>(::weight).thenBy { it.native })
    }
    private val latinWords = byLatin.keys.sorted()
    private val swiper by lazy {
        val common = byLatin.entries.sortedByDescending { weight(it.value.first()) }.take(300).map { it.key }.toSet()
        SwipeDecoder(latinWords, common)
    }

    fun suggestNative(word: String): List<String> = prefix(nativeWords, word)
        .filter { it != word }
        .sortedWith(compareByDescending<String> { frequency[it] ?: 0 }.thenBy { it.length })
        .take(3).toList()

    fun suggestNativeNext(previous: String): List<String> = continuations[previous].orEmpty()

    val commonWords: List<String> by lazy {
        frequency.entries.sortedByDescending { it.value }.take(8).map { it.key }
    }

    fun suggestRoman(word: String, previous: String? = null): List<String> {
        val exact = rankedRoman(word, previous).map { it.native }
        if (exact.size >= 3) return exact.take(3)
        val completions = prefix(latinWords, word).filter { it != word }
            .flatMap { byLatin.getValue(it).asSequence().take(2) }
            .sortedByDescending { contextualWeight(it, previous) }.map { it.native }
        val ranked = (exact.asSequence() + completions).distinct().take(3).toList()
        if (ranked.size >= 3 || exact.isNotEmpty()) return ranked
        return (ranked + listOfNotNull(phoneticFallback(word))).distinct().take(3)
    }

    fun exactRoman(word: String, previous: String? = null): String? =
        rankedRoman(word, previous).firstOrNull { it.votes > 0 }?.native

    fun swipe(points: List<SwipePoint>, previous: String? = null): List<String> {
        val latin = swiper.decode(points) ?: return emptyList()
        return rankedRoman(latin, previous).take(3).map { it.native }
    }

    private fun rankedRoman(word: String, previous: String?): List<RomanWord> {
        val choices = byLatin[word].orEmpty()
        return if (choices.size < 2 || previous !in continuations) choices
        else choices.sortedByDescending { contextualWeight(it, previous) }
    }

    private fun contextualWeight(word: RomanWord, previous: String?): Int {
        val position = continuations[previous]?.indexOf(word.native) ?: -1
        return weight(word) + if (position >= 0) (3 - position) * 18 else 0
    }

    private fun weight(word: RomanWord): Int =
        word.votes.coerceAtMost(20) * 12 + (frequency[word.native] ?: 0).coerceAtMost(65) * 2

    private fun prefix(words: List<String>, target: String): Sequence<String> {
        val start = words.binarySearch(target).let { if (it >= 0) it else -it - 1 }
        return words.subList(start, words.size).asSequence().takeWhile { it.startsWith(target) }.take(700)
    }
}

internal class IndicTyping(private val context: Context) {
    private data class Cached(val code: String, val voiceReady: Boolean, val lexicon: IndicLexicon)
    @Volatile private var cached: Cached? = null

    fun warm(language: KeyboardLanguage) {
        lexicon(language)
    }

    fun suggest(before: String, language: KeyboardLanguage): List<WordSuggestion> {
        val word = typedWord(before.trimEnd()) ?: return emptyList()
        val includesSpace = before.lastOrNull()?.isWhitespace() == true
        val lexicon = lexicon(language) ?: return emptyList()
        if (includesSpace) {
            return if (language.transliterationTarget != null) emptyList()
                else lexicon.suggestNativeNext(word).map { WordSuggestion("", "$it ") }
        }
        val normalized = Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFC)
        val previous = typedWord(before.dropLast(word.length).trimEnd())
        val choices = if (language.transliterationTarget != null) lexicon.suggestRoman(normalized, previous)
            else lexicon.suggestNative(normalized)
        return choices.map { WordSuggestion(word, "$it ") }
    }

    fun exact(before: String, language: KeyboardLanguage): WordSuggestion? {
        val target = language.transliterationTarget ?: return null
        val word = typedWord(before) ?: return null
        val preceding = before.getOrNull(before.length - word.length - 1)
        if (preceding != null && !preceding.isWhitespace() && preceding !in "([\"“‘") return null
        val normalized = word.lowercase(Locale.ROOT).takeIf(latinWord::matches) ?: return null
        val previous = typedWord(before.dropLast(word.length).trimEnd())
        val match = cached?.takeIf { it.code == target }?.lexicon?.exactRoman(normalized, previous) ?: return null
        return WordSuggestion(word, match)
    }

    fun swipe(points: List<SwipePoint>, language: KeyboardLanguage, before: String): List<String> =
        lexicon(language)?.swipe(points, typedWord(before.trimEnd())).orEmpty()

    fun nextAfter(word: String, language: KeyboardLanguage): List<String> =
        lexicon(language)?.suggestNativeNext(Normalizer.normalize(word.lowercase(Locale.ROOT),
            Normalizer.Form.NFC)).orEmpty()

    fun commonWords(language: KeyboardLanguage): List<String> = lexicon(language)?.commonWords.orEmpty()

    private fun lexicon(language: KeyboardLanguage): IndicLexicon? {
        val native = language.transliterationTarget?.let(KeyboardLanguage::of) ?: language
        val block = native.script ?: return null
        val voiceCode = native.id.substringBefore('-')
        val voice = VoxLanguage.entries.firstOrNull { it.code == voiceCode }
        val ready = voice != null && RegionalVox.ready(context, voice)
        cached?.takeIf { it.code == native.id && it.voiceReady == ready }?.let { return it.lexicon }
        return build(native.id, block, ready, voiceCode)
    }

    @Synchronized
    private fun build(code: String, block: IntRange, voiceReady: Boolean, voiceCode: String): IndicLexicon {
        cached?.takeIf { it.code == code && it.voiceReady == voiceReady }?.let { return it.lexicon }
        val frequency = linkedMapOf<String, Int>()
        val pairs = mutableMapOf<String, MutableList<String>>()
        val romanWords = mutableListOf<RomanWord>()
        fun belongs(word: String): Boolean =
            word.any { it.code in block && it.isLetter() } &&
                word.all { it.code in block || it == '\u200c' || it == '\u200d' }

        read("flores-$code.tsv") { columns ->
            val first = columns.first()
            if (!belongs(first)) return@read
            if (columns.size == 2) frequency[first] = columns[1].toIntOrNull() ?: 0
            else if (columns.size == 3 && belongs(columns[1])) {
                val next = pairs.getOrPut(first) { mutableListOf() }
                if (next.size < 3) next.add(columns[1])
            }
        }
        for (source in listOf("seed", "dakshina")) {
            read("$source-$code.tsv") { columns ->
                if (columns.size == 3 && latinWord.matches(columns[0]) && belongs(columns[1])) {
                    romanWords.add(RomanWord(columns[0], columns[1], columns[2].toIntOrNull() ?: 1))
                }
            }
        }
        val pieces = if (voiceReady) RegionalVox.typingVocabulary(context, voiceCode).filter {
            // Voice subwords are optional choices and never auto-replace typed text.
            belongs(it) && it.length >= 3
        }.take(1200) else emptyList()
        val script = when (block.first) {
            in 0x0900..0x097f -> "Devanagari"
            in 0x0980..0x09ff -> "Bengali"
            in 0x0a00..0x0a7f -> "Gurmukhi"
            in 0x0a80..0x0aff -> "Gujarati"
            in 0x0b00..0x0b7f -> "Oriya"
            in 0x0b80..0x0bff -> "Tamil"
            in 0x0c00..0x0c7f -> "Telugu"
            in 0x0c80..0x0cff -> "Kannada"
            in 0x0d00..0x0d7f -> "Malayalam"
            in 0x0600..0x06ff -> "Arabic"
            else -> null
        }
        val reverse = runCatching { Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC") }.getOrNull()
        val covered = romanWords.mapTo(hashSetOf()) { it.native }
        (frequency.keys.asSequence() + pieces.asSequence()).filterNot(covered::contains).take(700).forEach { word ->
            val spelling = (regionalLatinKey(code, word) ?: reverse?.transliterate(word))
                ?.lowercase(Locale.ROOT)
            if (spelling != null && latinWord.matches(spelling)) romanWords.add(RomanWord(spelling, word, 0))
        }
        val forward = script?.let { runCatching { Transliterator.getInstance("Latin-$it") }.getOrNull() }
        val fallback: (String) -> String? = { input ->
            if (input.length < 2 || !latinWord.matches(input)) null
            else forward?.let { synchronized(it) { it.transliterate(input) } }?.takeIf(::belongs)
        }
        return IndicLexicon(frequency, pairs, romanWords, pieces, fallback).also {
            cached = Cached(code, voiceReady, it)
        }
    }

    private fun read(name: String, onRow: (List<String>) -> Unit) {
        try {
            context.assets.open("typing/indic/$name").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { onRow(it.split('\t')) }
            }
        } catch (_: java.io.IOException) {
            // Some languages have a built-in starter list and gain more coverage from a voice pack.
        }
    }

    private companion object {
        val latinWord = Regex("[a-z]{1,24}")
        val finalWord = Regex("""[\p{L}\p{M}\u200c\u200d]+(?:['’][\p{L}\p{M}\u200c\u200d]+)?$""")
        fun typedWord(text: String) = finalWord.find(text)?.value?.takeIf { it.length <= 24 }
    }
}

// Fallback spellings for existing words, used only for suggestions. Vowel lengths
// and other distinctions may share a Latin key.
internal fun regionalLatinKey(language: String, text: String): String? = when (language) {
    "sat" -> spellSantali(text)
    "mni" -> spellMeitei(text)
    else -> null
}

private val santaliLatin = listOf("o", "t", "g", "ng", "l", "a", "k", "j", "m", "w", "i",
    "s", "h", "ny", "r", "u", "c", "d", "n", "y", "e", "p", "d", "n", "r", "o", "t", "b", "w", "h")

private fun spellSantali(text: String): String? = buildString {
    for (character in text) {
        if (character in '\u1c78'..'\u1c7d') continue
        append(santaliLatin.getOrNull(character.code - 0x1c5a) ?: return null)
    }
}

private val meeteiLatin = listOf("k", "s", "l", "m", "p", "n", "ch", "t", "kh", "ng", "th", "w",
    "y", "h", "u", "i", "ph", "a", "g", "jh", "r", "b", "j", "d", "gh", "dh", "bh",
    "k", "l", "m", "p", "n", "t", "ng", "i")
private val meeteiVowels = listOf("o", "i", "aa", "e", "ou", "u", "ei")

private fun spellMeitei(text: String): String? = buildString {
    var replaceVowel = false
    for (character in text) {
        when (character.code) {
            in 0xabc0..0xabe2 -> {
                append(meeteiLatin[character.code - 0xabc0])
                val inherent = character.code <= 0xabda && character.code !in setOf(0xabce, 0xabcf, 0xabd1)
                if (inherent) append('a')
                replaceVowel = inherent || character.code == 0xabd1
            }
            in 0xabe3..0xabe9 -> {
                if (replaceVowel) deleteCharAt(lastIndex)
                append(meeteiVowels[character.code - 0xabe3])
                replaceVowel = false
            }
            0xabea -> { append("ng"); replaceVowel = false }
            0xabed -> {
                if (replaceVowel) deleteCharAt(lastIndex)
                replaceVowel = false
            }
            else -> return null
        }
    }
}
