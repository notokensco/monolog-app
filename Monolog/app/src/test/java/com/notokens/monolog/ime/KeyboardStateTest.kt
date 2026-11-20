package com.notokens.monolog.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardStateTest {

    @Test
    fun testInitialState() {
        val state = KeyboardState()
        assertEquals(KeyboardLayer.LETTERS, state.layer)
        assertEquals(ShiftState.OFF, state.shiftState)
        assertFalse(state.shiftState.isUppercase)
    }

    @Test
    fun testShiftStateCycling() {
        var state = KeyboardState()

        state = state.cycleShift()
        assertEquals(ShiftState.ONCE, state.shiftState)
        assertTrue(state.shiftState.isUppercase)

        state = state.cycleShift()
        assertEquals(ShiftState.CAPS_LOCK, state.shiftState)
        assertTrue(state.shiftState.isUppercase)

        state = state.cycleShift()
        assertEquals(ShiftState.OFF, state.shiftState)
        assertFalse(state.shiftState.isUppercase)
    }

    @Test
    fun testOnLetterTypedResetsShiftOnce() {
        var state = KeyboardState(shiftState = ShiftState.ONCE)
        state = state.onLetterTyped()
        assertEquals(ShiftState.OFF, state.shiftState)

        // In caps lock, typing does not reset caps lock
        var capsState = KeyboardState(shiftState = ShiftState.CAPS_LOCK)
        capsState = capsState.onLetterTyped()
        assertEquals(ShiftState.CAPS_LOCK, capsState.shiftState)

        // In shift off, typing remains off
        var offState = KeyboardState(shiftState = ShiftState.OFF)
        offState = offState.onLetterTyped()
        assertEquals(ShiftState.OFF, offState.shiftState)
    }

    @Test
    fun testLayerToggling() {
        var state = KeyboardState()
        assertEquals(KeyboardLayer.LETTERS, state.layer)

        state = state.toggleSymbols()
        assertEquals(KeyboardLayer.SYMBOLS, state.layer)

        state = state.toggleMoreSymbols()
        assertEquals(KeyboardLayer.MORE_SYMBOLS, state.layer)

        state = state.toggleMoreSymbols()
        assertEquals(KeyboardLayer.SYMBOLS, state.layer)

        state = state.switchToLetters()
        assertEquals(KeyboardLayer.LETTERS, state.layer)
    }
}
