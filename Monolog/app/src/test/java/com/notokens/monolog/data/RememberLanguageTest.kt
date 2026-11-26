package com.notokens.monolog.data

import org.junit.Assert.*
import org.junit.Test

class RememberLanguageTest {
    @Test fun capturesFactsAndLeavesQuestionsAlone() {
        assertEquals("I kept my phone in the drawer", RememberLanguage.body("Remember I kept my phone in the drawer"))
        assertEquals("the key is upstairs", RememberLanguage.body("Please remember, that the key is upstairs"))
        assertEquals("", RememberLanguage.spokenBody("Remember."))
        assertNull(RememberLanguage.body("Remember me?"))
        assertNull(RememberLanguage.body("Remember that"))
        assertNull(RememberLanguage.body("Remember to buy milk"))
        assertNull(RememberLanguage.body("I remember my phone"))
    }

    @Test fun retrievesMatchingFactsForPersonalQuestions() {
        val question = "Where did I leave my phone?"
        assertTrue(RememberLanguage.isQuestion(question))
        assertFalse(RememberLanguage.isQuestion("phone"))
        assertFalse(RememberLanguage.isQuestion("Where is Mumbai?"))
        assertTrue(RememberLanguage.relevance(RememberLanguage.terms(question), "I kept my phone in a drawer.") > 0)
        assertEquals(0, RememberLanguage.relevance(RememberLanguage.terms(question), "The keys are in the drawer."))
    }
}
