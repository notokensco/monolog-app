package com.notokens.monolog.ime

import com.notokens.monolog.data.TypingMetric
import com.notokens.monolog.data.dictionaryWord
import com.notokens.monolog.data.lastContextWord
import com.notokens.monolog.data.wordKey

/** Keeps eligible words and aggregate counts in memory until the editor session ends. */
internal class TypingSession {
    private val current = StringBuilder()
    private val words = linkedMapOf<String, Int>()
    private val transitions = linkedMapOf<String, Int>()
    private val counters = java.util.EnumMap<TypingMetric, Long>(TypingMetric::class.java)
    private var skipWord = false
    private var wordBeforeCorrection: String? = null
    var hasPendingCorrection = false
        private set
    val hasUpdates get() = current.isNotEmpty() || counters.isNotEmpty()

    fun type(text: String) {
        increment(TypingMetric.KEYS)
        for (char in text) {
            if (char.isWhitespace() || char in ".,!?;:()") finishWord()
            else if (current.length < 25) {
                current.append(char)
                if (wordBeforeCorrection != null && char.isLetter()) hasPendingCorrection = true
            }
            else skipWord = true
        }
    }

    fun finishWord() {
        if (!skipWord) dictionaryWord(current.toString())?.let { word ->
            increment(TypingMetric.WORDS)
            if (word in words || words.size < 256) words[word] = (words[word] ?: 0) + 1
        }
        resetWord()
    }

    fun delete() {
        increment(TypingMetric.DELETIONS)
        if (current.isNotEmpty()) current.delete(Character.offsetByCodePoints(current, current.length, -1), current.length)
    }

    fun trackBackspace(before: String) {
        val prefix = if (before.lastOrNull()?.isWhitespace() == true) "" else lastContextWord(before).orEmpty()
        val previous = lastContextWord(before.dropLast(prefix.length))
        if (previous == null) {
            wordBeforeCorrection = null
            hasPendingCorrection = false
            return
        }
        resetWord()
        wordBeforeCorrection = wordKey(previous)
        current.append(prefix)
    }

    fun confirmCorrection(before: String) {
        val previous = wordBeforeCorrection ?: return
        if (!hasPendingCorrection || skipWord) return
        hasPendingCorrection = false
        val replacement = dictionaryWord(current.toString()) ?: return
        if (!before.endsWith(replacement) || lastContextWord(before) != replacement) return
        if (lastContextWord(before.dropLast(replacement.length))?.let(::wordKey) != previous) return
        val pair = "$previous " + wordKey(replacement)
        if (pair in transitions || transitions.size < 64) transitions[pair] = (transitions[pair] ?: 0) + 1
    }

    fun resetWord() {
        current.clear()
        skipWord = false
        wordBeforeCorrection = null
        hasPendingCorrection = false
    }

    fun increment(metric: TypingMetric) {
        counters[metric] = (counters[metric] ?: 0L) + 1
    }

    data class Updates(val words: Map<String, Int>, val counts: Map<TypingMetric, Long>, val transitions: Map<String, Int>)

    fun take(): Updates = Updates(words.toMap(), counters.toMap(), transitions.toMap()).also { clear() }

    fun clear() {
        resetWord()
        words.clear()
        transitions.clear()
        counters.clear()
    }
}
