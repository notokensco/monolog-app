package com.notokens.monolog.ime

internal class SensitiveDraft {
    var custom = emptyList<Regex>()
        private set
    private val typed = StringBuilder()

    fun setWords(value: String) {
        custom = value.split(',').map(String::trim).filter(String::isNotEmpty).distinct().take(50)
            .map { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
    }

    fun append(value: String): Boolean {
        if (value.length > 80 && matches(value)) return true
        typed.append(value)
        if (typed.length > 1600) typed.delete(0, typed.length - 1600)
        return matches(typed)
    }

    fun backspace() {
        if (typed.isNotEmpty()) typed.delete(Character.offsetByCodePoints(typed, typed.length, -1), typed.length)
    }

    fun clear() { typed.clear() }

    fun matches(value: CharSequence): Boolean = keywords.containsMatchIn(value) ||
        custom.any { it.containsMatchIn(value) } || card.findAll(value).any { match ->
            val digits = match.value.filter(Char::isDigit)
            digits.length in 13..19 && digits.reversed().mapIndexed { index, digit ->
                val number = digit.digitToInt()
                if (index % 2 == 0) number else if (number < 5) number * 2 else number * 2 - 9
            }.sum() % 10 == 0
        }

    private companion object {
        val keywords = Regex("""\b(?:credit card|debit card|card number|cvv|cvc|upi pin|password|passcode|verification code|one[ -]time (?:password|code)|otp|recovery phrase|seed phrase|private key|api key|social security number|aadhaar|bank account number|account password)\b""", RegexOption.IGNORE_CASE)
        val card = Regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)""")
    }
}
