package com.notokens.monolog.ime.shortcuts

import java.time.ZonedDateTime

internal data class ShortcutSuggestion(
    val page: ShortcutPage,
    val title: String,
    val body: String = "",
    val dueAt: Long,
    val label: String
)

/** Keyword matching stays local; a suggestion only opens a draft and never saves it. */
internal object ShortcutSuggestions {
    private val alarm = Regex("\\b(alarm|wake me|wake up)\\b", RegexOption.IGNORE_CASE)
    private val reminder = Regex("\\b(remind(?:er|ers)?|remember|alerts?|don['’]t forget|do not forget|meeting|appointment|deadline)\\b", RegexOption.IGNORE_CASE)
    private val note = Regex("\\b(note|notes|jot down|save this|journal)\\b", RegexOption.IGNORE_CASE)
    private val command = Regex("^(?:(?:please\\s+)?(?:remind me(?:\\s+to)?|remember(?:\\s+to)?|set (?:an? |the )?(?:reminder|alarm|alert)(?:\\s+to)?|don['’]t forget(?:\\s+to)?|(?:save |make |take )?(?:a )?note(?:\\s+to)?|jot down|save this|journal))\\s*[:,-]?\\s*", RegexOption.IGNORE_CASE)
    private val relative = Regex("\\bin\\s+(\\d{1,3})\\s*(minutes?|mins?|hours?|hrs?|days?)\\b", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\b(?:(?:at\\s+)([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s*([ap])\\.?m\\.?)?|([1-9]|1[0-2])(?::([0-5]\\d))?\\s*([ap])\\.?m\\.?)", RegexOption.IGNORE_CASE)

    fun detect(text: String, now: ZonedDateTime = ZonedDateTime.now()): ShortcutSuggestion? {
        val input = text.trim().takeLast(400).substringAfterLast('\n').trim()
        if (input.isEmpty()) return null
        val page = when {
            alarm.containsMatchIn(input) -> ShortcutPage.ALARM
            Regex("\\bremember that\\b", RegexOption.IGNORE_CASE).containsMatchIn(input) -> ShortcutPage.NOTE
            reminder.containsMatchIn(input) -> ShortcutPage.REMINDER
            note.containsMatchIn(input) -> ShortcutPage.NOTE
            else -> return null
        }
        val content = input.replaceFirst(command, "").trim()
        val due = relative.find(input)?.let { match ->
            val amount = match.groupValues[1].toLong()
            when {
                match.groupValues[2].startsWith("d", true) -> now.plusDays(amount)
                match.groupValues[2].startsWith("h", true) -> now.plusHours(amount)
                else -> now.plusMinutes(amount)
            }
        } ?: run {
            val tomorrow = Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)
            var date = if (tomorrow) now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0) else now.plusHours(1)
            clock.find(input)?.let { match ->
                val hour = (match.groupValues[1].ifEmpty { match.groupValues[4] }).toInt()
                val minute = (match.groupValues[2].ifEmpty { match.groupValues[5] }).toIntOrNull() ?: 0
                val meridiem = match.groupValues[3].ifEmpty { match.groupValues[6] }
                val hour24 = if (meridiem.isEmpty()) hour else hour % 12 + if (meridiem.equals("p", true)) 12 else 0
                date = (if (tomorrow) now.plusDays(1) else now).withHour(hour24).withMinute(minute).withSecond(0).withNano(0)
                if (!date.isAfter(now)) date = date.plusDays(1)
            }
            date
        }
        return ShortcutSuggestion(page, content.take(80), if (page == ShortcutPage.NOTE) content else "",
            due.toInstant().toEpochMilli(), when (page) {
                ShortcutPage.NOTE -> "Save note"
                ShortcutPage.ALARM -> "Set alarm"
                else -> "Set reminder"
            })
    }
}
