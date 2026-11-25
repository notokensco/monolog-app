package com.notokens.monolog.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnglishContractionTest {
    @Test fun suppliesMissingApostrophesAndPreservesCase() {
        assertEquals("I'm", englishContraction("Im", ""))
        assertEquals("I'm", englishContraction("im", "yes, "))
        assertEquals("can't", englishContraction("cant", "I "))
        assertEquals("Can't", englishContraction("Cant", ""))
        assertEquals("doesn't", englishContraction("doesnt", "it "))
    }

    @Test fun leavesAcronymsAndAmbiguousOrdinaryWordsAlone() {
        assertNull(englishContraction("IM", "send an "))
        assertNull(englishContraction("cant", "the "))
        assertNull(englishContraction("wont", "his "))
        assertNull(englishContraction("well", "I'm doing "))
        assertNull(englishContraction("its", "on "))
    }
}
