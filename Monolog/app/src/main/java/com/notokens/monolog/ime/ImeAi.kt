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
import com.notokens.monolog.ime.shortcuts.ShortcutSuggestion
import com.notokens.monolog.ime.shortcuts.ShortcutSuggestions
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
    private val canUse: () -> Boolean,
    private val proactiveAllowed: () -> Boolean = { true },
    private val automaticNetworkAllowed: () -> Boolean = { true },
    private val personalizationAllowed: () -> Boolean = { true },
    private val checkDraftPrivacy: (String) -> Unit = {},
    private val phoneActionsEnabled: () -> Boolean = { true },
    private val activePackage: () -> String = { "" },
    private val onTextApplied: (String?, String) -> Unit = { _, _ -> }
) {
    private data class Draft(val text: String, val selected: Boolean, val afterCursor: Int = 0)
    var hasDraft by mutableStateOf(false)
        private set
    private var wordSuggestions by mutableStateOf(emptyList<WordSuggestion>())
    private var swipeAlternatives by mutableStateOf(emptyList<WordSuggestion>())
    val suggestions get() = swipeAlternatives.ifEmpty { wordSuggestions }
    var shortcutSuggestion by mutableStateOf<ShortcutSuggestion?>(null)
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
    val webAllowed get() = automaticNetworkAllowed()
    val journalAllowed get() = personalizationAllowed()
    var web by mutableStateOf(false)
        private set
    private var pendingDraft: Draft? = null
    private var toolbarDraft: Draft? = null
    private var action: Job? = null
    private var actionVersion = 0L
    private var lookup: Job? = null
    var inlineBusy by mutableStateOf(false)
        private set
    var inlineActions by mutableStateOf(emptyList<DeviceAction>())
        private set
    var askActions by mutableStateOf(emptyList<DeviceAction>())
        private set

    private fun cancelLookup() {
        lookup?.cancel()
        lookup = null
        inlineBusy = false
    }

    fun onPhoneActionsChanged() {
        if (!phoneActionsEnabled()) {
            inlineActions = emptyList()
            askActions = emptyList()
        } else refreshDraft()
    }
    private var warmup: Job? = null
    private var expectedCursor: Int? = null
    private var cursor = -1

    fun reset(initialCursor: Int = -1) {
        closePanel()
        clearDraft()
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
        wordSuggestions = emptyList()
        swipeAlternatives = emptyList()
        shortcutSuggestion = null
        inlineActions = emptyList()
        cancelLookup()
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
        cancelLookup()
        if (!canUse()) {
            clearDraft()
            return
        }
        if (cursor >= 0) expectedCursor = (expectedCursor ?: cursor) + value.length
        if (value.isNotBlank()) hasDraft = true
        toolbar = null
        toolbarDraft = null
        swipeAlternatives = emptyList()
        inlineActions = emptyList()
        refreshDraft(typing = true)
    }

    fun refreshDraft(typing: Boolean = false) {
        suggestionJob?.cancel()
        suggestionJob = scope.launch {
            if (typing) delay(42)
            if (!canUse() || askOpen) return@launch
            val ic = connection() ?: return@launch
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
            val before = ic.getTextBeforeCursor(400, 0)?.toString().orEmpty()
            val after = if (selected.isEmpty()) ic.getTextAfterCursor(400, 0)?.toString().orEmpty() else ""
            val visibleText = selected.ifBlank { before + after }
            checkDraftPrivacy(visibleText)
            hasDraft = visibleText.isNotBlank()
            inlineActions = if (phoneActionsEnabled() && personalizationAllowed()) {
                DeviceActions.detect(selected.ifBlank { before }, automaticNetworkAllowed())
            } else emptyList()
            shortcutSuggestion = ShortcutSuggestions.detect(selected.ifBlank { before })?.let { suggestion ->
                if (personalizationAllowed()) suggestion
                else suggestion.takeUnless { it.page == com.notokens.monolog.ime.shortcuts.ShortcutPage.NOTE }
                    ?.copy(title = "", body = "")
            }
            if (typing && selected.isEmpty()) suggest(before.substringAfterLast('\n'), replaceTypedDraft = true)
            val language = settings.keyboardLanguage.first()
            wordSuggestions = if (selected.isEmpty()) {
                dictionary.suggest(before, language, personalizationAllowed())
            } else emptyList()
            if (typing && selected.isEmpty() && personalizationAllowed() && settings.systemSpellChecker.first()) {
                delay(220)
                if (!personalizationAllowed() || connection() !== ic) return@launch
                val tag = if (language == "hi-Latn") "en-IN" else KeyboardLanguage.of(language).speechTag
                val alternatives = SystemSpellChecker.suggest(context, before, java.util.Locale.forLanguageTag(tag))
                if (alternatives.isNotEmpty() && personalizationAllowed() && connection() === ic &&
                    ic.getTextBeforeCursor(alternatives.first().typed.length, 0)?.toString() == alternatives.first().typed) {
                    // Keep Monolog's lead suggestion and expose one additional OS correction.
                    wordSuggestions = (wordSuggestions.take(1) + alternatives + wordSuggestions.drop(1))
                        .distinctBy { it.replacement.lowercase() }.take(3)
                }
            }
        }
    }

    fun offerSwipeAlternatives(typed: String, alternatives: List<String>) {
        if (!canUse() || alternatives.isEmpty()) return
        swipeAlternatives = alternatives.map { WordSuggestion(typed, "$it ") }
    }

    fun applyWord(suggestion: WordSuggestion) {
        if (!canUse() || suggestion !in suggestions) return
        val ic = connection() ?: return
        val target = Draft(suggestion.typed, false)
        if (target.text.isNotEmpty() && (!matches(ic, target) || !selectDraft(ic, target))) return
        if (target.text.isEmpty() && (!ic.getSelectedText(0).isNullOrEmpty() ||
                ic.getTextBeforeCursor(1, 0)?.toString() != " ")) return
        if (ic.commitText(suggestion.replacement, 1)) {
            onTextApplied(suggestion.typed, suggestion.replacement)
            clearDraft()
            refreshDraft(typing = true)
        }
    }

    fun stableSpeech(transcript: String) {
        if (canUse()) suggest(transcript)
    }

    fun disableWebSuggestions() {
        cancelLookup()
        web = false
        toolbar = null
        toolbarDraft = null
    }

    private fun suggest(text: String, replaceTypedDraft: Boolean = false) {
        cancelLookup()
        toolbar = null
        toolbarDraft = null
        // Journal queries in the companion must remain on its explicit offline path.
        if (!proactiveAllowed()) return
        val question = AiText.proactiveQuestion(text)
        if (question == null) {
            if (replaceTypedDraft) {
                AiText.inlinePolish(text)?.let {
                    toolbar = AiPreview(it, insertable = true)
                    toolbarDraft = Draft(text, false)
                }
                val packageName = activePackage()
                val style = com.notokens.monolog.ai.SmartTones.style(packageName)
                if (style != null && text.length in 20..400 && text.count(Char::isWhitespace) >= 3 &&
                    !AiText.isBareFragment(text)) {
                    lookup = scope.launch {
                        delay(1_100)
                        if (!settings.smartTone.first() || !canUse() ||
                            activePackage() != packageName || busy || rewriteOpen || askOpen) return@launch
                        val target = Draft(text, false)
                        val editor = connection() ?: return@launch
                        if (!matches(editor, target)) return@launch
                        try {
                            val result = RewritePipeline.smartTone(context, text, style) ?: return@launch
                            if (!canUse() || connection() !== editor ||
                                activePackage() != packageName || !matches(editor, target)) return@launch
                            toolbar = result
                            toolbarDraft = target
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Retain the ordinary keyboard suggestions when the local model is unavailable.
                        }
                    }
                }
            }
            return
        }
        lookup = scope.launch {
            delay(650)
            if (!canUse() || busy || rewriteOpen || askOpen) return@launch
            inlineBusy = true
            try {
                val answer = RewritePipeline.askOffline(context, question, selectedModelOnly = true)
                if (!canUse() || busy || rewriteOpen || askOpen) return@launch
                if (replaceTypedDraft && connection()?.getTextBeforeCursor(text.length, 0)?.toString() != text) return@launch
                if (!answer.insertable) return@launch
                toolbar = answer
                if (replaceTypedDraft) toolbarDraft = Draft(text, false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Leave word suggestions available when inference fails.
            } finally {
                if (lookup == kotlin.coroutines.coroutineContext[Job]) inlineBusy = false
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
        cancelLookup()
        web = automaticNetworkAllowed() && !web
    }

    fun abortAction() {
        actionVersion++
        action?.cancel()
        action = null
        preview = null
        askActions = emptyList()
        busy = false
        pendingDraft = null
    }

    fun searchWeb(query: String) {
        checkDraftPrivacy(query)
        if (!canUse() || !automaticNetworkAllowed() || query.isBlank()) return
        abortAction()
        if (!WebLookup.openSearchApp(context, query)) {
            preview = AiPreview("No web search app is available.")
        }
    }

    fun ask(query: String) {
        if (!canUse() || query.isBlank()) return
        checkDraftPrivacy(query)
        if (phoneActionsEnabled() && personalizationAllowed()) {
            val choices = DeviceActions.detect(query, automaticNetworkAllowed(), explicit = true)
            if (choices.isNotEmpty()) {
                abortAction()
                askActions = choices
                return
            }
        }
        val useWeb = web && automaticNetworkAllowed()
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
                offlineAnswer(query)
            }
        }
    }

    private suspend fun offlineAnswer(query: String): AiPreview {
        val evidence = if (personalizationAllowed() && query.asksAboutJournalCapture()) {
            MonologData.repository(context).notes.first().captureEvidence(query)
        } else ""
        return RewritePipeline.askOffline(context, query, evidence)
    }

    fun openRewrite(language: KeyboardLanguage) {
        if (!canUse() || !hasDraft) return
        cancelLookup()
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
        cancelLookup()
        abortAction()
        val version = actionVersion
        busy = true
        action = scope.launch {
            try {
                if (!canUse()) return@launch
                val result = work()
                val ic = connection()
                if (version != actionVersion || !canUse() ||
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
        onTextApplied(target?.text, card.text)
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
