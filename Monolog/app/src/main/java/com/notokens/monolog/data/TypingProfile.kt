package com.notokens.monolog.data

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun dictionaryWord(text: String): String? =
    contextWord(text.trim())?.takeIf { it.length >= 2 }

internal fun contextWord(text: String): String? =
    text.takeIf { it.length in 1..24 && personalWordPattern.matches(it) }

internal fun lastContextWord(before: String): String? =
    contextWord(before.trimEnd(' ', '\t').takeLastWhile { !it.isWhitespace() })

private val personalWordPattern = Regex("""[\p{L}\p{M}]+(?:['’-][\p{L}\p{M}]+)*""")
internal fun wordKey(text: String) = text.lowercase(Locale.ROOT)

internal enum class TypingMetric(val label: String) {
    KEYS("Keys typed"), WORDS("Words typed"), SWIPES("Words written by swipe"),
    SUGGESTIONS("Suggestions accepted"), CORRECTIONS("Words autocorrected"),
    UNDONE("Autocorrections undone"), DELETIONS("Backspace presses")
}

internal data class LearnedWord(val text: String, val uses: Int, val at: Long)
internal data class LearnedTransition(val uses: Int, val at: Long)

internal data class TypingProfile(
    val words: Map<String, LearnedWord> = emptyMap(),
    val usage: Map<String, Map<TypingMetric, Long>> = emptyMap(),
    val transitions: Map<String, LearnedTransition> = emptyMap()
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
        val pairs = (transitions.keys + other.transitions.keys).mapNotNull { key ->
            if (tokens[key.substringBefore(' ')]?.uses == 0 || tokens[key.substringAfter(' ')]?.uses == 0) {
                return@mapNotNull null
            }
            val newer = listOfNotNull(transitions[key], other.transitions[key])
                .maxWithOrNull(compareBy<LearnedTransition> { it.at }.thenBy { it.uses })
            newer?.let { key to it }
        }.toMap()
        return TypingProfile(limit(tokens), counts, limitTransitions(pairs))
    }

    fun encode() = JSONObject().put("words", JSONObject().apply {
        for ((key, word) in words) put(key, JSONObject().put("text", word.text).put("uses", word.uses).put("at", word.at))
    }).put("usage", JSONObject().apply {
        for ((id, counters) in usage) put(id, JSONObject(counters.mapKeys { it.key.name }))
    }).put("transitions", JSONObject().apply {
        for ((pair, transition) in transitions) put(pair, JSONArray().put(transition.uses).put(transition.at))
    })

    companion object {
        private const val MAX_TRANSITIONS = 256

        fun limitTransitions(items: Map<String, LearnedTransition>): Map<String, LearnedTransition> =
            if (items.size <= MAX_TRANSITIONS) items else items.entries
                .sortedWith(compareByDescending<Map.Entry<String, LearnedTransition>> { it.value.at }
                    .thenByDescending { it.value.uses }).take(MAX_TRANSITIONS).associate { it.toPair() }

        fun limit(items: Map<String, LearnedWord>): Map<String, LearnedWord> =
            if (items.size <= 1600) items else items.entries.sortedByDescending { it.value.at }.take(1600).associate { it.toPair() }

        fun decode(json: JSONObject): TypingProfile {
            val words = json.optJSONObject("words") ?: JSONObject()
            val usage = json.optJSONObject("usage") ?: JSONObject()
            val pairs = json.optJSONObject("transitions") ?: JSONObject()
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
            }.toMap(), pairs.keys().asSequence().take(MAX_TRANSITIONS).mapNotNull { key ->
                val previous = contextWord(key.substringBefore(' ')) ?: return@mapNotNull null
                val next = dictionaryWord(key.substringAfter(' ', "")) ?: return@mapNotNull null
                if (key != wordKey(previous) + " " + wordKey(next)) return@mapNotNull null
                val entry = pairs.optJSONArray(key) ?: return@mapNotNull null
                val uses = entry.optInt(0)
                val at = entry.optLong(1)
                if (uses !in 1..255 || at <= 0) return@mapNotNull null
                key to LearnedTransition(uses, at)
            }.toMap())
        }
    }
}
