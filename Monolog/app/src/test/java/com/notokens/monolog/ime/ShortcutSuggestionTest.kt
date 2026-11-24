package com.notokens.monolog.ime.shortcuts

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ShortcutSuggestionTest {
    private val now = ZonedDateTime.parse("2026-11-21T16:00:00+05:30[Asia/Kolkata]")

    @Test fun reminderPrefillsTextAndClockTime() {
        val suggestion = ShortcutSuggestions.detect("Remind me to call Sam at 5 pm", now)!!
        assertEquals(ShortcutPage.REMINDER, suggestion.page)
        assertEquals("call Sam at 5 pm", suggestion.title)
        assertEquals("Set a reminder to call Sam at 5 pm?", suggestion.prompt)
        assertEquals(now.withHour(17).toInstant().toEpochMilli(), suggestion.dueAt)
    }

    @Test fun alarmAndRelativeAlertsAreRecognized() {
        val alarm = ShortcutSuggestions.detect("Set an alarm at 7:30 am tomorrow", now)!!
        assertEquals(ShortcutPage.ALARM, alarm.page)
        assertEquals("Set an alarm at 7:30 am tomorrow?", alarm.prompt)
        assertEquals(now.plusDays(1).withHour(7).withMinute(30).toInstant().toEpochMilli(), alarm.dueAt)
        assertEquals(now.plusMinutes(20).toInstant().toEpochMilli(),
            ShortcutSuggestions.detect("Alert me in 20 minutes", now)!!.dueAt)
        assertEquals(ShortcutPage.REMINDER, ShortcutSuggestions.detect("Don't forget the meeting", now)!!.page)
    }

    @Test fun notesRetainContentForReview() {
        val note = ShortcutSuggestions.detect("Note: Bring the blue folder", now)!!
        assertEquals(ShortcutPage.NOTE, note.page)
        assertEquals("Bring the blue folder", note.title)
        assertEquals("Bring the blue folder", note.body)
        assertEquals(ShortcutPage.NOTE, ShortcutSuggestions.detect("Remember that my seat is 12A", now)!!.page)
    }

    @Test fun onlyWholeKeywordsAndCurrentLineTriggerSuggestions() {
        assertNull(ShortcutSuggestions.detect("noteworthy and alarming", now))
        assertNull(ShortcutSuggestions.detect("reminder\nHello there", now))
        assertNull(ShortcutSuggestions.detect("", now))
    }

    @Test fun pastClockTimesRollForwardAndMidnightIsParsed() {
        assertEquals(now.plusDays(1).withHour(9).toInstant().toEpochMilli(),
            ShortcutSuggestions.detect("Reminder at 9 am", now)!!.dueAt)
        assertEquals(now.plusDays(1).withHour(0).toInstant().toEpochMilli(),
            ShortcutSuggestions.detect("Alarm at 12 am", now)!!.dueAt)
        assertEquals(now.withHour(17).withMinute(45).toInstant().toEpochMilli(),
            ShortcutSuggestions.detect("Reminder at 17:45", now)!!.dueAt)
    }
}
