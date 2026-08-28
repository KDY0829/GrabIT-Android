package com.example.grabitTest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioPreprocessor16k(private val context: Context) {

    suspend fun recordFixedWindow(
        millis: Int = DEFAULT_RECORD_MILLIS
    ): FloatArray = recordFixedWindowWithStats(millis).pcm16kMonoFloat

    suspend fun recordFixedWindowWithStats(
        millis: Int = DEFAULT_RECORD_MILLIS
    ): RecordedAudio = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val pcm = recordFixedWindowBlocking(millis)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        RecordedAudio(
            pcm16kMonoFloat = pcm.first,
            durationMs = millis,
            capturedSamples = pcm.second,
            preprocessMs = elapsedMs
        )
    }

    private fun recordFixedWindowBlocking(millis: Int): Pair<FloatArray, Int> {
        require(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        ) { "RECORD_AUDIO permission is required for speaker verification." }

        val sampleCount = TitaNetOnnxRunner.SAMPLE_RATE * millis / 1000
        val minBuffer = AudioRecord.getMinBufferSize(
            TitaNetOnnxRunner.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val readBufferSize = maxOf(minBuffer, sampleCount * BYTES_PER_SAMPLE)
        val pcm = ShortArray(sampleCount)
        var offset = 0

        @Suppress("MissingPermission")
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(TitaNetOnnxRunner.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(readBufferSize)
            .build()

        try {
            recorder.startRecording()
            while (offset < sampleCount) {
                val read = recorder.read(pcm, offset, sampleCount - offset)
                if (read <= 0) break
                offset += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }

        val floats = FloatArray(maxOf(sampleCount, TitaNetOnnxRunner.FIXED_SAMPLE_COUNT))
        val copyLength = minOf(offset, floats.size)
        for (i in 0 until copyLength) {
            floats[i] = (pcm[i] / 32768.0f).coerceIn(-1f, 1f)
        }
        return floats to offset
    }

    data class RecordedAudio(
        val pcm16kMonoFloat: FloatArray,
        val durationMs: Int,
        val capturedSamples: Int,
        val preprocessMs: Long
    )

    companion object {
        const val DEFAULT_RECORD_MILLIS = 2_000
        private const val BYTES_PER_SAMPLE = 2
    }
}
