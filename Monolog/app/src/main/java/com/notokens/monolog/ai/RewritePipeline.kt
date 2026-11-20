package com.notokens.monolog.ai

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull

internal data class AiPreview(
    val text: String,
    val generatedLocally: Boolean = false,
    val sources: List<WebSource> = emptyList(),
    val insertable: Boolean = false
)

internal object RewritePipeline {
    suspend fun rewrite(
        context: Context,
        draft: String,
        style: RewriteStyle,
        language: KeyboardLanguage
    ): String {
        if (style == RewriteStyle.POLISH && language.id != "en") return AiText.polishSeparators(draft)
        if (language.id != "en") {
            val english = translateToEnglish(context, language, draft)
            val rewritten = rewriteEnglish(context, english, style)
            val translated = LanguageResources.fromEnglish(context, language, rewritten) ?: throw LanguagePackRequired()
            check(translated.isNotBlank() && DraftFacts.read(draft).preserves(translated))
            return translated
        }
        return rewriteEnglish(context, draft, style)
    }

    private suspend fun rewriteEnglish(context: Context, draft: String, style: RewriteStyle): String {
        val facts = DraftFacts.read(draft)
        val output = NanoAi.rewrite(context, draft, style)?.takeIf(facts::preserves)
            ?: if (ModelStore.get(context).state.value !is ModelState.Ready) {
                throw LocalModelRequired()
            } else {
                withTimeoutOrNull(16_000L) {
                    LocalAi.generate(context, AiText.rewritePrompt(draft, style), 112)
                }.orEmpty()
            }
        check(output.isNotBlank())
        check(facts.preserves(output))
        return output
    }

    suspend fun askOffline(context: Context, question: String): AiPreview {
        AiText.deviceAnswer(question)?.let { return AiPreview(it, insertable = true) }
        if (AiText.needsWeb(question)) return AiPreview("Turn on Web for current information.")
        val instruction = "Answer briefly. If unsure, say so. You are offline.\nQuestion: " + question
        val answer = NanoAi.answer(context, instruction) ?: run {
            if (ModelStore.get(context).state.value !is ModelState.Ready) throw LocalModelRequired()
            withTimeoutOrNull(16_000L) { LocalAi.generate(context, instruction, 144) }
        }
        return if (answer.isNullOrBlank()) AiPreview("The on-device answer timed out.")
        else AiPreview(answer, generatedLocally = true, insertable = true)
    }

    suspend fun translateToEnglish(context: Context, language: KeyboardLanguage, draft: String): String {
        val translated = LanguageResources.toEnglish(context, language, draft)
            ?: throw LanguagePackRequired()
        if (!DraftFacts.read(draft).preserves(translated)) error("Translation changed an exact detail")
        return translated
    }
}

internal class LocalModelRequired : IllegalStateException()
internal class LanguagePackRequired : IllegalStateException()
