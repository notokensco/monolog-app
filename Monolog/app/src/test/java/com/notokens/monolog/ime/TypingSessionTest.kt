package com.notokens.monolog.ime

import com.notokens.monolog.data.LearnedTransition
import com.notokens.monolog.data.TypingProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingSessionTest {
    @Test fun learnsTheWordTypedAfterDeletingAnEarlierWord() {
        val session = TypingSession()
        session.trackBackspace("Call Sa")
        session.trackBackspace("Call ")
        session.type("Sameera")
        session.confirmCorrection("Call Sameera")
        session.type(" ")
        assertEquals(mapOf("call sameera" to 1), session.take().transitions)
    }

    @Test fun keepsUntouchedLettersAndSupportsASingleLetterPredecessor() {
        val session = TypingSession()
        session.trackBackspace("I pla")
        session.type("nned")
        session.confirmCorrection("I planned")
        assertEquals(mapOf("i planned" to 1), session.take().transitions)
    }

    @Test fun rejectsAnUnchangedOrStaleEditorSample() {
        val session = TypingSession()
        session.trackBackspace("Call Sa")
        session.confirmCorrection("Call Sa")
        session.type("m")
        session.confirmCorrection("Text Sam")
        assertTrue(session.take().transitions.isEmpty())
    }

    @Test fun encryptedProfilePayloadCanReadOlderBackupsAndMergeTransitions() {
        assertTrue(TypingProfile.decode(JSONObject()).transitions.isEmpty())
        val first = TypingProfile(transitions = mapOf("call sam" to LearnedTransition(1, 10)))
        val second = TypingProfile(transitions = mapOf("call sam" to LearnedTransition(3, 20)))
        val decoded = TypingProfile.decode(second.encode())
        assertEquals(second, decoded)
        assertEquals(second, first.merge(decoded).merge(decoded))
    }
}
