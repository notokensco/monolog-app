package com.notokens.monolog.ime.vox

import kotlin.math.abs
import kotlin.math.sqrt

/** Levels the model input without amplifying near-silence or clipping speech peaks. */
internal class MoonshineAudioGain {
    private var gain = 1f

    fun apply(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        var peak = 0f
        var power = 0f
        for (sample in samples) {
            peak = maxOf(peak, abs(sample))
            power += sample * sample
        }
        val rms = sqrt(power / samples.size)
        if (rms < .004f) {
            gain = 1f
            return samples
        }

        val headroom = .92f / peak
        val target = (.085f / rms).coerceIn(1f, 3.5f)
        val startingGain = minOf(gain, headroom)
        val endingGain = minOf(startingGain + (target - startingGain) * .48f, headroom)
        for (index in samples.indices) {
            val amount = (index + 1f) / samples.size
            samples[index] *= startingGain + (endingGain - startingGain) * amount
        }
        gain = endingGain
        return samples
    }
}
