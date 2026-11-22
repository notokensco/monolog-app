package com.notokens.monolog.ime

import android.content.Context
import androidx.annotation.Keep
import com.notokens.monolog.ai.KeyboardLanguage

@Keep
internal object NativeTyping {
    val available: Boolean by lazy {
        try {
            System.loadLibrary("monolog_typing")
            true
        } catch (_: LinkageError) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    internal data class Candidates(val correction: String?, val words: List<String>)

    private enum class Lexicon(val id: String, val asset: String?) {
        ENGLISH("en", "english-4.0.0.dic"),
        HINGLISH("hi-Latn", null),
        GERMAN("de", "typing/de.txt"),
        SPANISH("es", "typing/es.txt"),
        FRENCH("fr", "typing/fr.txt")
    }

    private val loaded = mutableSetOf<Lexicon>()

    @Synchronized
    private fun ready(context: Context, language: String): Lexicon? {
        if (!available) return null
        val lexicon = Lexicon.entries.firstOrNull { it.id == language } ?: return null
        if (lexicon in loaded) return lexicon
        val corpus = try {
            lexicon.asset?.let { context.assets.open(it).bufferedReader().use { r -> r.readText() } }.orEmpty()
        } catch (_: java.io.IOException) {
            return null
        }
        val preferred = when (lexicon) {
            Lexicon.ENGLISH -> TypingVocabulary.english.joinToString(" ")
            Lexicon.HINGLISH -> TypingVocabulary.hinglish.joinToString(" ")
            else -> ""
        }
        val secondary = if (lexicon == Lexicon.HINGLISH) TypingVocabulary.english.joinToString(" ") else ""
        if (!installLexicon(lexicon.ordinal, corpus, preferred, secondary)) return null
        loaded += lexicon
        return lexicon
    }

    fun suggestions(context: Context, language: String, prefix: String, next: List<String>): Candidates? {
        val lexicon = ready(context, language) ?: return null
        val ranked = rankSuggestions(lexicon.ordinal, prefix, next.joinToString(" ")) ?: return null
        return Candidates(ranked.substringBefore('\n').ifEmpty { null }, ranked.substringAfter('\n', "").words())
    }

    fun swipe(context: Context, language: KeyboardLanguage, points: List<SwipePoint>, next: List<String>): List<String>? {
        val lexicon = ready(context, language.id) ?: return null
        val traceSize = minOf(points.size, 256)
        val trace = FloatArray(traceSize * 2)
        for (i in 0 until traceSize) {
            val point = points[i * (points.size - 1) / (traceSize - 1).coerceAtLeast(1)]
            trace[i * 2] = point.x
            trace[i * 2 + 1] = point.y
        }
        val layout = QwertyLayout.nativeCenters(language.letterRows ?: QwertyLayout.rows)
        return rankSwipe(lexicon.ordinal, trace, layout, next.joinToString(" "))?.words()
    }

    fun phoneticKey(word: String): String? = if (available) pronunciationKey(word) else null

    private external fun pronunciationKey(word: String): String?

    private fun String.words(): List<String> = if (isEmpty()) emptyList() else split('\n')

    private external fun installLexicon(mode: Int, corpus: String, preferred: String, secondary: String): Boolean
    private external fun rankSuggestions(mode: Int, prefix: String, context: String): String?
    private external fun rankSwipe(mode: Int, path: FloatArray, layout: FloatArray, context: String): String?
}
