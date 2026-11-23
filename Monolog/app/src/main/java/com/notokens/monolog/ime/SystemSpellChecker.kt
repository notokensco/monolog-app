package com.notokens.monolog.ime

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal object SystemSpellChecker {
    private val token = Regex("""[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)?\z""")

    suspend fun suggest(context: Context, before: String, locale: Locale): List<WordSuggestion> {
        val typed = token.find(before)?.value?.takeIf { it.length in 3..32 } ?: return emptyList()
        val service = context.getSystemService(TextServicesManager::class.java) ?: return emptyList()
        if (!service.isSpellCheckerEnabled) return emptyList()
        return withTimeoutOrNull(700) {
            suspendCancellableCoroutine { result ->
                var session: SpellCheckerSession? = null
                fun finish(values: List<WordSuggestion>) {
                    session?.close()
                    if (result.isActive) result.resume(values)
                }
                val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
                    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit
                    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
                        val sentence = results?.firstOrNull()
                        val values = buildList {
                            for (index in 0 until (sentence?.suggestionsCount ?: 0)) {
                                val item = sentence?.getSuggestionsInfoAt(index) ?: continue
                                if (item.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) continue
                                for (candidate in 0 until item.suggestionsCount) {
                                    val word = item.getSuggestionAt(candidate) ?: continue
                                    if (!word.equals(typed, true) && token.matches(word) &&
                                        word.codePoints().allMatch { cp -> !Character.isLetter(cp) ||
                                            Character.UnicodeScript.of(cp) == Character.UnicodeScript.of(typed.codePointAt(0)) }) {
                                        add(WordSuggestion(typed, "$word "))
                                    }
                                }
                            }
                        }
                        finish(values.distinct().take(3))
                    }
                }
                val params = SpellCheckerSession.SpellCheckerSessionParams.Builder()
                    .setLocale(locale).setShouldReferToSpellCheckerLanguageSettings(false).build()
                session = runCatching { service.newSpellCheckerSession(params, context.mainExecutor, listener) }.getOrNull()
                if (session == null) finish(emptyList())
                else {
                    val active = session
                    result.invokeOnCancellation { context.mainExecutor.execute { active.close() } }
                    if (runCatching { active.getSentenceSuggestions(arrayOf(TextInfo(typed)), 3) }.isFailure) {
                        finish(emptyList())
                    }
                }
            }
        }.orEmpty()
    }
}
