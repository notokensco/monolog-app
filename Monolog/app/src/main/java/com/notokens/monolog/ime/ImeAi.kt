package com.notokens.monolog.ime

import android.content.Context
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notokens.monolog.ai.AiPreview
import com.notokens.monolog.ai.AiText
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.LanguagePackRequired
import com.notokens.monolog.ai.LocalAi
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.ai.RewritePipeline
import com.notokens.monolog.ai.RewriteStyle
import com.notokens.monolog.ai.WebAnswer
import com.notokens.monolog.ai.WebLookup
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.asksAboutJournalCapture
import com.notokens.monolog.data.captureEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class ImeAi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val dictionary: EnglishSuggestions,
    private val connection: () -> InputConnection?,
    private val canUse: () -> Boolean
) {
    private data class Draft(val text: String, val selected: Boolean, val afterCursor: Int = 0)
    var hasDraft by mutableStateOf(false)
        private set
    var suggestions by mutableStateOf(emptyList<WordSuggestion>())
        private set
    private var suggestionJob: Job? = null
    private var askOpen = false
    var preview by mutableStateOf<AiPreview?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var toolbar by mutableStateOf<AiPreview?>(null)
        private set
    var rewriteOpen by mutableStateOf(false)
        private set
    var web by mutableStateOf(false)
        private set
    private var pendingDraft: Draft? = null
    private var toolbarDraft: Draft? = null
    private var action: Job? = null
    private var actionVersion = 0L
    private var lookup: Job? = null
    private var warmup: Job? = null
    private var expectedCursor: Int? = null
    private var cursor = -1
    private var lastLookup: Pair<String, AiPreview>? = null

    fun reset(initialCursor: Int = -1) {
        closePanel()
        clearDraft()
        lastLookup = null
        web = false
        cursor = initialCursor
    }

    fun closePanel() {
        abortAction()
        warmup?.cancel()
        rewriteOpen = false
        askOpen = false
    }

    fun beginSearch() {
        closePanel()
        clearDraft()
        askOpen = true
    }

    fun clearDraft() {
        expectedCursor = null
        suggestionJob?.cancel()
        hasDraft = false
        suggestions = emptyList()
        lookup?.cancel()
        toolbar = null
        toolbarDraft = null
    }

    fun selectionChanged(start: Int, end: Int) {
        val tracked = expectedCursor
        if (start == end && tracked != null && end in (cursor + 1)..tracked) {
            cursor = end
            if (end == tracked) expectedCursor = null
            return
        }
        if (start == end && end == cursor) return
        clearDraft()
        if (rewriteOpen) closePanel() else abortAction()
        cursor = end
        refreshDraft()
    }

    fun textCommitted(value: String) {
        closePanel()
        lookup?.cancel()
        if (!canUse()) {
            clearDraft()
            return
        }
        if (cursor >= 0) expectedCursor = (expectedCursor ?: cursor) + value.length
        if (value.isNotBlank()) hasDraft = true
        toolbar = null
        toolbarDraft = null
        suggestions = emptyList()
        refreshDraft(typing = true)
    }

    fun refreshDraft(typing: Boolean = false) {
        suggestionJob?.cancel()
        suggestionJob = scope.launch {
            if (typing) delay(70)
            if (!canUse() || askOpen) return@launch
            val ic = connection() ?: return@launch
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
            val before = ic.getTextBeforeCursor(400, 0)?.toString().orEmpty()
            hasDraft = selected.isNotBlank() || before.isNotBlank() ||
                !ic.getTextAfterCursor(400, 0).isNullOrBlank()
            if (typing && selected.isEmpty()) suggest(before.substringAfterLast('\n'), replaceTypedDraft = true)
            val language = settings.keyboardLanguage.first()
            suggestions = if (selected.isEmpty() && (language == "en" || language == "hi-Latn")) {
                dictionary.suggest(before)
            } else emptyList()
        }
    }

    fun applyWord(suggestion: WordSuggestion) {
        if (!canUse() || suggestion !in suggestions) return
        val ic = connection() ?: return
        val target = Draft(suggestion.typed, false)
        if (target.text.isNotEmpty() && (!matches(ic, target) || !selectDraft(ic, target))) return
        if (target.text.isEmpty() && (!ic.getSelectedText(0).isNullOrEmpty() ||
                ic.getTextBeforeCursor(1, 0)?.toString() != " ")) return
        if (ic.commitText(suggestion.replacement, 1)) {
            clearDraft()
            refreshDraft(typing = true)
        }
    }

    fun stableSpeech(transcript: String) {
        if (canUse()) suggest(transcript)
    }

    fun disableWebSuggestions() {
        lookup?.cancel()
        toolbar = null
        toolbarDraft = null
        lastLookup = null
    }

    private fun suggest(text: String, replaceTypedDraft: Boolean = false) {
        lookup?.cancel()
        toolbar = null
        toolbarDraft = null
        AiText.deviceAnswer(text)?.let {
            toolbar = AiPreview(it, insertable = true)
            if (replaceTypedDraft) toolbarDraft = Draft(text, false)
            return
        }
        val forecast = AiText.weatherLookup(text)
        val question = AiText.proactiveQuestion(text)
        if (forecast == null && question == null) {
            if (replaceTypedDraft) AiText.inlinePolish(text)?.let {
                toolbar = AiPreview(it, insertable = true)
                toolbarDraft = Draft(text, false)
            }
            return
        }
        lookup = scope.launch {
            delay(650)
            if (!canUse() || settings.incognito.first() || busy || rewriteOpen || askOpen) return@launch
            try {
                val answer = if (forecast != null) {
                    val city = settings.weatherCity.first()
                    if (!WebLookup.configured || !web || !settings.webSuggestions.first() || city.isBlank()) {
                        AiPreview(if (!WebLookup.configured) "Live forecasts need a configured weather service."
                            else "Turn Web on and set a city under Privacy & data for live forecasts.")
                    } else {
                        val key = "$city|${forecast.date}"
                        lastLookup?.takeIf { it.first == key }?.second ?: WebLookup.weather(forecast, city).let {
                            webPreview(question ?: "What is the forecast for $city?", it).also { card -> lastLookup = key to card }
                        }
                    }
                } else if (question != null && web && settings.webQuestionSuggestions.first()) {
                    webPreview(question, WebLookup.ask(question))
                } else if (question != null) {
                    RewritePipeline.askOffline(context, question)
                } else return@launch
                if (!canUse() || settings.incognito.first() || busy || rewriteOpen || askOpen) return@launch
                // A late result must belong to the same draft that launched the request.
                if (replaceTypedDraft && connection()?.getTextBeforeCursor(text.length, 0)?.toString() != text) return@launch
                if (answer.insertable || forecast != null) toolbar = answer
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (forecast != null && canUse()) toolbar = AiPreview("Forecast unavailable.")
            }
        }
    }

    private suspend fun webPreview(question: String, result: WebAnswer): AiPreview {
        val prompt = AiText.answerPrompt(question, result.text)
        val summary = RewritePipeline.gemma(context, prompt, 96)?.let { AiText.checkedAnswer(question, it, result.text) }
        return AiPreview(summary ?: result.text, generatedLocally = summary != null,
            sources = result.sources, insertable = true,
            detail = if (!WebLookup.configured) "Wikipedia · CC BY-SA" else null)
    }

    fun toggleWeb() {
        abortAction()
        lookup?.cancel()
        web = !web
    }

    fun abortAction() {
        actionVersion++
        action?.cancel()
        action = null
        preview = null
        busy = false
        pendingDraft = null
    }

    fun searchWeb(query: String) {
        if (!canUse() || query.isBlank()) return
        abortAction()
        if (!WebLookup.openSearchApp(context, query)) {
            preview = AiPreview("No web search app is available.")
        }
    }

    fun ask(query: String) {
        if (!canUse() || query.isBlank()) return
        val useWeb = web
        launchRequest {
            if (useWeb) {
                try {
                    webPreview(query, WebLookup.ask(query))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    AiPreview("No sourced answer. Try Open web search or turn Web off.")
                }
            } else {
                val evidence = if (query.asksAboutJournalCapture()) {
                    MonologData.repository(context).notes.first().captureEvidence(query)
                } else ""
                RewritePipeline.askOffline(context, query, evidence)
            }
        }
    }

    fun openRewrite(language: KeyboardLanguage) {
        if (!canUse() || !hasDraft) return
        lookup?.cancel()
        closePanel()
        rewriteOpen = true
        if (language.id == "en" && ModelStore.get(context).state.value is ModelState.Ready) {
            warmup = scope.launch {
                try {
                    LocalAi.warm(context)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The explicit request reports model availability.
                } catch (_: LinkageError) {
                    // The explicit request reports native startup failures.
                }
            }
        }
    }

    fun rewrite(style: RewriteStyle, language: KeyboardLanguage, translate: Boolean = false) {
        if (!canUse()) return
        val ic = connection() ?: return
        val selected = ic.getSelectedText(0)?.toString()
        val input = if (!selected.isNullOrBlank()) Draft(selected, true) else {
            val before = ic.getTextBeforeCursor(401, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(401, 0)?.toString().orEmpty()
            Draft(before + after, false, after.length)
        }
        if (input.text.isBlank() || input.text.length > 400 || !matches(ic, input)) {
            abortAction()
            preview = AiPreview("Select up to 400 characters or type a short draft in this field first.")
            return
        }
        if (!translate && language.id != "en" && language.translationCode == null && style != RewriteStyle.POLISH) {
            abortAction()
            preview = AiPreview("This language supports local polish. Choose English for tone rewrites.")
            return
        }
        launchRequest(input) {
            val result = if (translate) {
                AiPreview(RewritePipeline.translateToEnglish(context, language, input.text),
                    generatedLocally = true, insertable = true)
            } else RewritePipeline.rewrite(context, input.text, style, language)
            if (result.insertable && input.text.endsWith(' ') && !result.text.endsWith(' ')) {
                result.copy(text = result.text + " ")
            } else result
        }
    }

    private fun launchRequest(draft: Draft? = null, work: suspend () -> AiPreview) {
        lookup?.cancel()
        abortAction()
        val version = actionVersion
        busy = true
        action = scope.launch {
            try {
                if (!canUse() || settings.incognito.first()) return@launch
                val result = work()
                val ic = connection()
                if (version != actionVersion || !canUse() || settings.incognito.first() ||
                    draft != null && (ic == null || !matches(ic, draft))) {
                    return@launch
                }
                pendingDraft = draft
                preview = result
            } catch (error: CancellationException) {
                throw error
            } catch (_: LanguagePackRequired) {
                if (version == actionVersion && canUse()) {
                    preview = AiPreview("Download this language’s translation pack in Monolog Settings.")
                }
            } catch (_: Exception) {
                if (version == actionVersion && canUse()) {
                    preview = AiPreview("No result available. Check the model or connection and try again.")
                }
            } finally {
                if (version == actionVersion) busy = false
            }
        }
    }

    fun apply(card: AiPreview) {
        if (!canUse() || !card.insertable || card != preview && card != toolbar) return
        val ic = connection() ?: return
        val target = if (card == preview) pendingDraft else toolbarDraft
        if (target != null && !matches(ic, target)) {
            closePanel()
            return
        }
        if (target?.selected == false && !selectDraft(ic, target)) return
        if (!ic.commitText(card.text, 1)) return
        if (askOpen) {
            abortAction()
            return
        }
        closePanel()
        clearDraft()
        refreshDraft()
    }

    private fun selectDraft(ic: InputConnection, draft: Draft): Boolean {
        val beforeLength = draft.text.length - draft.afterCursor
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val position = extracted?.takeIf { it.selectionStart == it.selectionEnd && it.selectionStart >= 0 }
            ?.let { it.startOffset + it.selectionStart } ?: expectedCursor ?: cursor
        return position >= beforeLength && ic.setSelection(position - beforeLength, position + draft.afterCursor)
    }

    private fun matches(ic: InputConnection, draft: Draft): Boolean =
        if (draft.selected) ic.getSelectedText(0)?.toString() == draft.text
        else if (draft.text.isEmpty() || !ic.getSelectedText(0).isNullOrEmpty()) false
        else {
            val beforeLength = draft.text.length - draft.afterCursor
            val before = if (beforeLength == 0) "" else ic.getTextBeforeCursor(beforeLength, 0)?.toString()
            val after = if (draft.afterCursor == 0) "" else ic.getTextAfterCursor(draft.afterCursor, 0)?.toString()
            before != null && after != null && before + after == draft.text
        }
}
