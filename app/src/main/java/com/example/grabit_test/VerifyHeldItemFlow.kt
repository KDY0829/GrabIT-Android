package com.example.grabitTest

class VerifyHeldItemFlow(
    private val requiredStableFrames: Int = 3,
    private val confidenceThreshold: Float = 0.5f
) {
    enum class State {
        IDLE,
        VERIFYING,
        SUCCESS,
        FAILURE
    }

    enum class VerificationResult {
        PENDING,
        SUCCESS,
        FAILURE
    }

    @Volatile
    var currentState: State = State.IDLE
        private set

    private var targetLabel = ""
    private var matchingFrames = 0
    private var otherFrames = 0

    @Synchronized
    fun start(targetLabel: String): Boolean {
        val normalizedTarget = targetLabel.trim()
        if (normalizedTarget.isEmpty()) return false
        this.targetLabel = normalizedTarget
        matchingFrames = 0
        otherFrames = 0
        currentState = State.VERIFYING
        return true
    }

    @Synchronized
    fun onDetection(
        detectedLabel: String?,
        confidence: Float
    ): VerificationResult {
        if (currentState != State.VERIFYING) {
            return VerificationResult.PENDING
        }
        if (detectedLabel.isNullOrBlank() || confidence < confidenceThreshold) {
            matchingFrames = 0
            otherFrames = 0
            return VerificationResult.PENDING
        }
        if (detectedLabel.trim().equals(targetLabel, ignoreCase = true)) {
            matchingFrames++
            otherFrames = 0
            if (matchingFrames >= requiredStableFrames) {
                currentState = State.SUCCESS
                return VerificationResult.SUCCESS
            }
        } else {
            otherFrames++
            matchingFrames = 0
            if (otherFrames >= requiredStableFrames) {
                currentState = State.FAILURE
                return VerificationResult.FAILURE
            }
        }
        return VerificationResult.PENDING
    }

    @Synchronized
    fun reset() {
        targetLabel = ""
        matchingFrames = 0
        otherFrames = 0
        currentState = State.IDLE
    }
}