package com.notokens.monolog.data

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class HobbyStreakTest {
    @Test fun streakStaysOpenThroughTodayAndResetsAfterAMissedDay() {
        val today = LocalDate.parse("2026-11-26")
        val days = setOf("2026-11-24", "2026-11-25")
        assertEquals(2, hobbyStreak(days, today))
        assertEquals(3, hobbyStreak(days + today.toString(), today))
        assertEquals(0, hobbyStreak(days, today.plusDays(1)))
        assertEquals(1, hobbyStreak(days + "2026-11-27", today.plusDays(1)))
    }
}
