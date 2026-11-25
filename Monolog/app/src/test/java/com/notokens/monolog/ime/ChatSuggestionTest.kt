package com.notokens.monolog.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSuggestionTest {
    private val goal = "Confirm if Friday works"

    @Test fun replyCanStartOnTheNextLineAfterAMarkdownLabel() {
        val result = parseChatSuggestion("**REPLY:**\nहाँ, शुक्रवार ठीक है!\n**TIP:** Confirm the time.", goal)
        assertEquals("हाँ, शुक्रवार ठीक है!", result.reply)
        assertEquals("Confirm the time.", result.tip)
    }

    @Test fun emptyAndRepeatedLabelsCannotBeInserted() {
        for (answer in listOf("REPLY:", "REPLY: REPLY:", "Reply: (blank)", "TIP: Say what time works.")) {
            val result = parseChatSuggestion(answer, goal)
            assertEquals("", result.reply)
            assertFalse(result.noReply)
        }
        assertEquals("Ask for a time.", parseChatSuggestion("REPLY:\nTIP: Ask for a time.", goal).tip)
    }

    @Test fun shortUnlabeledRepliesAreUsableAndExplicitNoReplyStaysEmpty() {
        assertEquals("Sure, Friday works for me.", parseChatSuggestion("Sure, Friday works for me.", goal).reply)
        val absent = parseChatSuggestion("NO_REPLY", goal)
        assertTrue(absent.noReply)
        assertEquals("", absent.reply)
    }
}
