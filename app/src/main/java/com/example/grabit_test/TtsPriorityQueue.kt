package com.example.grabitTest

import android.speech.tts.TextToSpeech
import java.util.PriorityQueue

/**
 * TTS 메시지가 겹치지 않도록 우선순위 큐로 순차 재생.
 * 숫자가 작을수록 높은 우선순위 (0 = 안전/긴급, 1 = 일반, 2 = 저우선순위).
 */
class TtsPriorityQueue(
    private val tts: TTSManager,
    private val duplicateCooldownMs: Long = 5000L,
    /** 큐에서 꺼낸 메시지는 QUEUE_ADD로 재생해 기존 재생을 끊지 않음(삐리삐리 방지). */
    private val onSpeak: (String, (() -> Unit)?) -> Unit = { text, onDone ->
        tts.speak(text, TextToSpeech.QUEUE_ADD, onDone)
    }
) {
    companion object {
        private const val TAG = "TtsPriorityQueue"
        const val PRIORITY_URGENT = 0
        const val PRIORITY_NORMAL = 1
        const val PRIORITY_LOW = 2
    }

    private data class Item(val priority: Int, val text: String, val ts: Long = System.currentTimeMillis()) :
        Comparable<Item> {
        override fun compareTo(other: Item): Int = compareBy<Item> { it.priority }.thenBy { it.ts }.compare(this, other)
    }

    private val queue = PriorityQueue<Item>()
    private val lastSpokenAtMs = mutableMapOf<String, Long>()
    private var isPlaying = false

    fun enqueue(text: String, priority: Int = PRIORITY_NORMAL) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        synchronized(queue) {
            val now = System.currentTimeMillis()
            val lastSpokenAt = lastSpokenAtMs[trimmed]
            val isInCooldown = lastSpokenAt != null &&
                now - lastSpokenAt < duplicateCooldownMs
            if (queue.any { it.text == trimmed } || isInCooldown) {
                return
            }
            queue.add(Item(priority, trimmed))
            drainLocked()
        }
    }

    private fun drainLocked() {
        if (isPlaying || queue.isEmpty()) return
        val item = queue.poll() ?: return
        isPlaying = true
        lastSpokenAtMs[item.text] = System.currentTimeMillis()
        onSpeak(item.text) {
            isPlaying = false
            synchronized(queue) {
                if (queue.isNotEmpty()) drainLocked()
            }
        }
    }

    fun interrupt() {
        synchronized(queue) {
            queue.clear()
            isPlaying = false
        }
    }

    fun clear() {
        synchronized(queue) {
            queue.clear()
            lastSpokenAtMs.clear()
        }
    }

    fun isBusy(): Boolean = synchronized(queue) { isPlaying || queue.isNotEmpty() }
}
