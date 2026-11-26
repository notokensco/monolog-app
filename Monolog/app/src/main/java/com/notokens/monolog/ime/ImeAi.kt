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
import com.notokens.monolog.ai.WebSearchKind
import com.notokens.monolog.ai.WebLookup
import com.notokens.monolog.data.MonologData
import com.notokens.monolog.data.SettingsStore
import com.notokens.monolog.data.dictionaryWord
import com.notokens.monolog.data.asksAboutJournalCapture
import com.notokens.monolog.data.matchingJournalEntries
import com.notokens.monolog.data.RememberLanguage
import com.notokens.monolog.data.RememberNotebook
import com.notokens.monolog.capture.TagMetadataCodec
import com.notokens.monolog.memory.MemorySearch
import com.notokens.monolog.ime.shortcuts.ShortcutSuggestion
import com.notokens.monolog.ime.shortcuts.ShortcutSuggestions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal val editorReadDispatcher = Dispatchers.IO.limitedParallelism(1)

internal data class PersonalMatch(
    val title: String, val excerpt: String,
    val noteId: Long? = null, val reminderId: Long? = null, val day: String? = null
)

internal class ImeAi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val dictionary: EnglishSuggestions,
    private val connection: () -> InputConnection?,
    private val canUse: () -> Boolean,
    private val canShowLocalToolbar: () -> Boolean = canUse,
    private val typedActionsAllowed: () -> Boolean = { true },
    private val proactiveAllowed: () -> Boolean = { true },
    private val automaticNetworkAllowed: () -> Boolean = { true },
    private val personalizationAllowed: () -> Boolean = { true },
    private val typingProfileAllowed: () -> Boolean = personalizationAllowed,
    private val onSuggestionAccepted: () -> Unit = {},
    private val personalSearchAllowed: () -> Boolean = personalizationAllowed,
    private val checkDraftPrivacy: (String) -> Unit = {},
    private val phoneActionsEnabled: () -> Boolean = { true },
    private val activePackage: () -> String = { "" },
    private val onTextApplied: (String?, String) -> Unit = { _, _ -> }
) {
    private data class EditorSample(val before: String, val selected: String, val after: String)
    private data class Draft(val text: String, val selected: Boolean, val afterCursor: Int = 0)
    var toolbarInput by mutableStateOf("")
        private set
    var rememberCandidate by mutableStateOf<String?>(null)
        private set
    private var rememberedDraft: String? = null
    private val rememberNotebook by lazy { RememberNotebook(context) }
    private var liveInput: String? = null
    var hasDraft by mutableStateOf(false)
        private set
    private var wordSuggestions by mutableStateOf(emptyList<WordSuggestion>())
    private var swipeAlternatives by mutableStateOf(emptyList<WordSuggestion>())
    private var hiddenWords by mutableStateOf(emptySet<String>())
    val suggestions get() = swipeAlternatives.ifEmpty { wordSuggestions }.filterNot {
        typingProfileAllowed() && (it.label.lowercase(java.util.Locale.ROOT) in hiddenWords || dictionary.hidesSuggestion(it.label))
    }
    var selectedDictionaryWord by mutableStateOf<String?>(null)
        private set
    var suggestionToRemove by mutableStateOf<WordSuggestion?>(null)
        private set
    val dictionaryActionsAllowed get() = typingProfileAllowed()

    fun requestSuggestionRemoval(word: WordSuggestion?) {
        suggestionToRemove = word?.takeIf { dictionaryActionsAllowed && it in suggestions && dictionaryWord(it.label) != null }
    }

    fun removeCurrentSuggestion() {
        val suggestion = suggestionToRemove ?: return
        suggestionToRemove = null
        val word = dictionaryWord(suggestion.label)?.takeIf { dictionaryActionsAllowed } ?: return
        hiddenWords = hiddenWords + word.lowercase(java.util.Locale.ROOT)
        wordSuggestions = wordSuggestions.filterNot { it.label.equals(word, ignoreCase = true) }
        swipeAlternatives = swipeAlternatives.filterNot { it.label.equals(word, ignoreCase = true) }
        scope.launch {
            try { settings.removePersonalWord(word) }
            finally { hiddenWords = hiddenWords - word.lowercase(java.util.Locale.ROOT) }
            refreshDraft()
        }
    }

    fun addSelectedDictionaryWord() {
        val word = selectedDictionaryWord?.takeIf { dictionaryActionsAllowed } ?: return
        val ic = connection() ?: return
        scope.launch {
            val current = withContext(editorReadDispatcher) { ic.getSelectedText(0)?.toString() }
            if (current == word && connection() === ic && dictionaryActionsAllowed && settings.addPersonalWord(word)) {
                selectedDictionaryWord = null
            }
        }
    }
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
    val personalAllowed get() = personalSearchAllowed()
    val journalAllowed get() = personalizationAllowed()
    var web by mutableStateOf(false)
        private set
    var safeSearch by mutableStateOf(true)
        private set
    var personal by mutableStateOf(false)
        private set
    var personalMatches by mutableStateOf(emptyList<PersonalMatch>())
        private set

    fun togglePersonal() {
        abortAction()
        val next = personalAllowed && !personal
        if (next) web = false
        personal = next
    }

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
    private var deleteUntil = 0L
    private var expectedCursor: Int? = null
    private var cursor = -1

    fun reset(initialCursor: Int = -1) {
        closePanel()
        clearDraft()
        rememberedDraft = null
        if (initialCursor == 0) liveInput = ""
        web = false
        cursor = initialCursor
        deleteUntil = 0L
    }

    fun closePanel() {
        abortAction()
        warmup?.cancel()
        rewriteOpen = false
        askOpen = false
        personal = false
    }

    fun beginSearch() {
        closePanel()
        clearDraft()
        askOpen = true
    }

    fun clearDraft(clearToolbarInput: Boolean = true, keepWordSuggestions: Boolean = false) {
        expectedCursor = null
        liveInput = null
        suggestionJob?.cancel()
        if (clearToolbarInput) {
            toolbarInput = ""
            hasDraft = false
        }
        if (!keepWordSuggestions) wordSuggestions = emptyList()
        swipeAlternatives = emptyList()
        selectedDictionaryWord = null
        suggestionToRemove = null
        shortcutSuggestion = null
        rememberCandidate = null
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
        clearDraft(keepWordSuggestions = start == end && android.os.SystemClock.uptimeMillis() < deleteUntil)
        if (rewriteOpen) closePanel() else abortAction()
        cursor = end
        refreshDraft()
    }

    fun textCommitted(value: String) {
        deleteUntil = 0L
        closePanel()
        cancelLookup()
        if (!canShowLocalToolbar()) {
            clearDraft()
            return
        }
        if (cursor >= 0) expectedCursor = (expectedCursor ?: cursor) + value.length
        if (value.isNotBlank()) hasDraft = true
        val previousWord = if (value == " ") liveInput?.let(dictionary::typedCandidate)?.typed else null
        if (previousWord != null) {
            val continuations = suggestions.filter {
                it.nextWord && it.typed == previousWord && it.replacement == "$previousWord " + it.label + " "
            }
            if (continuations.isNotEmpty()) {
                wordSuggestions = continuations.map { WordSuggestion("", it.label + " ") }
            }
        }
        val updatedInput = liveInput?.let { (it + value).takeLast(96) }
        liveInput = updatedInput
        if (canUse() && value.isNotBlank() && updatedInput != null) {
            dictionary.typedCandidate(updatedInput)?.takeIf { it.typed.length < 96 }?.let {
                wordSuggestions = listOf(it)
            }
        }
        toolbar = null
        toolbarDraft = null
        swipeAlternatives = emptyList()
        inlineActions = emptyList()
        rememberCandidate = null
        refreshDraft(typing = true)
    }

    fun backspaceStarted() {
        deleteUntil = android.os.SystemClock.uptimeMillis() + 100
        closePanel()
        clearDraft(clearToolbarInput = false, keepWordSuggestions = true)
    }

    fun refreshDraft(typing: Boolean = false) {
        suggestionJob?.cancel()
        suggestionJob = scope.launch {
            val wait = maxOf(if (typing) 75L else 0L, deleteUntil - android.os.SystemClock.uptimeMillis())
            if (wait > 0) delay(wait)
            if (!canShowLocalToolbar() || askOpen) return@launch
            val ic = connection() ?: return@launch
            val (before, selected, after) = withContext(editorReadDispatcher) {
                val surrounding = ic.getSurroundingText(400, 400, 0)?.takeIf { state ->
                    state.selectionStart in 0..state.text.length && state.selectionEnd in state.selectionStart..state.text.length
                }
                val text = surrounding?.text
                val selected = if (surrounding == null || text == null) ic.getSelectedText(0)?.toString().orEmpty()
                    else text.substring(surrounding.selectionStart, surrounding.selectionEnd)
                val before = if (surrounding == null || text == null) ic.getTextBeforeCursor(400, 0)?.toString().orEmpty()
                    else text.substring(0, surrounding.selectionStart)
                val after = if (surrounding == null || text == null) ic.getTextAfterCursor(400, 0)?.toString().orEmpty()
                    else text.substring(surrounding.selectionEnd)
                EditorSample(before, selected, after)
            }
            if (ic !== connection() || !canShowLocalToolbar() || askOpen) return@launch
            val visibleText = selected.ifBlank { before + after }
            checkDraftPrivacy(if (selected.isEmpty()) visibleText else before + selected + after)
            rememberCandidate = if (canUse() && personalSearchAllowed()) {
                RememberLanguage.body(visibleText).takeUnless { it == rememberedDraft }
            } else null
            fun wordEdge(char: Char?) = char != null && (char.isLetterOrDigit() || char in "'’-_")
            selectedDictionaryWord = dictionaryWord(selected)?.takeIf {
                it == selected && dictionaryActionsAllowed && !wordEdge(before.lastOrNull()) && !wordEdge(after.firstOrNull()) &&
                    !dictionary.inPersonalDictionary(it)
            }
            if (typing) suggestionToRemove = null
            liveInput = before.takeLast(96)
            toolbarInput = if (selected.isEmpty()) before.takeLast(96) else " "
            hasDraft = visibleText.isNotBlank()
            inlineActions = if (phoneActionsEnabled() && typedActionsAllowed()) {
                DeviceActions.detect(selected.ifBlank { before }, automaticNetworkAllowed())
            } else emptyList()
            if (!canUse()) return@launch
            shortcutSuggestion = ShortcutSuggestions.detect(selected.ifBlank { before })?.let { suggestion ->
                if (personalizationAllowed()) suggestion
                else suggestion.takeUnless { it.page == com.notokens.monolog.ime.shortcuts.ShortcutPage.NOTE }
                    ?.copy(title = "", body = "")
            }
            if (typing && selected.isEmpty() && rememberCandidate == null) {
                suggest(before.substringAfterLast('\n'), replaceTypedDraft = true)
            }
            val language = settings.keyboardLanguage.first()
            wordSuggestions = if (selected.isEmpty()) {
                val personalize = typingProfileAllowed()
                val ranked = dictionary.suggest(before, language, personalize)
                if (personalize && ranked.none { !dictionary.hidesSuggestion(it.label) }) {
                    listOfNotNull(dictionary.typedCandidate(before))
                } else ranked
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

    fun saveRememberCandidate() {
        val body = rememberCandidate ?: return
        if (!canUse() || !personalSearchAllowed()) return
        rememberCandidate = null
        rememberedDraft = body
        scope.launch {
            val saved = try { rememberNotebook.save(body) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!saved && rememberedDraft == body) rememberedDraft = null
            android.widget.Toast.makeText(context,
                if (saved) "Added to Remember" else "Couldn’t save to Remember.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun offerSwipeAlternatives(typed: String, alternatives: List<String>, next: List<String>) {
        if (!canUse()) return
        swipeAlternatives = buildList {
            add(WordSuggestion(typed, "$typed ", emphasized = true))
            next.firstOrNull()?.let { add(WordSuggestion(typed, "$typed $it ", label = it, nextWord = true)) }
            alternatives.take(2).forEach { add(WordSuggestion(typed, "$it ")) }
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
            onSuggestionAccepted()
            onTextApplied(suggestion.typed, suggestion.replacement)
            clearDraft()
            refreshDraft(typing = true)
        }
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
        val request = AiText.proactiveRequest(text)
        if (request == null) {
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
            delay(if (request.endsWith('?')) 750 else 1_400)
            if (!canUse() || busy || rewriteOpen || askOpen) return@launch
            inlineBusy = true
            try {
                val answer = RewritePipeline.respondOffline(context, request, inline = true)
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

    fun toggleWeb() {
        abortAction()
        cancelLookup()
        web = automaticNetworkAllowed() && !web
        if (web) personal = false
    }

    fun setWebSafeSearch(enabled: Boolean) {
        if (!web || !webAllowed || enabled == safeSearch) return
        abortAction()
        safeSearch = enabled
    }

    fun abortAction() {
        actionVersion++
        action?.cancel()
        action = null
        preview = null
        personalMatches = emptyList()
        askActions = emptyList()
        busy = false
        pendingDraft = null
    }

    fun searchWeb(query: String, kind: WebSearchKind = WebSearchKind.WEB) {
        checkDraftPrivacy(query)
        if (!canUse() || !web || !webAllowed || query.isBlank()) return
        abortAction()
        if (!WebLookup.openSearchApp(context, query, safeSearch, kind)) {
            preview = AiPreview("No browser is available to open DuckDuckGo.")
        }
    }

    fun ask(query: String, kind: WebSearchKind = WebSearchKind.WEB) {
        if (!canUse() || query.isBlank()) return
        val requestedWeb = web
        checkDraftPrivacy(query)
        if (requestedWeb) {
            if (web && webAllowed) askWeb(query, kind)
            return
        }
        if (!personal && phoneActionsEnabled() && personalizationAllowed()) {
            val choices = DeviceActions.detect(query, automaticNetworkAllowed(), explicit = true)
            if (choices.isNotEmpty()) {
                abortAction()
                askActions = choices
                return
            }
        }
        launchRequest {
            if (personal && personalAllowed) personalAnswer(query) else RewritePipeline.respondOffline(context, query)
        }
    }

    private fun askWeb(query: String, kind: WebSearchKind) {
        if (WebLookup.searchProvider == null) {
            abortAction()
            preview = AiPreview("In-keyboard web search is awaiting provider setup.", webKind = kind)
            return
        }
        val filter = safeSearch
        val version = actionVersion + 1
        launchRequest {
            val result = try {
                WebLookup.ask(query, filter, kind)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launchRequest AiPreview("Couldn’t load results. Try again or open in browser.", webKind = kind)
            }
            val empty = if (kind == WebSearchKind.IMAGES) "No images found." else "No results found."
            val cards = AiPreview(if (result.sources.isEmpty()) empty else "", sources = result.sources, webKind = kind)
            if (kind == WebSearchKind.IMAGES || result.sources.isEmpty() || ModelStore.get(context).state.value !is ModelState.Ready) {
                return@launchRequest cards
            }
            if (version != actionVersion || !canUse() || !web || !webAllowed) return@launchRequest cards
            preview = cards
            val summary = try {
                val prompt = AiText.answerPrompt(query, result.text)
                RewritePipeline.gemma(context, prompt, 96)?.let { AiText.checkedAnswer(query, it, result.text) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (summary.isNullOrBlank()) cards else cards.copy(text = summary, generatedLocally = true, insertable = true)
        }
    }

    private suspend fun personalAnswer(query: String): AiPreview {
        val matches = withContext(kotlinx.coroutines.Dispatchers.Default) {
            val notes = MonologData.repository(context).notes.first().matchingJournalEntries(query, limit = 5)
            val direct = notes.map { note ->
                val decoded = TagMetadataCodec.decode(note.body)
                PersonalMatch(note.title, (decoded.bottomComment.ifBlank { decoded.cleanBody }).take(240), noteId = note.id)
            }
            val recorded = try {
                MemorySearch(context).search(query, year = null).map { match ->
                    val row = match.record
                    PersonalMatch(match.evidence.summary, match.evidence.excerpt.orEmpty().take(240),
                        row.noteId, row.reminderId, row.localDay)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            (direct + recorded).distinctBy { it.noteId?.let { id -> "note/$id" }
                ?: it.reminderId?.let { id -> "reminder/$id" } ?: "day/${it.day}/${it.title}" }.take(6)
        }
        if (!personal || !personalAllowed) return AiPreview("Personal search is unavailable.")
        val facts = rememberNotebook.recall(query)
        if (!personal || !personalAllowed) return AiPreview("Personal search is unavailable.")
        personalMatches = matches
        if (matches.isEmpty() && facts.isEmpty()) return AiPreview("No matching journal entries, notes, or captures.")
        val evidence = (facts.map { "Remember entry: $it" } + matches.map { "${it.title} ${it.excerpt}" }).joinToString("\n")
        return RewritePipeline.askOffline(context, query, evidence)
    }

    fun openRewrite(language: KeyboardLanguage) {
        if (!canUse() || !hasDraft) return
        cancelLookup()
        closePanel()
        rewriteOpen = true
        if (language.isEnglish && ModelStore.get(context).state.value is ModelState.Ready) {
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
        if (!translate && !language.isEnglish && language.translationCode == null && style != RewriteStyle.POLISH) {
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
