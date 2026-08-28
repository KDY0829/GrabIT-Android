package com.example.grabitTest

import android.speech.tts.TextToSpeech

class TtsFeedbackController(
    private val ttsManager: TTSManager,
    private val shouldDropAutoGuidance: () -> Boolean,
    private val shouldBlockQueuedGuidance: () -> Boolean
) {
    private val guidanceQueue = TtsPriorityQueue(ttsManager)

    fun speak(
        text: String,
        urgent: Boolean = true,
        isAutoGuidance: Boolean = true,
        onDone: (() -> Unit)? = null
    ) {
        if (isAutoGuidance && shouldDropAutoGuidance()) return
        if (!urgent && shouldBlockQueuedGuidance()) return
        if (urgent) {
            guidanceQueue.interrupt()
            ttsManager.speak(text, TextToSpeech.QUEUE_FLUSH, onDone)
        } else {
            guidanceQueue.enqueue(text, TtsPriorityQueue.PRIORITY_NORMAL)
        }
    }

    fun clear() {
        guidanceQueue.clear()
    }

    fun stop() {
        ttsManager.stop()
    }

    fun isReady(): Boolean {
        return ttsManager.isReady()
    }

    fun release() {
        guidanceQueue.clear()
        ttsManager.release()
    }
}