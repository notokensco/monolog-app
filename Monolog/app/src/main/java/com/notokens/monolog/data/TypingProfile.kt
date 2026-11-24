package com.notokens.monolog.data

import java.util.Locale
import org.json.JSONObject

internal fun dictionaryWord(text: String): String? = text.trim().takeIf {
    it.length in 2..24 && personalWordPattern.matches(it)
}

private val personalWordPattern = Regex("""[\p{L}\p{M}]+(?:['’-][\p{L}\p{M}]+)*""")
internal fun wordKey(text: String) = text.lowercase(Locale.ROOT)

internal enum class TypingMetric(val label: String) {
    KEYS("Keys typed"), WORDS("Words typed"), SWIPES("Words written by swipe"),
    SUGGESTIONS("Suggestions accepted"), CORRECTIONS("Words autocorrected"),
    UNDONE("Autocorrections undone"), DELETIONS("Backspace presses")
}

internal data class LearnedWord(val text: String, val uses: Int, val at: Long)

internal data class TypingProfile(
    val words: Map<String, LearnedWord> = emptyMap(),
    val usage: Map<String, Map<TypingMetric, Long>> = emptyMap()
) {
    fun total(metric: TypingMetric): Long = usage.values.sumOf { it[metric] ?: 0 }

    fun merge(other: TypingProfile): TypingProfile {
        val tokens = (words.keys + other.words.keys).associateWith { key ->
            val local = words[key]
            val remote = other.words[key]
            when {
                local == null -> checkNotNull(remote)
                remote == null -> local
                local.at >= remote.at -> local
                else -> remote
            }
        }
        val counts = (usage.keys + other.usage.keys).associateWith { id ->
            val local = usage[id].orEmpty()
            val remote = other.usage[id].orEmpty()
            TypingMetric.entries.associateWith { maxOf(local[it] ?: 0, remote[it] ?: 0) }
        }
        return TypingProfile(limit(tokens), counts)
    }

    fun encode() = JSONObject().put("words", JSONObject().apply {
        for ((key, word) in words) put(key, JSONObject().put("text", word.text).put("uses", word.uses).put("at", word.at))
    }).put("usage", JSONObject().apply {
        for ((id, counters) in usage) put(id, JSONObject(counters.mapKeys { it.key.name }))
    })

    companion object {
        fun limit(items: Map<String, LearnedWord>): Map<String, LearnedWord> =
            if (items.size <= 1600) items else items.entries.sortedByDescending { it.value.at }.take(1600).associate { it.toPair() }

        fun decode(json: JSONObject): TypingProfile {
            val words = json.optJSONObject("words") ?: JSONObject()
            val usage = json.optJSONObject("usage") ?: JSONObject()
            require(words.length() <= 1600 && usage.length() <= 128)
            return TypingProfile(words.keys().asSequence().mapNotNull { key ->
                val word = words.optJSONObject(key) ?: return@mapNotNull null
                val text = dictionaryWord(word.optString("text")) ?: return@mapNotNull null
                if (wordKey(text) != key) return@mapNotNull null
                key to LearnedWord(text, word.optInt("uses").coerceIn(0, 9999), word.optLong("at"))
            }.toMap(), usage.keys().asSequence().mapNotNull { id ->
                if (runCatching { java.util.UUID.fromString(id) }.isFailure) return@mapNotNull null
                val counters = usage.optJSONObject(id) ?: return@mapNotNull null
                id to TypingMetric.entries.associateWith { counters.optLong(it.name).coerceIn(0L, 1_000_000_000_000L) }
            }.toMap())
        }
    }
}
