package com.notokens.monolog.ai

import android.content.Context

internal data class AiPreview(
    val text: String,
    val generatedLocally: Boolean = false,
    val sources: List<WebSource> = emptyList(),
    val insertable: Boolean = false,
    val detail: String? = null
)

internal object RewritePipeline {
    suspend fun smartTone(context: Context, draft: String, style: RewriteStyle): AiPreview? {
        if (ModelStore.get(context).state.value !is ModelState.Ready) return null
        val prompt = AiText.rewritePrompt(draft, style) + "\nKeep the draft's original language or mix of languages."
        val reply = gemma(context, prompt, AiText.rewriteTokenLimit(draft)) ?: return null
        val checked = AiText.checkedRewrite(draft, reply) ?: return null
        if (checked.trim() == draft.trim()) return null
        val text = if (draft.endsWith(' ') && !checked.endsWith(' ')) "$checked " else checked
        return edit(text, generated = true, detail = if (style == RewriteStyle.PROFESSIONAL) "Professional tone" else "Casual tone")
    }

    suspend fun rewrite(context: Context, draft: String, style: RewriteStyle, language: KeyboardLanguage): AiPreview {
        if (style == RewriteStyle.POLISH && language.id != "en") return edit(AiText.polishSeparators(draft))
        if (language.id == "en") return rewriteEnglish(context, draft, style)
        val english = translateToEnglish(context, language, draft)
        val rewritten = rewriteEnglish(context, english, style)
        if (!rewritten.generatedLocally) return edit(AiText.simpleRewrite(draft), detail = rewritten.detail)
        val translated = LanguageResources.fromEnglish(context, language, rewritten.text) ?: throw LanguagePackRequired()
        return AiText.checkedRewrite(draft, translated)?.let { edit(it, generated = true) }
            ?: lightEdit(draft, "The rewrite didn’t preserve the draft.")
    }

    private suspend fun rewriteEnglish(context: Context, draft: String, style: RewriteStyle): AiPreview {
        if (AiText.isBareFragment(draft)) return edit(AiText.simpleRewrite(draft))
        if (CloudAi.enabled(context) && ModelStore.get(context).active.value?.isGguf != true) {
            return withAiFallback(online = {
                val result = CloudAi.answer(context, AiText.rewritePrompt(draft, style))
                AiText.checkedRewrite(draft, result)?.let { edit(it) }
                    ?: error("The online rewrite did not preserve the draft.")
            }, offline = { rewriteEnglishOffline(context, draft, style) })
        }
        return rewriteEnglishOffline(context, draft, style)
    }

    private suspend fun rewriteEnglishOffline(context: Context, draft: String, style: RewriteStyle): AiPreview {
        val nano = if (ModelStore.get(context).active.value?.isGguf == true) null
            else NanoAi.rewrite(context, draft, style)
        if (nano != null) return AiText.checkedRewrite(draft, nano)?.let { edit(it, generated = true) }
            ?: lightEdit(draft, "The rewrite didn’t preserve the draft.")
        val result = LocalAi.request(context, AiText.rewritePrompt(draft, style), AiText.rewriteTokenLimit(draft))
        if (result is GemmaResult.Answer) {
            return AiText.checkedRewrite(draft, result.text)?.let { edit(it, generated = true) }
                ?: lightEdit(draft, "The rewrite didn’t preserve the draft.")
        }
        return lightEdit(draft, modelMessage(result))
    }

    private fun lightEdit(draft: String, reason: String): AiPreview {
        val polished = AiText.simpleRewrite(draft)
        return if (polished == draft) AiPreview(reason) else edit(polished, detail = reason)
    }

    private fun edit(text: String, generated: Boolean = false, detail: String? = null) =
        AiPreview(text, generatedLocally = generated, insertable = true, detail = detail)

    suspend fun askOffline(context: Context, question: String, evidence: String = ""): AiPreview {
        AiText.deviceAnswer(question)?.let { return AiPreview(it, insertable = true) }
        if (evidence.isBlank() && AiText.needsWeb(question)) return AiPreview("Use Search web for current information.")
        val instruction = AiText.answerPrompt(question, evidence)
        val nano = if (ModelStore.get(context).active.value?.isGguf == true) null
            else NanoAi.answer(context, instruction)
        if (nano != null) return verifiedAnswer(question, nano, evidence)
        return when (val result = LocalAi.request(context, instruction, 160)) {
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
