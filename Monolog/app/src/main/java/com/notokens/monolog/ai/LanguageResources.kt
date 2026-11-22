package com.notokens.monolog.ai

import android.content.Context
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

internal object LocalMlKit {
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (!initialized) {
            MlKit.initialize(context.applicationContext)
            initialized = true
        }
    }
}

internal data class KeyboardLanguage(
    val id: String,
    val label: String,
    val speechTag: String,
    val script: IntRange? = null,
    val letterRows: List<String>? = null,
    val alternateLetters: Map<Char, String> = emptyMap()
) {
    val supportsLatinSwipe get() = id == "en" || id == "hi-Latn" || letterRows != null
    val translationCode: String?
        get() = if (id == "en" || id == "hi-Latn") null else TranslateLanguage.fromLanguageTag(id)

    fun nativeKeys(): List<String> = script?.mapNotNull { point ->
        val category = Character.getType(point)
        if (Character.isLetter(point) || category == Character.NON_SPACING_MARK.toInt() ||
            category == Character.COMBINING_SPACING_MARK.toInt()) String(Character.toChars(point)) else null
    }.orEmpty()

    companion object {
        val all = listOf(
            KeyboardLanguage("en", "English (India)", "en-IN"),
            KeyboardLanguage("hi", "Hindi · हिन्दी", "hi-IN", 0x0901..0x094d),
            KeyboardLanguage("hi-Latn", "Hinglish · Romanized Hindi", "hi-IN"),
            KeyboardLanguage("bn", "Bengali · বাংলা", "bn-IN", 0x0981..0x09cd),
            KeyboardLanguage("as", "Assamese · অসমীয়া", "as-IN", 0x0981..0x09f1),
            KeyboardLanguage("gu", "Gujarati · ગુજરાતી", "gu-IN", 0x0a81..0x0acd),
            KeyboardLanguage("kn", "Kannada · ಕನ್ನಡ", "kn-IN", 0x0c81..0x0ccd),
            KeyboardLanguage("ml", "Malayalam · മലയാളം", "ml-IN", 0x0d02..0x0d4d),
            KeyboardLanguage("mr", "Marathi · मराठी", "mr-IN", 0x0901..0x094d),
            KeyboardLanguage("or", "Odia · ଓଡ଼ିଆ", "or-IN", 0x0b01..0x0b4d),
            KeyboardLanguage("pa", "Punjabi · ਪੰਜਾਬੀ", "pa-IN", 0x0a01..0x0a4d),
            KeyboardLanguage("ta", "Tamil · தமிழ்", "ta-IN", 0x0b82..0x0bcd),
            KeyboardLanguage("te", "Telugu · తెలుగు", "te-IN", 0x0c01..0x0c4d),
            KeyboardLanguage("ur", "Urdu · اردو", "ur-IN", 0x0621..0x06d3),
            KeyboardLanguage("de", "German · Deutsch", "de-DE",
                letterRows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
                alternateLetters = mapOf('a' to "ä", 'o' to "ö", 'u' to "ü", 's' to "ß")),
            KeyboardLanguage("es", "Spanish · Español", "es-ES",
                letterRows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
                alternateLetters = mapOf('a' to "á", 'e' to "é", 'i' to "í", 'o' to "ó", 'u' to "úü")),
            KeyboardLanguage("fr", "French · Français", "fr-FR",
                letterRows = listOf("azertyuiop", "qsdfghjklm", "wxcvbn'"),
                alternateLetters = mapOf('a' to "àâæ", 'e' to "éèêë", 'i' to "îï", 'o' to "ôœ", 'u' to "ùûü", 'c' to "ç", 'y' to "ÿ"))
        )

        fun of(id: String): KeyboardLanguage = all.find { it.id == id } ?: all.first()
    }
}

internal object LanguageResources {
    suspend fun installed(context: Context): Set<String> {
        LocalMlKit.initialize(context)
        return RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .await().mapTo(mutableSetOf()) { it.language }
    }

    suspend fun download(context: Context, language: KeyboardLanguage) {
        LocalMlKit.initialize(context)
        val code = requireNotNull(language.translationCode)
        val model = TranslateRemoteModel.Builder(code).build()
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()).await()
    }

    suspend fun remove(context: Context, language: KeyboardLanguage) {
        LocalMlKit.initialize(context)
        val code = requireNotNull(language.translationCode)
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
    }

    suspend fun toEnglish(context: Context, language: KeyboardLanguage, text: String): String? {
        return translate(context, language, text, toEnglish = true)
    }

    suspend fun fromEnglish(context: Context, language: KeyboardLanguage, text: String): String? {
        return translate(context, language, text, toEnglish = false)
    }

    private suspend fun translate(context: Context, language: KeyboardLanguage, text: String, toEnglish: Boolean): String? {
        val code = language.translationCode ?: return null
        if (code !in installed(context)) return null
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(if (toEnglish) code else TranslateLanguage.ENGLISH)
            .setTargetLanguage(if (toEnglish) TranslateLanguage.ENGLISH else code)
            .build()
        val translator = Translation.getClient(options)
        return try {
            translator.translate(text).await()
        } finally {
            translator.close()
        }
    }
}
