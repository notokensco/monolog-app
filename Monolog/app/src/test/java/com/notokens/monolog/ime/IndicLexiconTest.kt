package com.notokens.monolog.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndicLexiconTest {
    @Test
    fun nativeCompletionsAndContinuationsRespectCorpusFrequency() {
        val lexicon = IndicLexicon(
            mapOf("আমি" to 80, "আমাদের" to 22, "আমার" to 65, "আজ" to 30),
            mapOf("আমি" to listOf("আজ")),
            emptyList()
        )
        assertEquals(listOf("আমি", "আমার", "আমাদের"), lexicon.suggestNative("আম"))
        assertEquals(listOf("আজ"), lexicon.suggestNativeNext("আমি"))
    }

    @Test
    fun attestedTransliterationPreservesAlternativesWithoutCommittingGuesses() {
        val lexicon = IndicLexicon(
            mapOf("मैं" to 45, "मई" to 10),
            emptyMap(),
            listOf(RomanWord("mai", "मैं", 20), RomanWord("mai", "मई", 2), RomanWord("aap", "आप", 0)),
            phoneticFallback = { "नमस्ते" }
        )
        assertEquals(listOf("मैं", "मई"), lexicon.suggestRoman("mai"))
        assertEquals("मैं", lexicon.exactRoman("mai"))
        assertNull(lexicon.exactRoman("aap"))
        assertEquals(listOf("नमस्ते"), lexicon.suggestRoman("namaste"))
        assertNull(lexicon.exactRoman("namaste"))
    }

    @Test
    fun regionalSpellingsRespectMeiteiVowelSignsAndOlChikiLetters() {
        assertEquals("mitei", regionalLatinKey("mni", "ꯃꯤꯇꯩ"))
        assertEquals("ei", regionalLatinKey("mni", "ꯑꯩ"))
        assertEquals("santari", regionalLatinKey("sat", "ᱥᱟᱱᱛᱟᱲᱤ"))
        assertNull(regionalLatinKey("sat", "???"))
    }

    @Test
    fun previousNativeWordBreaksAnAmbiguousRomanSpellingTie() {
        val lexicon = IndicLexicon(
            mapOf("কি" to 20, "কী" to 18),
            mapOf("এটা" to listOf("কী")),
            listOf(RomanWord("ki", "কি", 3), RomanWord("ki", "কী", 3))
        )
        assertEquals("কি", lexicon.exactRoman("ki"))
        assertEquals("কী", lexicon.exactRoman("ki", "এটা"))
    }
}
