package com.notokens.monolog.ime.vox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoxStateTest {

    @Test
    fun testDefaultState() {
        val state = VoxState()
        assertEquals(VoxStatus.IDLE, state.status)
        assertEquals("", state.transcript)
        assertEquals(0f, state.rmsLevel, 0.001f)
    }

    @Test
    fun testStateTransitionToListening() {
        val state = VoxState(status = VoxStatus.LISTENING, rmsLevel = 0.5f)
        assertEquals(VoxStatus.LISTENING, state.status)
        assertEquals(0.5f, state.rmsLevel, 0.001f)
    }

    @Test
    fun testTranscriptUpdate() {
        var state = VoxState(status = VoxStatus.LISTENING)
        state = state.copy(transcript = "Meeting at four")
        assertEquals("Meeting at four", state.transcript)

        state = state.copy(transcript = "${state.transcript} with the team")
        assertEquals("Meeting at four with the team", state.transcript)
    }
}
