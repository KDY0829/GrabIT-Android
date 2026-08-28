package com.example.grabitTest

import android.content.Context
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Lightweight, rule-based VAD used before speaker verification and STT.
 *
 * The captured PCM is returned with the analysis so a successful VAD gate can
 * reuse it for speaker verification without opening the microphone twice.
 */
class VoiceActivityDetector(context: Context) {

    private val recorder = AudioPreprocessor16k(context.applicationContext)

    suspend fun recordAndAnalyze(
        recordMillis: Int = AudioPreprocessor16k.DEFAULT_RECORD_MILLIS
    ): VadCapture {
        val audio = recorder.recordFixedWindowWithStats(recordMillis)
        return VadCapture(audio = audio, result = analyze(audio))
    }

    fun analyze(audio: AudioPreprocessor16k.RecordedAudio): VadResult {
        val validSamples = audio.capturedSamples.coerceIn(0, audio.pcm16kMonoFloat.size)
        if (validSamples < FRAME_SAMPLES) {
            return VadResult(
                isSpeech = false,
                reason = "insufficient_audio",
                rmsDb = SILENCE_DB,
                speechFrameRatio = 0f,
                capturedSamples = validSamples,
                durationMs = audio.durationMs
            )
        }

        var sumSquares = 0.0
        for (index in 0 until validSamples) {
            val sample = audio.pcm16kMonoFloat[index].toDouble()
            sumSquares += sample * sample
        }
        val rmsDb = toDb(sqrt(sumSquares / validSamples))

        val frameCount = validSamples / FRAME_SAMPLES
        var speechFrameCount = 0
        var consecutiveSpeechFrames = 0
        var longestSpeechRun = 0
        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * FRAME_SAMPLES
            var frameSumSquares = 0.0
            for (sampleIndex in start until start + FRAME_SAMPLES) {
                val sample = audio.pcm16kMonoFloat[sampleIndex].toDouble()
                frameSumSquares += sample * sample
            }
            val frameRmsDb = toDb(sqrt(frameSumSquares / FRAME_SAMPLES))
            if (frameRmsDb >= MIN_SPEECH_FRAME_RMS_DB) {
                speechFrameCount++
                consecutiveSpeechFrames++
                longestSpeechRun = maxOf(longestSpeechRun, consecutiveSpeechFrames)
            } else {
                consecutiveSpeechFrames = 0
            }
        }

        val speechFrameRatio = speechFrameCount.toFloat() / frameCount
        val reason = when {
            rmsDb < MIN_OVERALL_RMS_DB -> "low_rms"
            speechFrameCount < MIN_SPEECH_FRAMES -> "too_few_speech_frames"
            longestSpeechRun < MIN_CONSECUTIVE_SPEECH_FRAMES -> "transient_input"
            speechFrameRatio < MIN_SPEECH_FRAME_RATIO -> "insufficient_speech_frame_ratio"
            else -> "speech_detected"
        }
        return VadResult(
            isSpeech = reason == "speech_detected",
            reason = reason,
            rmsDb = rmsDb,
            speechFrameRatio = speechFrameRatio,
            capturedSamples = validSamples,
            durationMs = audio.durationMs
        )
    }

    private fun toDb(rms: Double): Float {
        if (rms <= RMS_EPSILON) return SILENCE_DB
        return (20.0 * log10(rms)).toFloat().coerceAtLeast(SILENCE_DB)
    }

    data class VadCapture(
        val audio: AudioPreprocessor16k.RecordedAudio,
        val result: VadResult
    )

    data class VadResult(
        val isSpeech: Boolean,
        val reason: String,
        val rmsDb: Float,
        val speechFrameRatio: Float,
        val capturedSamples: Int,
        val durationMs: Int
    )

    companion object {
        const val FRAME_DURATION_MS = 20
        const val SAMPLE_RATE = TitaNetOnnxRunner.SAMPLE_RATE
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_DURATION_MS / 1_000

        // Tune from VAD_GATE Logcat output on target devices.
        const val MIN_SPEECH_FRAME_RMS_DB = -38f
        const val MIN_OVERALL_RMS_DB = -42f
        const val MIN_SPEECH_FRAME_RATIO = 0.25f
        const val MIN_SPEECH_FRAMES = 15
        const val MIN_CONSECUTIVE_SPEECH_FRAMES = 5

        private const val RMS_EPSILON = 1.0e-8
        private const val SILENCE_DB = -120f
    }
}
