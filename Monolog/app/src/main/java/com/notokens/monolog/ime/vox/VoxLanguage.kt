package com.notokens.monolog.ime.vox

enum class VoxLanguage(val code: String, val title: String, val pack: String = code) {
    ENGLISH("en", "English", "hi"),
    HINDI("hi", "Hindi"),
    HINGLISH("hi-Latn", "Hinglish", "hi"),
    ASSAMESE("as", "Assamese"),
    BENGALI("bn", "Bengali"),
    BODO("brx", "Bodo"),
    DOGRI("doi", "Dogri"),
    GUJARATI("gu", "Gujarati"),
    KANNADA("kn", "Kannada"),
    KASHMIRI("ks", "Kashmiri"),
    KONKANI("kok", "Konkani"),
    MAITHILI("mai", "Maithili"),
    MALAYALAM("ml", "Malayalam"),
    MANIPURI("mni", "Manipuri"),
    MARATHI("mr", "Marathi"),
    NEPALI("ne", "Nepali"),
    ODIA("or", "Odia"),
    PUNJABI("pa", "Punjabi"),
    SANSKRIT("sa", "Sanskrit"),
    SANTALI("sat", "Santali"),
    SINDHI("sd", "Sindhi"),
    TAMIL("ta", "Tamil"),
    TELUGU("te", "Telugu"),
    URDU("ur", "Urdu");

    companion object {
        val downloadChoices = entries.filter { it.pack == it.code }
        fun resolve(saved: String?, locale: String): VoxLanguage =
            entries.firstOrNull { it.code == saved }
                ?: entries.firstOrNull { it.code == locale.substringBefore('-').lowercase() }
                ?: ENGLISH
    }
}
