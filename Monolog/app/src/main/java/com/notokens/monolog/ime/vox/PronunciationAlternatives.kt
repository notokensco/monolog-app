package com.notokens.monolog.ime.vox

import com.notokens.monolog.ime.NativeTyping
import java.util.Locale

/** Conservative cleanup of recognized speech. */
internal object PronunciationAlternatives {
    private data class Term(val word: String, val soundAlikes: Regex)

    private val vocabulary = listOf(
        Term("Monolog", Regex("\\bmono[ -]log(?:ue)?\\b", RegexOption.IGNORE_CASE)),
        Term("Hinglish", Regex("\\bhing[ -]+lish\\b", RegexOption.IGNORE_CASE)),
        Term("LiteRT", Regex("\\blight[ -]+r[ -]*t\\b|\\blite[ -]+r[ -]*t\\b", RegexOption.IGNORE_CASE))
    )

    private fun supports(languageTag: String): Boolean =
        languageTag.substringBefore('-').lowercase(Locale.ROOT) in setOf("en", "hi")

    private val fillerPhonemes = Regex(
        """(?<![\p{L}\p{M}\p{N}-])(?:u+m+s?|u+h+(?:m+s?|s)?|a{3,}|e+rm+|h+m{2,}|उ+ह+|आ{2,}|उम{2,}|हम्म+)(?![\p{L}\p{M}\p{N}-])(?:[ \t]*(?:,…|…|\.{3,}|,))?""",
        RegexOption.IGNORE_CASE
    )
    private val disfluencyLabel = Regex("""[\[(<]\s*(?:uh|um|hmm|filler|hesitation|pause|silence)\s*[\])>]""",
        RegexOption.IGNORE_CASE)

    fun cleanFillerPhonemes(text: String): String {
        if (text.isBlank()) return ""
        return fillerPhonemes.replace(disfluencyLabel.replace(text, " "), " ")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .trim()
            .trimStart(',', ';', ':')
            .trim()
    }

    fun cleanFinal(text: String, languageTag: String, smartFilter: Boolean = true): String {
        var result = text.trim().replace(Regex("""[ \t\u00A0]+"""), " ")
        if (smartFilter) result = cleanFillerPhonemes(result)
        if (!supports(languageTag)) return result
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

    private val monologInContext = Regex(
        """\b((?:open|launch|in|the)\s+(?:the\s+)?)([a-z-]+(?:\s+[a-z-]+){0,2}?)(?=\s+(?:app|keyboard)\b)""",
        RegexOption.IGNORE_CASE
    )
}
