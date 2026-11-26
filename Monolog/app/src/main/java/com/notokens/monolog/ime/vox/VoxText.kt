package com.notokens.monolog.ime.vox

import com.notokens.monolog.ime.englishContraction
import java.util.Locale

internal object VoxText {
    private val note = Regex("""^\s*add\s+(?:this\s+to\s+(?:my\s+)?notes|(?:a\s+)?note)\b(?:[\s,.:;!?\-]+that\b)?[\s,.:;!?\-]*""", RegexOption.IGNORE_CASE)
    private val englishWords = Regex("""\b[a-zA-Z]+\b""")
    private val englishQuestion = Regex("""^(?:(?:what|who|whom|whose|where|when|why|how|which)(?:['’]\w+|\s+(?:is|are|was|were|do|does|did|can|could|will|would|should|has|have|many|much|long))\b|(?:is|are|was|were|do|does|did|can|could|will|would|should|have|has)\s+(?:i|you|he|she|it|we|they|there|the|this|that|your|my)\b)""", RegexOption.IGNORE_CASE)
    private val englishExclamation = Regex("""^(?:wow\b|oh no\b|hooray\b|what a\b|congratulations\b|happy birthday\b)""", RegexOption.IGNORE_CASE)
    private val indianQuestion = mapOf(
        "hi" to Regex("""^(?:क्या(?=\s|$)|ky[aa]+\b)|(?:^|\s)(?:क्यों|कहाँ|कहां|कब|कैसे|kyun|kahaan|kahan|kaise|kab)(?=\s|$)""", RegexOption.IGNORE_CASE),
        "bn" to Regex("""^(?:কি|কী)(?=\s|$)|(?:^|\s)(?:কেন|কোথায়|কখন|কীভাবে|কেমন)(?=\s|$)""")
    )
    private val indianExclamation = Regex("""^(?:वाह|अरे वाह|বাহ)(?=\s|$)|^wa+h\b""", RegexOption.IGNORE_CASE)
    private val englishExplicit = mapOf("question mark" to "?", "exclamation (?:point|mark)" to "!", "full stop" to ".")
    private val sentenceEnd = ".!?।॥！？…"

    fun noteBody(text: String): String? = note.find(text)?.let { text.substring(it.range.last + 1).trim() }

    fun isNoteLead(text: String): Boolean {
        val lead = text.trim().lowercase(Locale.ROOT)
        return lead.isNotEmpty() && listOf("add this to notes", "add this to my notes", "add a note", "add note")
            .any { it.startsWith(lead) }
    }

    fun finish(text: String, languageTag: String, punctuation: Boolean = true): String {
        val language = languageTag.substringBefore('-').lowercase(Locale.ROOT)
        var value = text.trim()
        val includesEnglish = language == "en" || languageTag.equals("hi-Latn", ignoreCase = true)
        if (includesEnglish) {
            val original = value
            value = englishWords.replace(original) { match ->
                if (match.value == "i") "I" else englishContraction(match.value, original.substring(0, match.range.first)) ?: match.value
            }
            for ((phrase, mark) in englishExplicit) {
                value = Regex("\\s+$phrase[.!?]?$", RegexOption.IGNORE_CASE).replace(value, mark)
            }
        }
        if (!punctuation || value.none(Char::isLetterOrDigit) || value.last() in sentenceEnd) return value
        return when {
            includesEnglish && englishExclamation.containsMatchIn(value) -> "$value!"
            includesEnglish && englishQuestion.containsMatchIn(value) -> "$value?"
            language in indianQuestion && indianExclamation.containsMatchIn(value) -> "$value!"
            indianQuestion[language]?.containsMatchIn(value) == true -> "$value?"
            else -> value
        }
    }
}
