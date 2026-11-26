package com.notokens.monolog.ime.vox

import com.notokens.monolog.ime.NativeTyping

/** Counts recorded samples so slow inference or UI frames cannot turn a brief pause into an endpoint. */
internal class SpeechPause(private val stopAfterMs: Int) {
    var quietMs = 0
        private set
    var voicedMs = 0
        private set
    var elapsedMs = 0
        private set
    private var noise = .0025f

    fun shouldStop(extended: Boolean = false): Boolean {
        val duration = if (extended) maxOf(stopAfterMs, 9_000) else stopAfterMs
        return if (voicedMs >= 180) quietMs >= duration else elapsedMs >= if (extended) 15_000 else 9_000
    }

    fun accept(pcm: ByteArray, count: Int): Boolean {
        val ms = count * 1000 / (VoiceRecorder.RATE * 2)
        val amplitude = NativeTyping.voiceRms(pcm, count)
        elapsedMs += ms
        val voice = amplitude > maxOf(.0045f, noise * 3f)
        if (voice) {
            voicedMs += ms
            quietMs = 0
        } else {
            noise = (noise * .95f + amplitude * .05f).coerceIn(.0006f, .02f)
            quietMs += ms
        }
        return voice
    }
}
