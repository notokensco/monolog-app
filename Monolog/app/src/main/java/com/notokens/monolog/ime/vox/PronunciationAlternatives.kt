package com.notokens.monolog.ime.vox

import com.notokens.monolog.ime.NativeTyping
import java.util.Locale

/** Conservative transcript cleanup and pronunciation hints for Whisper. */
internal object PronunciationAlternatives {
    private data class Term(val word: String, val soundAlikes: Regex)

    private val vocabulary = listOf(
        Term("Monolog", Regex("\\bmono[ -]log(?:ue)?\\b", RegexOption.IGNORE_CASE)),
        Term("Hinglish", Regex("\\bhing[ -]+lish\\b", RegexOption.IGNORE_CASE)),
        Term("LiteRT", Regex("\\blight[ -]+r[ -]*t\\b|\\blite[ -]+r[ -]*t\\b", RegexOption.IGNORE_CASE))
    )

    val terms = vocabulary.map { it.word }
    fun supports(languageTag: String): Boolean =
        languageTag.substringBefore('-').lowercase(Locale.ROOT) in setOf("en", "hi")

    fun cleanFinal(text: String, languageTag: String): String {
        var result = pauseMarkers.replace(text, " ").trim().replace(Regex("""[ \t\u00A0]+"""), " ")
        if (!supports(languageTag)) return result
        result = filler.replace(result, " ")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .trim().trimStart(',', ';', ':').trimStart()
        if (result.none(Char::isLetterOrDigit)) return ""
        for (term in vocabulary.drop(1)) result = term.soundAlikes.replace(result, term.word)
        val monolog = vocabulary.first()
        val monologKey = NativeTyping.phoneticKey(monolog.word)
        return monologInContext.replace(result) { match ->
            val spokenName = match.groupValues[2]
            val explicitAlias = monolog.soundAlikes.matches(spokenName)
            val phoneticMatch = monologKey != null && NativeTyping.phoneticKey(spokenName) == monologKey
            if (explicitAlias || phoneticMatch) match.groupValues[1] + monolog.word else match.value
        }
    }

    private val pauseMarkers = Regex("""\[(?:pause|silence)\]|\((?:pause|silence)\)""", RegexOption.IGNORE_CASE)
    private val filler = Regex(
        """(?<![\p{L}\p{N}-])(?:u+h+m*|u+m+|o{2,}m+s?|e+r+m+|a+h+m*|a+m{2,}|h+m{2,})(?![\p{L}\p{N}-])(?:[ \t]*(?:,…|…|\.{3,}|,))?""",
        RegexOption.IGNORE_CASE
    )

    private val monologInContext = Regex(
        """\b((?:open|launch|in|the)\s+(?:the\s+)?)([a-z-]+(?:\s+[a-z-]+){0,2}?)(?=\s+(?:app|keyboard)\b)""",
        RegexOption.IGNORE_CASE
    )
}
