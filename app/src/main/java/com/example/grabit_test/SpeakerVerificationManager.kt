package com.example.grabitTest

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class SpeakerVerificationManager(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val runner = TitaNetOnnxRunner(appContext)
    private val recorder = AudioPreprocessor16k(appContext)
    private val store = VoiceprintStore(appContext)

    suspend fun enrollFromMicrophone(
        recordings: Int = ENROLL_RECORDING_COUNT,
        recordMillis: Int = SpeakerVerificationConfig.RECORD_MILLIS
    ): FloatArray = enrollFromMicrophoneWithStats(recordings, recordMillis).voiceprint

    suspend fun enrollFromMicrophoneWithStats(
        recordings: Int = ENROLL_RECORDING_COUNT,
        recordMillis: Int = SpeakerVerificationConfig.RECORD_MILLIS,
        onSampleComplete: (EnrollmentSampleResult) -> Unit = {}
    ): EnrollmentResult = withContext(Dispatchers.Default) {
        require(recordings >= ENROLL_RECORDING_COUNT) {
            "At least $ENROLL_RECORDING_COUNT enrollment recordings are required."
        }
        val totalStartedAt = SystemClock.elapsedRealtime()
        val embeddings = mutableListOf<FloatArray>()
        val sampleResults = mutableListOf<EnrollmentSampleResult>()
        repeat(recordings) { index ->
            val audio = recorder.recordFixedWindowWithStats(recordMillis)
            val inferenceStartedAt = SystemClock.elapsedRealtime()
            val embedding = runner.extractEmbedding(audio.pcm16kMonoFloat, audio.capturedSamples)
            val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
            embeddings += embedding

            val sampleResult = EnrollmentSampleResult(
                index = index + 1,
                durationMs = audio.durationMs,
                preprocessMs = audio.preprocessMs,
                inferenceMs = inferenceMs,
                embeddingNorm = l2Norm(embedding)
            )
            sampleResults += sampleResult
            Log.i(
                SpeakerVerificationConfig.LOG_TAG,
                "SV_REGISTER_SAMPLE index=${sampleResult.index} durationMs=${sampleResult.durationMs} " +
                    "preprocessMs=${sampleResult.preprocessMs} inferenceMs=${sampleResult.inferenceMs} " +
                    "embeddingNorm=${sampleResult.embeddingNorm}"
            )
            onSampleComplete(sampleResult)
        }
        val voiceprint = TitaNetOnnxRunner.averageAndNormalize(embeddings)
        store.save(voiceprint)
        val result = EnrollmentResult(
            samples = recordings,
            voiceprint = voiceprint,
            voiceprintNorm = l2Norm(voiceprint),
            sampleResults = sampleResults,
            totalMs = SystemClock.elapsedRealtime() - totalStartedAt
        )
        Log.i(
            SpeakerVerificationConfig.LOG_TAG,
            "SV_REGISTER_DONE samples=${result.samples} voiceprintNorm=${result.voiceprintNorm} totalMs=${result.totalMs}"
        )
        result
    }

    suspend fun verifyFromMicrophone(
        threshold: Float = DEFAULT_THRESHOLD,
        recordMillis: Int = SpeakerVerificationConfig.RECORD_MILLIS
    ): VerificationResult = withContext(Dispatchers.Default) {
        val totalStartedAt = SystemClock.elapsedRealtime()
        val voiceprint = store.load()
            ?: return@withContext VerificationResult(
                accepted = false,
                score = Float.NaN,
                threshold = threshold,
                reason = "voiceprint_not_enrolled",
                recordingDurationMs = 0,
                preprocessMs = 0L,
                inferenceMs = 0L,
                totalMs = SystemClock.elapsedRealtime() - totalStartedAt,
                voiceprintRegistered = false
            )
        val audio = recorder.recordFixedWindowWithStats(recordMillis)
        val result = verifyPcm(
            pcm16kMonoFloat = audio.pcm16kMonoFloat,
            voiceprint = voiceprint,
            threshold = threshold,
            validSamples = audio.capturedSamples,
            recordingDurationMs = audio.durationMs,
            preprocessMs = audio.preprocessMs,
            totalStartedAtMs = totalStartedAt
        )
        Log.i(
            SpeakerVerificationConfig.LOG_TAG,
            "SV_VERIFY similarity=${result.score} threshold=${result.threshold} accepted=${result.accepted} " +
                "preprocessMs=${result.preprocessMs} inferenceMs=${result.inferenceMs} totalMs=${result.totalMs}"
        )
        result
    }

    suspend fun verifyRecordedAudio(
        audio: AudioPreprocessor16k.RecordedAudio,
        threshold: Float = DEFAULT_THRESHOLD
    ): VerificationResult = withContext(Dispatchers.Default) {
        val totalStartedAt = SystemClock.elapsedRealtime()
        val voiceprint = store.load()
            ?: return@withContext VerificationResult(
                accepted = false,
                score = Float.NaN,
                threshold = threshold,
                reason = "voiceprint_not_enrolled",
                recordingDurationMs = audio.durationMs,
                preprocessMs = audio.preprocessMs,
                inferenceMs = 0L,
                totalMs = SystemClock.elapsedRealtime() - totalStartedAt,
                voiceprintRegistered = false
            )
        val result = verifyPcm(
            pcm16kMonoFloat = audio.pcm16kMonoFloat,
            voiceprint = voiceprint,
            threshold = threshold,
            validSamples = audio.capturedSamples,
            recordingDurationMs = audio.durationMs,
            preprocessMs = audio.preprocessMs,
            totalStartedAtMs = totalStartedAt
        )
        Log.i(
            SpeakerVerificationConfig.LOG_TAG,
            "SV_VERIFY_RECORDED similarity=${result.score} threshold=${result.threshold} accepted=${result.accepted} " +
                "preprocessMs=${result.preprocessMs} inferenceMs=${result.inferenceMs} totalMs=${result.totalMs}"
        )
        result
    }

    fun verifyPcm(
        pcm16kMonoFloat: FloatArray,
        voiceprint: FloatArray = requireNotNull(store.load()) { "Voiceprint is not enrolled." },
        threshold: Float = DEFAULT_THRESHOLD,
        validSamples: Int = pcm16kMonoFloat.size,
        recordingDurationMs: Int = 0,
        preprocessMs: Long = 0L,
        totalStartedAtMs: Long = SystemClock.elapsedRealtime()
    ): VerificationResult {
        val inferenceStartedAt = SystemClock.elapsedRealtime()
        val embedding = runner.extractEmbedding(pcm16kMonoFloat, validSamples)
        val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartedAt
        val score = TitaNetOnnxRunner.cosine(voiceprint, embedding)
        return VerificationResult(
            accepted = score >= threshold,
            score = score,
            threshold = threshold,
            reason = if (score >= threshold) "accepted" else "rejected",
            recordingDurationMs = recordingDurationMs,
            preprocessMs = preprocessMs,
            inferenceMs = inferenceMs,
            totalMs = SystemClock.elapsedRealtime() - totalStartedAtMs,
            voiceprintRegistered = true
        )
    }

    fun hasVoiceprint(): Boolean = store.hasVoiceprint()

    fun saveVoiceprint(embeddings: List<FloatArray>): FloatArray {
        val voiceprint = TitaNetOnnxRunner.averageAndNormalize(embeddings)
        store.save(voiceprint)
        return voiceprint
    }

    fun clearVoiceprint() {
        store.clear()
    }

    override fun close() {
        runner.close()
    }

    private fun l2Norm(values: FloatArray): Float {
        var sum = 0.0
        for (value in values) sum += value * value
        return sqrt(sum).toFloat()
    }

    data class EnrollmentSampleResult(
        val index: Int,
        val durationMs: Int,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val embeddingNorm: Float
    )

    data class EnrollmentResult(
        val samples: Int,
        val voiceprint: FloatArray,
        val voiceprintNorm: Float,
        val sampleResults: List<EnrollmentSampleResult>,
        val totalMs: Long
    )

    data class VerificationResult(
        val accepted: Boolean,
        val score: Float,
        val threshold: Float,
        val reason: String,
        val recordingDurationMs: Int,
        val preprocessMs: Long,
        val inferenceMs: Long,
        val totalMs: Long,
        val voiceprintRegistered: Boolean
    )

    companion object {
        const val ENROLL_RECORDING_COUNT = SpeakerVerificationConfig.ENROLL_RECORDING_COUNT

        const val DEFAULT_THRESHOLD = SpeakerVerificationConfig.DEFAULT_THRESHOLD

        const val STRICT_FA0_THRESHOLD = SpeakerVerificationConfig.FA0_THRESHOLD
    }
}
