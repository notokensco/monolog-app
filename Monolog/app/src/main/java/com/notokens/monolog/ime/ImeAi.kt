package com.notokens.monolog.ime

import android.content.Context
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notokens.monolog.ai.AiPreview
import com.notokens.monolog.ai.AiText
import com.notokens.monolog.ai.KeyboardLanguage
import com.notokens.monolog.ai.LanguagePackRequired
import com.notokens.monolog.ai.LocalAi
import com.notokens.monolog.ai.LocalModelRequired
import com.notokens.monolog.ai.NanoAi
import com.notokens.monolog.ai.ModelState
import com.notokens.monolog.ai.ModelStore
import com.notokens.monolog.ai.RewritePipeline
import com.notokens.monolog.ai.RewriteStyle
import com.notokens.monolog.ai.WebLookup
import com.notokens.monolog.data.SettingsStore
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
    private val connection: () -> InputConnection?,
    private val canUse: () -> Boolean
) {
    private data class Draft(val text: String, val selected: Boolean)
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
    private var action: Job? = null
    private var actionVersion = 0L
    private var lookup: Job? = null
    private var warmup: Job? = null
    private var typed = ""
    private var expectedCursor: Int? = null
    private var cursor = -1
    private var lastLookup: Pair<String, AiPreview>? = null

    fun reset(initialCursor: Int = -1) {
        closePanel()
        clearDraft()
        lastLookup = null
        cursor = initialCursor
    }

    fun closePanel() {
        abortAction()
        warmup?.cancel()
        rewriteOpen = false
        web = false
    }

    fun clearDraft() {
        typed = ""
        expectedCursor = null
        lookup?.cancel()
        toolbar = null
    }

    fun selectionChanged(start: Int, end: Int) {
        val tracked = expectedCursor
        if (start == end && tracked != null && end > cursor && end < tracked) {
            cursor = end
            return
        }
        if (start != end || (tracked != null && tracked != end) || (tracked == null && cursor != end)) clearDraft()
        if (cursor != end || start != end) {
            if (rewriteOpen) closePanel() else abortAction()
        }
        cursor = end
    }

    fun textCommitted(value: String) {
        closePanel()
        if (!canUse()) {
            clearDraft()
            return
        }
        if (cursor < 0) return
        expectedCursor = (expectedCursor ?: cursor) + value.length
        typed = (typed + value).takeLast(400)
        suggest(typed)
    }

    fun stableSpeech(transcript: String) {
        if (canUse()) suggest(transcript)
    }

    fun disableWebSuggestions() {
        lookup?.cancel()
        toolbar = null
        lastLookup = null
    }

    private fun suggest(text: String) {
        lookup?.cancel()
        toolbar = null
        AiText.deviceAnswer(text)?.let {
            toolbar = AiPreview(it, insertable = true)
            return
        }
        val weather = AiText.weatherLookup(text) ?: return
        if (!WebLookup.configured) return
        lookup = scope.launch {
            delay(425)
            if (!canUse() || !settings.webSuggestions.first() || settings.incognito.first()) return@launch
            val city = settings.weatherCity.first()
            if (city.isBlank()) return@launch
            val key = city + "|" + weather.date
            lastLookup?.takeIf { it.first == key }?.let {
                toolbar = it.second
                return@launch
            }
            try {
                val result = WebLookup.weather(weather, city)
                if (!canUse() || !settings.webSuggestions.first() || settings.incognito.first()) return@launch
                val card = AiPreview(result.text, sources = result.sources, insertable = true)
                lastLookup = key to card
                toolbar = card
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (canUse()) toolbar = AiPreview("Forecast unavailable.")
            }
        }
    }

    fun toggleWeb() {
        abortAction()
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

    fun ask(query: String) {
        if (!canUse() || query.isBlank()) return
        val useWeb = web
        launchRequest {
            if (useWeb) {
                val answer = WebLookup.ask(query)
                AiPreview(answer.text, sources = answer.sources, insertable = true)
            } else {
                RewritePipeline.askOffline(context, query)
            }
        }
    }

    fun probeNano() {
        if (canUse()) launchRequest { AiPreview("Keyboard Nano · " + NanoAi.probe(context)) }
    }

    fun openRewrite(language: KeyboardLanguage) {
        if (!canUse()) return
        closePanel()
        rewriteOpen = true
        if (language.id == "en" && ModelStore.get(context).state.value is ModelState.Ready) {
            warmup = scope.launch {
                try {
                    LocalAi.warm(context)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (rewriteOpen && !busy) preview = AiPreview("Offline writing could not start.")
                }
            }
        }
    }

    fun rewrite(style: RewriteStyle, language: KeyboardLanguage, translate: Boolean = false) {
        if (!canUse()) return
        val ic = connection() ?: return
        val selected = ic.getSelectedText(0)?.toString()
        val input = if (!selected.isNullOrBlank()) Draft(selected, true) else Draft(typed, false)
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
        val localGeneration = translate || language.id == "en" || style != RewriteStyle.POLISH
        launchRequest(input) {
            val result = when {
                translate -> RewritePipeline.translateToEnglish(context, language, input.text)
                else -> RewritePipeline.rewrite(context, input.text, style, language)
            }
            AiPreview(result + if (input.text.endsWith(' ') && !result.endsWith(' ')) " " else "",
                generatedLocally = localGeneration, insertable = true)
        }
    }

    private fun launchRequest(draft: Draft? = null, work: suspend () -> AiPreview) {
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
            } catch (_: LocalModelRequired) {
                if (version == actionVersion && canUse()) {
                    preview = AiPreview("Download the offline writing model in Monolog Settings.")
                }
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
        val target = if (card == preview) pendingDraft else null
        if (target != null && !matches(ic, target)) {
            closePanel()
            return
        }
        if (target?.selected == false) {
            if (cursor < target.text.length || !ic.setSelection(cursor - target.text.length, cursor)) return
        }
        if (!ic.commitText(card.text, 1)) return
        closePanel()
        clearDraft()
    }

    private fun matches(ic: InputConnection, draft: Draft): Boolean =
        if (draft.selected) ic.getSelectedText(0)?.toString() == draft.text
        else !draft.text.isEmpty() && ic.getSelectedText(0).isNullOrEmpty() &&
            ic.getTextBeforeCursor(draft.text.length, 0)?.toString() == draft.text
}
