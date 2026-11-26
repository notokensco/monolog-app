package com.notokens.monolog.ime.vox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoxTextTest {
    @Test fun capturesOnlyTheTextAfterAnExplicitNoteCommand() {
        assertEquals("Buy coffee tomorrow", VoxText.noteBody("Add this to notes that Buy coffee tomorrow"))
        assertEquals("Buy coffee tomorrow", VoxText.noteBody("Add this to notes, that Buy coffee tomorrow"))
        assertEquals("", VoxText.noteBody("Add this to my notes."))
        assertEquals("Second meeting", VoxText.noteBody("Add a note: Second meeting"))
        assertNull(VoxText.noteBody("Please don't add this to notes"))
        assertNull(VoxText.noteBody("Add this to the notes app"))
    }

    @Test fun addsSupportedPunctuationWithoutChangingWordOrder() {
        assertEquals("How are you?", VoxText.finish("How are you", "en-IN"))
        assertEquals("Oh no, I can't go!", VoxText.finish("Oh no, i cant go", "en"))
        assertEquals("What will happen?", VoxText.finish("What will happen question mark", "en"))
        assertEquals("क्या आप आ रहे हैं?", VoxText.finish("क्या आप आ रहे हैं", "hi-IN"))
        assertEquals("Aap kaise ho?", VoxText.finish("Aap kaise ho", "hi-Latn"))
        assertEquals("তুমি কোথায়?", VoxText.finish("তুমি কোথায়", "bn-IN"))
        assertEquals("I wonder what happened", VoxText.finish("I wonder what happened", "en"))
        assertEquals("Quelle surprise!", VoxText.finish("Quelle surprise!", "fr-FR"))
    }

    @Test fun stripsHesitationsAndPreservesMeaningfulInterjectionsAndNames() {
        assertEquals("Oh no, Umang is here", PronunciationAlternatives.cleanFillerPhonemes("Oh no, umm uh Umang is here"))
        assertEquals("hum kal milenge", PronunciationAlternatives.cleanFillerPhonemes("hum uhh kal milenge"))
    }
}
