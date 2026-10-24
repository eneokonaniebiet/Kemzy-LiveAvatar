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
        // Signals below -45 dBFS are treated as microphone/background noise.
        private const val NOISE_GATE_THRESHOLD_DB = -45.0f
        // Signals at -10 dBFS and above map to a fully open jaw coefficient.
        private const val MAX_SPEECH_VOLUME_DB = -10.0f
        private const val PCM_FULL_SCALE = 32768.0
        private const val RMS_SAFETY_FLOOR = 1.0e-5
    }

    fun calculateJawOpenCoefficient(pcmBuffer: ShortArray, sampleCount: Int): Float {
        val samplesRead = sampleCount.coerceIn(0, pcmBuffer.size)
        if (samplesRead <= 0) return 0.0f

        var squaredSum = 0.0
        for (i in 0 until samplesRead) {
            val sample = pcmBuffer[i].toDouble()
            squaredSum += sample * sample
        }

        val rms = sqrt(squaredSum / samplesRead)
        if (rms < RMS_SAFETY_FLOOR) return 0.0f

        val dbfs = (20.0 * log10(rms / PCM_FULL_SCALE)).toFloat()
        if (!dbfs.isFinite() || dbfs < NOISE_GATE_THRESHOLD_DB) return 0.0f

        val range = MAX_SPEECH_VOLUME_DB - NOISE_GATE_THRESHOLD_DB
        if (range <= 0.0f) return 0.0f

        return ((dbfs - NOISE_GATE_THRESHOLD_DB) / range).coerceIn(0.0f, 1.0f)
    }
}
