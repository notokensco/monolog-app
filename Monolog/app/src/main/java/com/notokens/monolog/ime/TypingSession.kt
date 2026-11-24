package com.notokens.monolog.ime

import com.notokens.monolog.data.TypingMetric
import com.notokens.monolog.data.dictionaryWord

/** Keeps eligible words and aggregate counts in memory until the editor session ends. */
internal class TypingSession {
    private val current = StringBuilder()
    private val words = linkedMapOf<String, Int>()
    private val counters = java.util.EnumMap<TypingMetric, Long>(TypingMetric::class.java)
    private var skipWord = false
    val hasUpdates get() = current.isNotEmpty() || counters.isNotEmpty()

    fun type(text: String) {
        increment(TypingMetric.KEYS)
        for (char in text) {
            if (char.isWhitespace() || char in ".,!?;:()") finishWord()
            else if (current.length < 25) current.append(char)
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

    fun resetWord() {
        current.clear()
        skipWord = false
    }

    fun increment(metric: TypingMetric) {
        counters[metric] = (counters[metric] ?: 0L) + 1
    }

    fun take(): Pair<Map<String, Int>, Map<TypingMetric, Long>> =
        (words.toMap() to counters.toMap()).also { clear() }

    fun clear() {
        resetWord()
        words.clear()
        counters.clear()
    }
}
