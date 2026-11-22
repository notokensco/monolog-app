package com.notokens.monolog.ime.vox

/** Sample-count based voice activity detection. Captions are provisional until the full clip is decoded. */
internal class SpeechEndpoint {
    enum class Event { Continue, Preview, Finished }

    private var elapsedMs = 0
    private var silenceMs = 0
    private var voicedMs = 0
    private var lastPreviewMs = 0
    private var noiseFloor = .003f

    fun accept(bytes: ByteArray, count: Int): Event {
        val length = count / 2
        if (length <= 0) return Event.Continue
        val duration = (length * 1000 / VoiceRecorder.RATE).coerceAtLeast(1)
        var power = 0.0
        for (i in 0 until length) {
            val raw = (bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)
            val normalized = raw / 32768.0
            power += normalized * normalized
        }
        val level = kotlin.math.sqrt(power / length).toFloat()
        elapsedMs += duration
        if (level >= maxOf(.010f, noiseFloor * 2.8f)) {
            voicedMs += duration
            silenceMs = 0
        } else {
            noiseFloor = (noiseFloor * .94f + level * .06f).coerceIn(.001f, .028f)
            silenceMs += duration
        }
        if ((voicedMs >= 200 && silenceMs >= 950) || (voicedMs < 200 && elapsedMs >= 6500)) {
            return Event.Finished
        }
        if (voicedMs >= 550 && elapsedMs - lastPreviewMs >= 1800 && silenceMs < 500) {
            lastPreviewMs = elapsedMs
            return Event.Preview
        }
        return Event.Continue
    }
}
