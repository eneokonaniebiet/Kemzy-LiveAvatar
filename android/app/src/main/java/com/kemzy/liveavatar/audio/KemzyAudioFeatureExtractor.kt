package com.kemzy.liveavatar.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight PCM16 RMS envelope extractor. The extractor keeps no history and
 * allocates no objects per call. Its output is an energy-based jaw proxy, not
 * phoneme/viseme recognition; calibrate thresholds against the target device.
 */
class KemzyAudioFeatureExtractor {
    companion object {
        // PCM16 full scale is 32768. Thresholds are expressed in dBFS.
        private const val NOISE_FLOOR_DBFS = -55.0f
        private const val MAX_SPEECH_DBFS = -18.0f
        private const val PCM_FULL_SCALE = 32768.0
    }

    fun calculateJawOpenCoefficient(pcmBuffer: ShortArray, sampleCount: Int): Float {
        val count = sampleCount.coerceIn(0, pcmBuffer.size)
        if (count == 0) return 0.0f

        var sumSquares = 0.0
        for (i in 0 until count) {
            val sample = pcmBuffer[i].toDouble()
            sumSquares += sample * sample
        }

        val rms = sqrt(sumSquares / count)
        if (rms <= 0.0) return 0.0f

        val dbfs = (20.0 * log10(rms / PCM_FULL_SCALE)).toFloat()
        return ((dbfs - NOISE_FLOOR_DBFS) /
            (MAX_SPEECH_DBFS - NOISE_FLOOR_DBFS)).coerceIn(0.0f, 1.0f)
    }
}
