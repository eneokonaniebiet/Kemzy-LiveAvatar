package com.kemzy.liveavatar.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local PCM capture only. The callback receives copied samples and must process them
 * quickly; this class does not upload or persist microphone data.
 * Caller must request RECORD_AUDIO permission at runtime before starting.
 */
class KemzyVoiceCaptureDriver(
    private val context: Context,
    private val onAudioChunkAvailable: (ShortArray) -> Unit
) {
    companion object {
        private const val TAG = "KemzyAudioDriver"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val SAMPLES_PER_CHUNK = 1024
    }

    private val isRecording = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recordingThread: Thread? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startVoiceCaptureStream(): Boolean {
        if (isRecording.get()) return true
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission has not been granted")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            Log.e(TAG, "AudioRecord returned invalid minimum buffer size: $minBufferSize")
            return false
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                maxOf(minBufferSize * 2, SAMPLES_PER_CHUNK * 2 * 2)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord construction failed", e)
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            recorder.release()
            return false
        }

        audioRecord = recorder
        return try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "AudioRecord did not enter recording state")
                recorder.release()
                audioRecord = null
                false
            } else {
                isRecording.set(true)
                recordingThread = Thread({ captureLoop(recorder) }, "KemzyAudioCaptureThread").apply {
                    isDaemon = true
                    start()
                }
                Log.i(TAG, "Local PCM capture started: 16kHz mono PCM16")
                true
            }
        } catch (e: Exception) {
            isRecording.set(false)
            audioRecord = null
            recorder.release()
            Log.e(TAG, "Unable to start microphone capture", e)
            false
        }
    }

    private fun captureLoop(recorder: AudioRecord) {
        val buffer = ShortArray(SAMPLES_PER_CHUNK)
        try {
            while (isRecording.get()) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                when {
                    count > 0 -> {
                        try {
                            onAudioChunkAvailable(buffer.copyOf(count))
                        } catch (e: Exception) {
                            Log.e(TAG, "PCM consumer callback failed", e)
                        }
                    }
                    count == 0 -> Unit
                    count == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.e(TAG, "AudioRecord device became unavailable")
                        break
                    }
                    else -> Log.w(TAG, "AudioRecord read error: $count")
                }
            }
        } finally {
            isRecording.set(false)
            Log.i(TAG, "PCM capture loop stopped")
        }
    }

    @Synchronized
    fun stopVoiceCaptureStream() {
        isRecording.set(false)
        val recorder = audioRecord
        try {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop reported: ${e.message}")
        }

        val thread = recordingThread
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(750)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord release reported: ${e.message}")
        }
        audioRecord = null
        recordingThread = null
        Log.i(TAG, "Local microphone resources released")
    }
}
