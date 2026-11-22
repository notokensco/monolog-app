package com.notokens.monolog.memory

import java.util.Locale

internal data class MemoryEvidence(
    val summary: String, val excerpt: String?, val tags: List<String>, val app: String = "",
    val anger: Int? = null, val angerOrigin: String? = null, val mood: String? = null
)

internal object MeaningfulMoment {
    private val plans = Regex("\\b(remind|reminder|remember|bring|take|meet|meeting|appointment|tomorrow|tonight|library|promise|promised|will send|will call|need to|have to|decided|agreed|plan|deadline|discussed|talked about|kal|laana|lana|milna|yaad|vaada)\\b|याद|कल|लाना|मिलना|वादा|निर्णय|போக|நாளை|கொண்டு", RegexOption.IGNORE_CASE)
    private val anger = Regex("\\b(angry|furious|enraged|livid|gussa|gusse)\\b|गुस्सा|क्रोधित|கோபம்", RegexOption.IGNORE_CASE)
    private val notAngry = Regex("\\b(not|never|no longer|isn't|wasn't)\\s+(?:really\\s+)?(?:angry|furious)|gussa nahi|गुस्सा नहीं", RegexOption.IGNORE_CASE)
    private val selfScore = Regex("(?:anger|angry|gussa|गुस्सा)\\s*(?:is|at|level|score|=|:)?\\s*A?(10|[1-9])(?:/10)?|\\bA(10|[1-9])\\b", RegexOption.IGNORE_CASE)

    fun evaluate(text: String, app: String = "", explicit: Boolean = false): MemoryEvidence? {
        val clean = text.replace(Regex("\\s+"), " ").trim().take(600)
        if (clean.isEmpty()) return null
        val self = Regex("(?:\\bi(?:'m| am| was| feel)|\\bmain\\b|\\bmujhe\\b|मैं|मुझे|நான்).{0,35}(?:angry|furious|enraged|livid|gussa|gusse|गुस्सा|क्रोधित|கோபம்)", RegexOption.IGNORE_CASE)
        val angry = self.containsMatchIn(clean) && !notAngry.containsMatchIn(clean)
        val timed = Regex("\\b\\d{1,2}(?::\\d{2})?\\s*[ap]\\.?m\\b|\\bat\\s+\\d{1,2}:\\d{2}\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(clean)
        if (!explicit && !angry && (!(plans.containsMatchIn(clean) || timed) || clean.count { it.isLetter() } < 12)) return null
        val match = if (angry) selfScore.find(clean) else null
        val reported = match?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()
        val inferred = if (angry) when {
            Regex("furious|enraged|livid|क्रोधित", RegexOption.IGNORE_CASE).containsMatchIn(clean) -> 8
            else -> 6
        } else null
        val tags = buildList {
            if (plans.containsMatchIn(clean) || timed) add("plan")
            if (Regex("decided|agreed|निर्णय", RegexOption.IGNORE_CASE).containsMatchIn(clean)) add("decision")
            if (Regex("discussed|talked about|बात", RegexOption.IGNORE_CASE).containsMatchIn(clean)) add("conversation")
            if (angry) add("anger")
            if (explicit) add("saved")
        }
        val important = if (explicit) clean else clean.replace(
            Regex("^(?:hi|hey|hello|um|uh)[,\\s]+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^i\\s+(?:have|will|need to)\\s+", RegexOption.IGNORE_CASE), "")
            .replaceFirstChar { it.titlecase() }
        return MemoryEvidence(important.take(240), null, tags, app.take(160), reported ?: inferred,
            if (reported != null) "Self-reported" else if (angry) "Inferred" else null,
            if (angry) "Anger expressed in this drafted moment." else null)
    }

    fun normalized(text: String) = text.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
