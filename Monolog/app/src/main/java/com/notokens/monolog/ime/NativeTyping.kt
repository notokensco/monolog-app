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
        HINGLISH("hi-Latn", "english-4.0.0.dic"),
        GERMAN("de", "typing/de.txt"),
        SPANISH("es", "typing/es.txt"),
        FRENCH("fr", "typing/fr.txt")
    }

    private val loaded = mutableSetOf<Lexicon>()

    fun warm(context: Context, language: String) { ready(context, language) }

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
        val everydayEnglish = if (lexicon == Lexicon.ENGLISH || lexicon == Lexicon.HINGLISH) {
            readCommonWords(context, "typing/indic/flores-en.tsv", 380)
        } else ""
        val commonHinglish = if (lexicon == Lexicon.HINGLISH) {
            readRomanizedHindi(context)
        } else ""
        val preferred = when (lexicon) {
            Lexicon.ENGLISH -> TypingVocabulary.english.joinToString(" ")
            Lexicon.HINGLISH -> TypingVocabulary.hinglish.joinToString(" ") + " " + commonHinglish
            else -> ""
        }
        val secondary = when (lexicon) {
            Lexicon.ENGLISH -> TypingVocabulary.hinglish.joinToString(" ") + " " + everydayEnglish
            Lexicon.HINGLISH -> TypingVocabulary.english.joinToString(" ") + " " + everydayEnglish
            else -> ""
        }
        if (!installLexicon(lexicon.ordinal, corpus, preferred, secondary)) return null
        loaded += lexicon
        return lexicon
    }

    private fun readCommonWords(context: Context, path: String, limit: Int): String =
        runCatching {
            context.assets.open(path).bufferedReader().useLines { rows ->
                rows.filterNot { it.startsWith('#') }.mapNotNull { row ->
                    val columns = row.split('\t')
                    columns.firstOrNull()?.takeIf { columns.size == 2 && it.length in 2..24 &&
                        it.all { char -> char in 'a'..'z' || char == '\'' } }
                }.take(limit).joinToString(" ")
            }
        }.getOrDefault("")

    private fun readRomanizedHindi(context: Context): String = runCatching {
        val frequency = context.assets.open("typing/indic/flores-hi.tsv").bufferedReader().useLines { rows ->
            rows.map { it.split('\t') }.filter { it.size == 2 }.associate { it[0] to (it[1].toIntOrNull() ?: 0) }
        }
        context.assets.open("typing/indic/dakshina-hi.tsv").bufferedReader().useLines { rows ->
            rows.map { it.split('\t') }.filter { it.size == 3 && it[0].length in 2..24 }
                .sortedWith(compareByDescending<List<String>> { frequency[it[1]] ?: 0 }
                    .thenByDescending { it[2].toIntOrNull() ?: 0 })
                .map { it[0] }.distinct().take(260).joinToString(" ")
        }
    }.getOrDefault("")

    fun suggestions(context: Context, language: String, prefix: String, before: String, next: List<String>): Candidates? {
        val lexicon = ready(context, language) ?: return null
        val ranked = rankSuggestions(lexicon.ordinal, prefix, next.joinToString(" "), before.takeLast(128).lowercase(java.util.Locale.ROOT)) ?: return null
        return Candidates(ranked.substringBefore('\n').ifEmpty { null }, ranked.substringAfter('\n', "").words())
    }

    fun swipe(context: Context, language: KeyboardLanguage, points: List<SwipePoint>, before: String, next: List<String>): List<String>? {
        val lexicon = ready(context, language.id) ?: return null
        val traceSize = minOf(points.size, 256)
        val trace = FloatArray(traceSize * 2)
        for (i in 0 until traceSize) {
            val point = points[i * (points.size - 1) / (traceSize - 1).coerceAtLeast(1)]
            trace[i * 2] = point.x
            trace[i * 2 + 1] = point.y
        }
        val layout = QwertyLayout.nativeCenters(language.letterRows ?: QwertyLayout.rows)
        return rankSwipe(lexicon.ordinal, trace, layout, next.joinToString(" "), before.takeLast(128).lowercase(java.util.Locale.ROOT))?.words()
    }

    fun nextWords(before: String, hinglish: Boolean): List<String> =
        if (available) rankNext(if (hinglish) 1 else 0, before.takeLast(128).lowercase(java.util.Locale.ROOT))
            ?.words().orEmpty() else emptyList()

    fun voiceSamples(pcm: ByteArray): FloatArray =
        (if (available) decodePcm(pcm) else null) ?: FloatArray(pcm.size / 2) { pcmSample(pcm, it) }

    fun voiceRms(pcm: ByteArray, count: Int): Float {
        if (available) return measurePcm(pcm, count)
        val samples = count.coerceIn(0, pcm.size) / 2
        var power = 0f
        for (i in 0 until samples) {
            val sample = pcmSample(pcm, i)
            power += sample * sample
        }
        return kotlin.math.sqrt(power / samples.coerceAtLeast(1))
    }

    private fun pcmSample(pcm: ByteArray, i: Int) =
        ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() / 32768f

    private external fun rankNext(mode: Int, context: String): String?
    private external fun decodePcm(pcm: ByteArray): FloatArray?
    private external fun measurePcm(pcm: ByteArray, count: Int): Float

    fun phoneticKey(word: String): String? = if (available) pronunciationKey(word) else null

    private external fun pronunciationKey(word: String): String?

    private fun String.words(): List<String> = if (isEmpty()) emptyList() else split('\n')

    private external fun installLexicon(mode: Int, corpus: String, preferred: String, secondary: String): Boolean
    private external fun rankSuggestions(mode: Int, prefix: String, context: String, before: String): String?
    private external fun rankSwipe(mode: Int, path: FloatArray, layout: FloatArray, context: String, before: String): String?
}
