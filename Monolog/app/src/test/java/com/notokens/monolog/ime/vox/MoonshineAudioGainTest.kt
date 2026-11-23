package com.notokens.monolog.ime.vox

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineAudioGainTest {
    @Test
    fun liftsQuietSpeechButLimitsTheNextLoudFrame() {
        val gain = MoonshineAudioGain()
        val quiet = FloatArray(1024) { if (it % 2 == 0) .018f else -.018f }
        val boosted = gain.apply(quiet.copyOf())
        assertTrue(abs(boosted.last()) > abs(quiet.last()) * 2)
        val loud = gain.apply(FloatArray(1024) { if (it % 2 == 0) 1f else -1f })
        assertTrue(loud.all { abs(it) <= .921f })
    }

    @Test
    fun leavesNearSilenceAtItsOriginalLevelAfterSpeech() {
        val gain = MoonshineAudioGain()
        gain.apply(FloatArray(1024) { .02f })
        val roomNoise = FloatArray(1024) { .001f }
        assertArrayEquals(roomNoise, gain.apply(roomNoise.copyOf()), 0f)
    }
}
