package com.notokens.monolog.ai

import android.content.Context

internal data class AiPreview(
    val text: String,
    val generatedLocally: Boolean = false,
    val sources: List<WebSource> = emptyList(),
    val insertable: Boolean = false,
    val detail: String? = null,
    val webKind: WebSearchKind? = null
)

internal object RewritePipeline {
    suspend fun smartTone(context: Context, draft: String, style: RewriteStyle): AiPreview? {
        if (ModelStore.get(context).state.value !is ModelState.Ready) return null
        val prompt = AiText.rewritePrompt(draft, style) + "\nKeep the draft's original language or mix of languages."
        val reply = gemma(context, prompt, AiText.rewriteTokenLimit(draft)) ?: return null
        val checked = AiText.checkedRewrite(draft, reply) ?: return null
        if (!AiText.matchesRewriteStyle(draft, checked, style)) return null
        val text = if (draft.endsWith(' ') && !checked.endsWith(' ')) "$checked " else checked
        return edit(text, generated = true, detail = if (style == RewriteStyle.PROFESSIONAL) "Professional tone" else "Casual tone")
    }

    suspend fun rewrite(context: Context, draft: String, style: RewriteStyle, language: KeyboardLanguage): AiPreview {
        if (style == RewriteStyle.POLISH && !language.isEnglish) return edit(AiText.polishSeparators(draft))
        if (language.isEnglish) return rewriteEnglish(context, draft, style)
        val english = translateToEnglish(context, language, draft)
        val rewritten = rewriteEnglish(context, english, style)
        if (!rewritten.generatedLocally) return edit(AiText.simpleRewrite(draft), detail = rewritten.detail)
        val translated = LanguageResources.fromEnglish(context, language, rewritten.text) ?: throw LanguagePackRequired()
        return AiText.checkedRewrite(draft, translated)?.let { edit(it, generated = true) }
            ?: lightEdit(draft, "The rewrite didn’t preserve the draft.")
    }

    private suspend fun rewriteEnglish(context: Context, draft: String, style: RewriteStyle): AiPreview {
        if (AiText.isBareFragment(draft)) return edit(AiText.simpleRewrite(draft))
        val result = LocalAi.request(context, AiText.rewritePrompt(draft, style), AiText.rewriteTokenLimit(draft))
        if (result is GemmaResult.Answer) {
            val checked = AiText.checkedRewrite(draft, result.text)
                ?: return lightEdit(draft, "The rewrite didn’t preserve the draft.")
            if (!AiText.matchesRewriteStyle(draft, checked, style)) {
                return AiPreview("The offline model couldn’t produce the requested rewrite.")
            }
            return edit(checked, generated = true)
        }
        return lightEdit(draft, modelMessage(result))
    }

    private fun lightEdit(draft: String, reason: String): AiPreview {
        val polished = AiText.simpleRewrite(draft)
        return if (polished == draft) AiPreview(reason) else edit(polished, detail = reason)
    }

    private fun edit(text: String, generated: Boolean = false, detail: String? = null) =
        AiPreview(text, generatedLocally = generated, insertable = true, detail = detail)

    suspend fun respondOffline(context: Context, request: String, inline: Boolean = false): AiPreview {
        if (!AiText.isWritingRequest(request)) return askOffline(context, request,
            selectedModelOnly = true, retryLanguageMismatch = !inline)
        val prompt = "Follow the writing or editing request below. Keep the requested language and tone. " +
            "For edits, preserve the source meaning, names, dates, and numbers. " +
            "Return only the finished draft, without commentary.\n\nRequest: $request"
        return when (val result = LocalAi.request(context, prompt, 160)) {
            is GemmaResult.Answer -> AiText.checkedWriting(request, result.text)?.let { edit(it, generated = true) }
                ?: AiPreview("The offline model did not return a finished draft.")
            else -> AiPreview(modelMessage(result))
        }
    }

    suspend fun askOffline(context: Context, question: String, evidence: String = "", selectedModelOnly: Boolean = false,
        retryLanguageMismatch: Boolean = true): AiPreview {
        if (!selectedModelOnly) AiText.deviceAnswer(question)?.let { return AiPreview(it, insertable = true) }
        if (evidence.isBlank() && AiText.needsWeb(question)) return AiPreview("Use Search web for current information.")
        val instruction = AiText.answerPrompt(question, evidence)
        val first = LocalAi.request(context, instruction, if (retryLanguageMismatch) 160 else 96)
        val result = if (retryLanguageMismatch && first is GemmaResult.Answer &&
            !AiText.usesReplyScript(question, first.text)) {
            LocalAi.request(context,
                "Reply in the original language and script of the question. Never translate it to English.\n$instruction", 128)
        } else first
        return when (result) {
            is GemmaResult.Answer -> verifiedAnswer(question, result.text, evidence)
            else -> AiPreview(modelMessage(result))
        }
    }

    private fun verifiedAnswer(question: String, text: String, evidence: String): AiPreview =
        AiText.checkedAnswer(question, text, evidence)?.let { edit(it, generated = true) }
            ?: AiPreview(AiText.UNKNOWN_ANSWER, detail = "Turn Web on for a sourced answer.")

    private fun modelMessage(result: GemmaResult): String = when (result) {
        GemmaResult.Missing -> "Download a text model in Monolog Settings → On-device AI."
        GemmaResult.TimedOut -> "The offline text model took too long. Try again."
        GemmaResult.Empty -> "The offline text model returned an empty response. Try again."
        is GemmaResult.Failed -> "${result.status()}. Check Settings → On-device AI."
        is GemmaResult.Answer -> error("Expected a model failure")
    }

    suspend fun gemma(context: Context, prompt: String, maxTokens: Int): String? =
        (LocalAi.request(context, prompt, maxTokens) as? GemmaResult.Answer)?.text

    suspend fun translateToEnglish(context: Context, language: KeyboardLanguage, draft: String): String {
        val translated = LanguageResources.toEnglish(context, language, draft)
            ?: throw LanguagePackRequired()
        if (!DraftFacts.read(draft).preserves(translated)) error("Translation changed an exact detail")
        return translated
    }
}

internal class LanguagePackRequired : IllegalStateException()
