package com.notokens.monolog.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeTypingTest {
    private val decoder = SwipeDecoder(listOf("hello", "hero", "ho", "help", "world", "word", "that", "what"))

    @Test
    fun repeatedLettersNeedOnlyOneVisit() {
        assertEquals("hello", decoder.decode(trace("helo")))
        assertEquals("that", decoder.decode(trace("that")))
    }

    @Test
    fun pathShapeDistinguishesWordsWithTheSameEndpoints() {
        assertEquals("hero", decoder.decode(trace("hero")))
        assertEquals("world", decoder.decode(trace("world")))
        assertEquals("word", decoder.decode(trace("word")))
    }

    @Test
    fun nearbyEndpointsAndUnevenSamplingStillMatch() {
        val path = trace("what")
        val first = path.first()
        val last = path.last()
        val imperfect = listOf(first.copy(x = first.x - .4f)) + path + last.copy(y = last.y + .3f)
        assertEquals("what", decoder.decode(imperfect))
    }

    @Test
    fun tapsAndNonletterKeysDoNotBecomeSwipes() {
        assertNull(decoder.decode(trace("h")))
        assertNull(decoder.decode(trace("hh")))
        assertNull(QwertyLayout.letterAt(SwipePoint(.5f, 3f)))
        assertNull(QwertyLayout.letterAt(SwipePoint(9.5f, 3f)))
        assertEquals('z', QwertyLayout.letterAt(QwertyLayout.center('z')))
    }

    @Test
    fun swipeWordUsesTheExistingShiftStateAndAddsOnlyNeededSpaces() {
        assertEquals("Hello", formatSwipeWord("hello", ShiftState.ONCE, null))
        assertEquals(" world", formatSwipeWord("world", ShiftState.OFF, 'o'))
        assertEquals("WORLD", formatSwipeWord("world", ShiftState.CAPS_LOCK, ' '))
    }

    @Test
    fun commonInflectionsAndContractionsAreRecognized() {
        val decoder = SwipeDecoder(swipeWordForms("work", "ADJSG") + swipeWordForms("try", "SD") + "don't")
        assertEquals("working", decoder.decode(trace("working")))
        assertEquals("tried", decoder.decode(trace("tried")))
        assertEquals("don't", decoder.decode(trace("dont")))
    }

    private fun trace(letters: String) = letters.map(QwertyLayout::center)
}
