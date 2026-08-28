package com.example.grabitTest

class GuidanceStateMachine {

    enum class State {
        IDLE,
        SEARCHING,
        CANDIDATE_DETECTED,
        TARGET_LOCKED,
        CENTERED,
        VERIFYING,
        COMPLETED
    }

    var currentState: State = State.IDLE
        private set // setter의 접근 제한을 통해 외부에서 직접 변경하지 못하도록 함

    fun reset() {
        currentState = State.IDLE
    }

    fun startSearching(): Boolean {
        return transitionTo(State.SEARCHING)
    }

    fun candidateDetected(): Boolean {
        return transitionTo(State.CANDIDATE_DETECTED)
    }

    fun targetLocked(): Boolean {
        return transitionTo(State.TARGET_LOCKED)
    }

    fun targetCentered(): Boolean {
        return transitionTo(State.CENTERED)
    }

    fun startVerification(): Boolean {
        return transitionTo(State.VERIFYING)
    }

    fun verificationFailed(): Boolean {
        return transitionTo(State.TARGET_LOCKED)
    }

    fun complete(): Boolean {
        return transitionTo(State.COMPLETED)
    }

    private fun transitionTo(nextState: State): Boolean {
        if (currentState == nextState) return true
        val allowed = when (currentState) { // switch 문 같은거임.
            State.IDLE -> nextState == State.SEARCHING
            State.SEARCHING -> nextState == State.CANDIDATE_DETECTED ||
                nextState == State.TARGET_LOCKED
            State.CANDIDATE_DETECTED -> nextState == State.SEARCHING ||
                nextState == State.TARGET_LOCKED
            State.TARGET_LOCKED -> nextState == State.SEARCHING ||
                nextState == State.CENTERED ||
                nextState == State.VERIFYING
            State.CENTERED -> nextState == State.SEARCHING ||
                nextState == State.TARGET_LOCKED ||
                nextState == State.VERIFYING
            State.VERIFYING -> nextState == State.SEARCHING ||
                nextState == State.TARGET_LOCKED ||
                nextState == State.COMPLETED
            State.COMPLETED -> false
        }
        if (!allowed) return false
        currentState = nextState
        return true
    }
}