package com.notokens.monolog.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BriefRefreshTest {
    @Test fun changesToExistingItemsDoNotCountAsNewMaterial() {
        val oldIds = setOf("note:1", "note:2", "memory:3")
        val edited = oldIds.map { BriefSource(it, timestamp = 0L, text = "edited ".repeat(300)) }
        assertFalse(hasEnoughNewBriefData(oldIds, edited))
        assertFalse(hasEnoughNewBriefData(oldIds, edited + BriefSource("note:4", 1L, "Small addition")))
    }

    @Test fun severalNewEntriesOrALongNewNoteTriggerARefresh() {
        val oldIds = setOf("note:1")
        val newEntries = (2..4).map { BriefSource("note:$it", it.toLong(), "A saved moment") }
        assertTrue(hasEnoughNewBriefData(oldIds, newEntries))
        assertTrue(hasEnoughNewBriefData(oldIds, listOf(BriefSource("note:2", 2L, "word ".repeat(250)))))
    }
}
