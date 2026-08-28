package com.example.grabitTest

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Android SpeechRecognizer 기반 STT(음성→텍스트) 매니저
 */
class STTManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onErrorWithCode: ((String, Int) -> Unit)? = null,
    private val onListeningChanged: (Boolean) -> Unit = {},
    private val onPartialResult: ((String) -> Unit)? = null,
    private val playStartBeep: ((onDone: () -> Unit) -> Unit),
    /** 음성 인식이 끝난 원인(디버깅용). onEndOfSpeech/onError/onResults 시 로그 + 이 콜백으로 전달 */
    private val onListeningEndedReason: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "STT"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var audioManager: AudioManager? = null
    private var lastPartialText: String? = null
    private var sttRequestSeq = 0
    private var currentRequestId = 0
    private var currentRequestStartedAtMs = 0L
    private var currentBeepStartedAtMs = 0L
    private var isRecordedAudioRequest = false
    private var recordedAudioReadFd: ParcelFileDescriptor? = null

    fun init(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "STT 초기화 실패: SpeechRecognizer 사용 불가")
            onError("이 기기에서는 음성 인식을 지원하지 않습니다.")
            return false
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createRecognitionListener())
        }
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return true
    }
    
    /**
     * 음성 인식 시작. 삐 소리만 재생 후 음성녹음 시작.
     */
    fun startListening() {
        startListeningInternal(playBeep = true)
    }

    fun startListeningWithoutBeep() {
        startListeningInternal(playBeep = false)
    }

    private fun startListeningInternal(playBeep: Boolean) {
        // 비프 재생 전부터 "듣는 중" 표시 (엔진이 실제로 켜지기 전에도 사용자 피드백)
        onListeningChanged(true)
        currentRequestId = ++sttRequestSeq
        currentRequestStartedAtMs = System.currentTimeMillis()
        currentBeepStartedAtMs = 0L
        isRecordedAudioRequest = false
        Log.i(TAG, "STT_START_REQUEST requestId=$currentRequestId")
        val sr = speechRecognizer
        if (sr == null) {
            Log.e(TAG, "STT 시작 실패: speechRecognizer=null")
            onListeningChanged(false)
            onError("SpeechRecognizer가 초기화되지 않았습니다.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "STT 시작 실패: RECORD_AUDIO 권한 없음")
            onListeningChanged(false)
            onError("마이크 권한이 필요합니다.")
            return
        }
        if (playBeep) {
            currentBeepStartedAtMs = System.currentTimeMillis()
            Log.i(TAG, "STT_BEEP_START requestId=$currentRequestId elapsedSinceRequestMs=${currentBeepStartedAtMs - currentRequestStartedAtMs}")
            playStartBeep {
                val now = System.currentTimeMillis()
                Log.i(TAG, "STT_BEEP_DONE requestId=$currentRequestId beepElapsedMs=${now - currentBeepStartedAtMs} elapsedSinceRequestMs=${now - currentRequestStartedAtMs}")
                doStartListening()
            }
        } else {
            Log.i(TAG, "STT_BEEP_SKIPPED requestId=$currentRequestId")
            doStartListening()
        }
    }

    fun startListeningFromAudio(audio: AudioPreprocessor16k.RecordedAudio): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.w(TAG, "STT_RECORDED_AUDIO_UNSUPPORTED sdk=${Build.VERSION.SDK_INT}")
            return false
        }
        onListeningChanged(true)
        currentRequestId = ++sttRequestSeq
        currentRequestStartedAtMs = System.currentTimeMillis()
        currentBeepStartedAtMs = 0L
        isRecordedAudioRequest = true
        Log.i(TAG, "STT_START_RECORDED_AUDIO_REQUEST requestId=$currentRequestId capturedSamples=${audio.capturedSamples} durationMs=${audio.durationMs}")
        val sr = speechRecognizer
        if (sr == null) {
            Log.e(TAG, "STT 시작 실패: speechRecognizer=null")
            onListeningChanged(false)
            onError("SpeechRecognizer가 초기화되지 않았습니다.")
            return true
        }

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: Exception) {
            Log.e(TAG, "STT recorded audio pipe 생성 실패", e)
            onListeningChanged(false)
            onError("녹음 음성 인식 준비에 실패했습니다.")
            return true
        }
        val readFd = pipe[0]
        val writeFd = pipe[1]
        runCatching { recordedAudioReadFd?.close() }
        recordedAudioReadFd = readFd
        thread(name = "stt-recorded-audio-writer") {
            try {
                FileOutputStream(writeFd.fileDescriptor).use { output ->
                    output.write(audio.toPcm16LeBytes())
                    output.flush()
                }
            } catch (e: IOException) {
                Log.w(TAG, "STT_RECORDED_AUDIO_WRITE_ABORTED requestId=$currentRequestId msg=${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "STT_RECORDED_AUDIO_WRITE_FAILED requestId=$currentRequestId", e)
            } finally {
                runCatching { writeFd.close() }
            }
        }

        try {
            if (isListening) sr.stopListening()
        } catch (_: Exception) {}
        isListening = false
        audioManager?.requestAudioFocus(
            { },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        val intent = createRecognizerIntent().apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TitaNetOnnxRunner.SAMPLE_RATE)
        }
        return try {
            sr.startListening(intent)
            isListening = true
            onListeningChanged(true)
            Log.i(TAG, "STT_START_RECORDED_AUDIO requestId=$currentRequestId elapsedSinceRequestMs=${System.currentTimeMillis() - currentRequestStartedAtMs}")
            true
        } catch (e: Exception) {
            runCatching { readFd.close() }
            if (recordedAudioReadFd === readFd) recordedAudioReadFd = null
            runCatching { writeFd.close() }
            Log.e(TAG, "STT recorded audio 시작 예외", e)
            isListening = false
            onListeningChanged(false)
            onError("녹음 음성 인식 시작 실패: ${e.message}")
            true
        }
    }

    private fun doStartListening() {
        val sr = speechRecognizer ?: run {
            onListeningChanged(false)
            return
        }
        try {
            if (isListening) sr.stopListening()
        } catch (_: Exception) {}
        isListening = false
        audioManager?.requestAudioFocus(
            { },
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // true면 오프라인 한국어 팩이 없는 기기에서 ERROR_LANGUAGE_UNAVAILABLE(13)이 자주 난다. 네트워크 인식 허용.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
        }
        try {
            sr.startListening(intent)
            isListening = true
            onListeningChanged(true)
            val now = System.currentTimeMillis()
            Log.i(TAG, "STT_START_LISTENING requestId=$currentRequestId elapsedSinceRequestMs=${now - currentRequestStartedAtMs}")
        } catch (e: Exception) {
            Log.e(TAG, "STT 시작 예외", e)
            isListening = false
            onListeningChanged(false)
            onError("음성 인식 시작 실패: ${e.message}")
        }
    }

    private fun createRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
        }

    private fun AudioPreprocessor16k.RecordedAudio.toPcm16LeBytes(): ByteArray {
        val sampleCount = capturedSamples.coerceIn(0, pcm16kMonoFloat.size)
        val bytes = ByteArray(sampleCount * 2)
        var byteIndex = 0
        for (i in 0 until sampleCount) {
            val sample = (pcm16kMonoFloat[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bytes[byteIndex++] = (sample.toInt() and 0xff).toByte()
            bytes[byteIndex++] = ((sample.toInt() shr 8) and 0xff).toByte()
        }
        return bytes
    }

    private fun closeRecordedAudioReadFd() {
        runCatching { recordedAudioReadFd?.close() }
        recordedAudioReadFd = null
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            isListening = false
            onListeningChanged(false)
        } catch (e: Exception) {
            Log.e(TAG, "stopListening 실패", e)
        }
    }

    fun cancelListening() {
        try {
            speechRecognizer?.cancel()
            isListening = false
            onListeningChanged(false)
        } catch (e: Exception) {
            Log.e(TAG, "cancelListening 실패", e)
        }
    }

    fun isListening(): Boolean = isListening

    /** NO_MATCH/타임아웃 전 마지막 부분 인식 텍스트 (사용자에게 뭐가 들렸는지 보여주기용) */
    fun getLastPartialText(): String? = lastPartialText

    fun release() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
            onListeningChanged(false)
            audioManager?.abandonAudioFocus(null)
        } catch (e: Exception) {
            Log.e(TAG, "release 실패", e)
        }
    }

    private fun createRecognitionListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.i(TAG, "STT_READY_FOR_SPEECH requestId=$currentRequestId elapsedSinceRequestMs=${System.currentTimeMillis() - currentRequestStartedAtMs}")
        }
        override fun onBeginningOfSpeech() {
            Log.i(TAG, "STT_BEGINNING_OF_SPEECH requestId=$currentRequestId elapsedSinceRequestMs=${System.currentTimeMillis() - currentRequestStartedAtMs}")
        }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.i(TAG, "STT_END_OF_SPEECH requestId=$currentRequestId elapsedSinceRequestMs=${System.currentTimeMillis() - currentRequestStartedAtMs}")
            onListeningEndedReason("onEndOfSpeech")
            isListening = false
            isRecordedAudioRequest = false
            closeRecordedAudioReadFd()
            onListeningChanged(false)
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "오디오 에러"
                SpeechRecognizer.ERROR_CLIENT -> "클라이언트 에러"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 부족"
                SpeechRecognizer.ERROR_NETWORK -> "네트워크 에러"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 타임아웃"
                SpeechRecognizer.ERROR_NO_MATCH -> "인식 결과 없음"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중"
                SpeechRecognizer.ERROR_SERVER -> "서버 에러"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말 없음 타임아웃"
                // API 31+ (값 12, 13): 언어 팩/오프라인 인식 관련
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                    "이 기기의 음성 인식에서 한국어를 지원하지 않습니다."
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "한국어 음성 인식 데이터를 쓸 수 없습니다. 인터넷 연결을 확인하거나, 설정에서 ‘Google 음성 입력’ 한국어를 내려받아 주세요."
                else -> "알 수 없는 에러 (코드: $error)"
            }
            onListeningEndedReason("onError($error)")
            Log.w(TAG, "STT_ERROR requestId=$currentRequestId code=$error msg=$msg elapsedSinceRequestMs=${System.currentTimeMillis() - currentRequestStartedAtMs}")
            isListening = false
            onListeningChanged(false)
            if (isRecordedAudioRequest && error == SpeechRecognizer.ERROR_CLIENT) {
                isRecordedAudioRequest = false
                closeRecordedAudioReadFd()
                Log.w(TAG, "STT_RECORDED_AUDIO_CLIENT_ERROR_FALLBACK_LIVE requestId=$currentRequestId")
                startListeningWithoutBeep()
                return
            }
            isRecordedAudioRequest = false
            closeRecordedAudioReadFd()
            onErrorWithCode?.invoke(msg, error)
            if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                error != SpeechRecognizer.ERROR_CLIENT &&
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                onError(msg)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim()
            val finalText = when {
                !text.isNullOrBlank() -> text
                !lastPartialText.isNullOrBlank() -> lastPartialText
                else -> null
            }
            if (!finalText.isNullOrBlank()) {
                Log.i(TAG, "STT_RESULTS requestId=$currentRequestId text=$finalText elapsedSinceRequestMs=${System.currentTimeMillis() - currentRequestStartedAtMs}")
                onResult(finalText)
                lastPartialText = null
                isRecordedAudioRequest = false
                closeRecordedAudioReadFd()
            } else {
                onError("인식된 말이 없습니다.")
            }
            onListeningEndedReason(if (!finalText.isNullOrBlank()) "onResults" else "onResults(빈 결과)")
            isListening = false
            onListeningChanged(false)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotBlank()) lastPartialText = text
            if (text.isNotBlank()) {
                Log.d(TAG, "STT_PARTIAL requestId=$currentRequestId text=$text elapsedSinceRequestMs=${System.currentTimeMillis() - currentRequestStartedAtMs}")
            }
            onPartialResult?.invoke(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
