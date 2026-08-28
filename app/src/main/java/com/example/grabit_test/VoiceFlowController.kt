package com.example.grabitTest

import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.grabitTest.data.synonym.SynonymRepository

/**
 * 시각장애인을 위한 음성 인터랙션 상태 머신
 * 흐름: 안내 → 삐 → 상품명 말하기 → 확인 질문 → 삐 → 음성 확인 → 탐색 → 결과 안내
 */
class VoiceFlowController(
    private val ttsManager: TTSManager,
    private val onStateChanged: (VoiceFlowState, String) -> Unit,
    private val onRequestStartStt: () -> Unit,
    private val onStartSearch: (productName: String) -> Unit,
    private val onProductNameEntered: (productName: String) -> Unit = {}
) {
    companion object {
        private const val TAG = "VoiceFlowController"

        // 안내 멘트
        const val MSG_ASK_PRODUCT = "찾으시는 상품을 말씀해주세요. 앱 사용방법은 도움말이라고 말씀해주세요."

        const val MSG_HELP =
            "홈 화면에서 시작하기 버튼을 누르거나, 볼륨 업 키를 길게 누르면 찾으시는 상품을 말씀해 달라는 안내가 나옵니다.\n\n" +
            "상품 이름을 말하시면 맞는지 확인한 뒤, 예라고 하시거나 확인 버튼을 누르시면 찾기가 시작됩니다. 재입력 버튼으로 상품명을 다시 말할 수 있습니다.\n\n" +
            "상품을 찾는 동안 방향과 거리 안내가 음성으로 나옵니다. 상품이 가까이 있으면 손을 뻗어 확인해보세요 안내 후, 손을 뻗어 닿으시면 상품에 닿았나요라고 물어봅니다. 예라고 하시거나 예 버튼을 누르시면 안내가 종료됩니다.\n\n" +
            "찾지 못했을 때는 다시 찾기 버튼으로 다시 시도할 수 있습니다.\n\n" +
            "내 정보의 자주 찾는 상품, 최근 찾은 상품에서 항목을 누르면 해당 상품 찾기가 바로 시작됩니다.\n\n" +
            "도움말이라고 말하시면 이 사용방법을 다시 들으실 수 있습니다."

        /** 확인 단계: 사용자가 무엇을 찾는지 알 수 있도록 상품명 유지 */
        fun msgConfirmProduct(productName: String) =
            "찾으시는 상품이 ${productName} 맞습니까? 맞으면 '예'라고 말해주세요."

        fun msgSearching(productName: String) = "상품을 찾고 있습니다. 잠시만 기다려주세요."

        const val MSG_SEARCH_FAILED =
            "상품을 찾지 못했습니다. 다시 찾으시겠습니까?"
    }

    enum class VoiceFlowState {
        APP_START,
        WAITING_PRODUCT_NAME,
        /** STT 상품명 → 클래스/표시명 해석 중(비동기). 이 구간에는 확인 멘트를 아직 내지 않음 */
        RESOLVING_PRODUCT_FOR_CONFIRM,
        CONFIRM_PRODUCT,
        WAITING_CONFIRMATION,
        SEARCHING_PRODUCT,
        SEARCH_RESULT,
        SEARCH_FAILED
    }

    var currentState: VoiceFlowState = VoiceFlowState.APP_START
        private set

    /** TTS 다음 단계가 음성 입력인지. 볼륨 다운 길게 누르기는 이 값이 true일 때만 TTS 스킵 후 STT 시작. */
    var isNextStepVoiceInput: Boolean = false
        private set

    private var lastSpokenText: String = ""
    private var currentProductName: String = ""
    /** STT 세션 종료 시각. 4초 이내에는 자동 탐지 TTS(거리/방향 안내) 무시용 */
    private var lastSttEndTime = 0L

    /** 앱 시작 시 호출: 상태만 APP_START로 두고, STT는 시작하지 않음 (화면 터치 후 startProductNameInput 호출) */
    fun start() {
        transitionTo(VoiceFlowState.APP_START)
    }

    /** 화면 터치 후 호출: 찾으시는 상품을 말씀해주세요 → STT 시작 (STT에서 "삐 소리가 나면 말씀해주세요" + 삐 재생) */
    fun startProductNameInput() {
        Log.i(TAG, "VOICE_FLOW_START_PRODUCT_INPUT state=$currentState")
        transitionTo(VoiceFlowState.WAITING_PRODUCT_NAME)
        speak(MSG_ASK_PRODUCT) {
            onRequestStartStt()
        }
    }

    /** 볼륨 다운 길게 누르기: 상태 변경 없이 재생 중 TTS만 스킵하고 STT만 시작 */
    fun requestSttOnly() {
        Log.i(TAG, "VOICE_FLOW_REQUEST_STT_ONLY state=$currentState")
        onRequestStartStt()
    }

    /** STT 결과 처리 */
    fun onSttResult(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) return
        Log.i(TAG, "VOICE_FLOW_STT_RESULT state=$currentState text=$normalized")

        when (currentState) {
            VoiceFlowState.WAITING_PRODUCT_NAME -> handleProductNameReceived(normalized)
            VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM -> {
                if (isRepeatCommand(normalized)) repeatLast()
                else if (isHelpCommand(normalized)) speakHelp()
            }
            VoiceFlowState.WAITING_CONFIRMATION -> handleConfirmationReceived(normalized)
            VoiceFlowState.APP_START -> {
                if (isRepeatCommand(normalized)) repeatLast()
                else if (isHelpCommand(normalized)) speakHelp()
                else handleProductNameReceived(normalized)
            }
            VoiceFlowState.CONFIRM_PRODUCT -> {
                if (isRepeatCommand(normalized)) repeatLast()
                else handleConfirmationReceived(normalized)
            }
            VoiceFlowState.SEARCHING_PRODUCT -> {
                if (isRepeatCommand(normalized)) repeatLast()
            }
            VoiceFlowState.SEARCH_RESULT,
            VoiceFlowState.SEARCH_FAILED -> {
                if (isRepeatCommand(normalized)) repeatLast()
                else if (isHelpCommand(normalized)) speakHelp()
            }
        }
    }

    private fun handleProductNameReceived(text: String) {
        Log.i(TAG, "VOICE_FLOW_PRODUCT_RECEIVED text=$text state=$currentState")
        when {
            isHelpCommand(text) -> speakHelp()
            isRepeatCommand(text) -> repeatLast()
            else -> {
                currentProductName = text
                transitionTo(VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM)
                onProductNameEntered(currentProductName)
            }
        }
    }

    /**
     * [RESOLVING_PRODUCT_FOR_CONFIRM]에서 HomeFragment가 클래스 해석 후 호출.
     * 확인 멘트에는 사용자 발화가 아닌 표시명(예: 코카콜라)을 사용한다.
     */
    fun speakResolvedProductConfirmation(displayNameForUser: String) {
        if (currentState != VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM) return
        Log.i(TAG, "VOICE_FLOW_CONFIRM_ASK displayName=$displayNameForUser")
        transitionTo(VoiceFlowState.CONFIRM_PRODUCT)
        val msg = msgConfirmProduct(displayNameForUser)
        speak(msg) {
            transitionTo(VoiceFlowState.WAITING_CONFIRMATION)
            onRequestStartStt()
        }
    }

    /** 상품명을 목록에서 찾지 못한 경우: 확인 단계 취소 후 앱 시작 상태로 */
    fun resetVoiceFlowAfterUnmappedProduct() {
        if (currentState != VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM) return
        currentProductName = ""
        transitionTo(VoiceFlowState.APP_START)
    }

    private fun handleConfirmationReceived(text: String) {
        val normalized = text.trim()
        val isYes = isConfirmationYes(normalized)
        var branchChosen = "INIT"
        var startSearchCalled = false
        val currentTargetSnapshot = currentProductName

        if (isYes) {
            branchChosen = "YES"
            Log.i(TAG, "VOICE_FLOW_CONFIRM_RESULT text=$normalized isYes=true branch=$branchChosen target=$currentTargetSnapshot")
            transitionTo(VoiceFlowState.SEARCHING_PRODUCT)
            val msg = msgSearching(currentProductName)
            speak(msg)
            onStartSearch(currentProductName)
            startSearchCalled = true
        } else {
            branchChosen = "NON_YES_RESET"
            Log.i(TAG, "VOICE_FLOW_CONFIRM_RESULT text=$normalized isYes=false branch=$branchChosen target=$currentTargetSnapshot")
            resetToAppStartWithRestartPrompt()
        }

    }

    private fun isConfirmationYes(text: String): Boolean {
        if (SynonymRepository.isLoaded()) {
            return SynonymRepository.isYesAnswer(text)
        }
        val t = text.trim().lowercase().replace(" ", "")
        return t.contains("예") || t.contains("네") || t.contains("내") || t.contains("맞") || t.contains("응") ||
            t == "yes" || t == "y" || t.contains("그래") || t.contains("좋아") ||
            t == "네" || t == "내" || t == "예"
    }

    private fun isConfirmationNo(text: String): Boolean {
        val t = text.trim().lowercase().replace(" ", "")
        return t.contains("아니") || t.contains("틀렸") || t.contains("다른") ||
            t == "no" || t == "n"
    }

    private fun isHelpCommand(text: String) =
        text.contains("도움말") || text.contains("도움") || text.equals("help", ignoreCase = true)

    private fun isRepeatCommand(text: String) =
        text.contains("다시") || text.contains("재생") || text.equals("repeat", ignoreCase = true)

    private fun resetToAppStartWithRestartPrompt() {
        currentProductName = ""
        transitionTo(VoiceFlowState.APP_START)
        // 확인 단계 이후에는 YES가 아닐 경우 언제나 초기 화면으로 복귀 + 동일 안내 멘트
        ttsManager.stop()
        speak(VoicePrompts.PROMPT_TOUCH_RESTART)
    }

    /** 확인(예) 버튼 클릭: 맞으면 예와 동일하게 검색 시작 */
    fun onConfirmClicked() {
        if (currentState == VoiceFlowState.CONFIRM_PRODUCT || currentState == VoiceFlowState.WAITING_CONFIRMATION) {
            transitionTo(VoiceFlowState.SEARCHING_PRODUCT)
            val msg = msgSearching(currentProductName)
            speak(msg)
            onStartSearch(currentProductName)
        }
    }

    /** 재입력 버튼 클릭 */
    fun onReinputClicked() {
        when (currentState) {
            VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM,
            VoiceFlowState.CONFIRM_PRODUCT,
            VoiceFlowState.WAITING_CONFIRMATION -> {
                currentProductName = ""
                transitionTo(VoiceFlowState.WAITING_PRODUCT_NAME)
                speak(MSG_ASK_PRODUCT) {
                    onRequestStartStt()
                }
            }
            VoiceFlowState.SEARCH_FAILED -> {
                transitionTo(VoiceFlowState.WAITING_PRODUCT_NAME)
                speak(MSG_ASK_PRODUCT) {
                    onRequestStartStt()
                }
            }
            else -> {}
        }
    }

    /** 탐색 완료 */
    fun onSearchComplete(
        success: Boolean,
        detectedClass: String? = null,
        boxRect: RectF? = null,
        imageWidth: Int = 0,
        imageHeight: Int = 0,
        confidencePercent: Int? = null
    ) {
        Log.i(TAG, "VOICE_FLOW_SEARCH_COMPLETE success=$success detectedClass=$detectedClass confidencePercent=$confidencePercent state=$currentState")
        if (success) {
            transitionTo(VoiceFlowState.SEARCH_RESULT)
            // 위치/거리 안내는 HomeFragment transitionToLocked에서 이미 재생함. 중복 제거.
        } else {
            transitionTo(VoiceFlowState.SEARCH_FAILED)
            speak(MSG_SEARCH_FAILED)
        }
    }

    /** 위치 안내를 주기적으로 반복할 때 호출 (화면 9구역 기반 + 핸드폰 조작 유도). */
    fun announcePosition(displayName: String, boxRect: RectF, imageWidth: Int, imageHeight: Int, distMm: Float) {
        speak(getPositionAnnounceMessage(displayName, boxRect, imageWidth, imageHeight, distMm))
    }

    /** 55cm: 정면에서 이 거리 이하면 "손을 뻗어 확인", 그보다 멀면 "앞으로 걸어가세요" (HomeFragment REACH_DISTANCE_MM와 동일) */
    private val REACH_DISTANCE_MM = 550f

    /** 주기 위치 안내 문장만 생성: "상품이 [구역]에 있습니다. 핸드폰을 [방향]으로 움직여주세요" (화면 9구역). 정면은 거리별 안내. */
    fun getPositionAnnounceMessage(displayName: String, boxRect: RectF, imageWidth: Int, imageHeight: Int, distMm: Float): String {
        val zone = getZoneName(boxRect, imageWidth, imageHeight)
        return if (zone == "정면") {
            if (distMm <= REACH_DISTANCE_MM)
                "상품이 정면에 있습니다. 손을 뻗어 확인해보세요."
            else
                getCenterDistanceMessage(distMm)
        } else {
            buildPositionGuidance(boxRect, imageWidth, imageHeight)
        }
    }

    /** 정면 + 거리: 55cm 이하면 "손 뻗어", 그보다 멀면 "방향 유지한 채 걸어가세요"만 (거리 기준 55cm 단일). */
    fun getCenterDistanceMessage(distMm: Float): String {
        if (distMm <= REACH_DISTANCE_MM)
            return "상품이 정면에 있습니다. 손을 뻗어 확인해보세요."
        return "상품이 정면에 있습니다. 방향을 유지한 채 앞으로 걸어가세요."
    }

    /** 현재 박스가 속한 9구역 이름만 반환 (구역 변경 감지용). */
    fun getZoneName(boxRect: RectF, imageWidth: Int, imageHeight: Int): String {
        if (imageWidth <= 0 || imageHeight <= 0) return "화면 안"
        val centerXNorm = (boxRect.left + boxRect.right) / 2f / imageWidth
        val centerYNorm = (boxRect.top + boxRect.bottom) / 2f / imageHeight
        val zoneCol = when {
            centerXNorm < 1f / 3f -> 0
            centerXNorm < 2f / 3f -> 1
            else -> 2
        }
        val zoneRow = when {
            centerYNorm < 1f / 3f -> 0
            centerYNorm < 2f / 3f -> 1
            else -> 2
        }
        return when (zoneRow to zoneCol) {
            0 to 0 -> "좌측 상단"
            0 to 1 -> "중앙 상단"
            0 to 2 -> "우측 상단"
            1 to 0 -> "좌측"
            1 to 1 -> "정면"
            1 to 2 -> "우측"
            2 to 0 -> "좌측 하단"
            2 to 1 -> "중앙 하단"
            2 to 2 -> "우측 하단"
            else -> "화면 안"
        }
    }

    /**
     * 화면을 9구역(좌상단·중앙상단·우상단 / 좌측·정면·우측 / 좌하단·중앙하단·우하단)으로 나누어
     * "상품이 [구역]에 있습니다. 핸드폰을 [방향]으로 움직여주세요" 형식으로 반환.
     */
    private fun buildPositionGuidance(box: RectF, w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "상품이 화면 안에 있습니다. 핸드폰을 천천히 움직여보세요."
        val centerXNorm = (box.left + box.right) / 2f / w
        val centerYNorm = (box.top + box.bottom) / 2f / h
        val zoneCol = when {
            centerXNorm < 1f / 3f -> 0
            centerXNorm < 2f / 3f -> 1
            else -> 2
        }
        val zoneRow = when {
            centerYNorm < 1f / 3f -> 0
            centerYNorm < 2f / 3f -> 1
            else -> 2
        }
        val (zoneName, actionDir) = when (zoneRow to zoneCol) {
            0 to 0 -> "좌측 상단" to "왼쪽 위로 조금"
            0 to 1 -> "중앙 상단" to "위로 조금"
            0 to 2 -> "우측 상단" to "오른쪽 위로 조금"
            1 to 0 -> "좌측" to "왼쪽으로 조금"
            1 to 1 -> "정면" to "그대로 두고 손을 뻗어 확인해보세요"
            1 to 2 -> "우측" to "오른쪽으로 조금"
            2 to 0 -> "좌측 하단" to "왼쪽 아래로 조금"
            2 to 1 -> "중앙 하단" to "아래로 조금"
            2 to 2 -> "우측 하단" to "오른쪽 아래로 조금"
            else -> "화면 안" to "천천히 움직여보세요"
        }
        return if (zoneName == "정면") {
            "상품이 정면에 있습니다. $actionDir."
        } else {
            "상품이 ${zoneName}에 있습니다. 핸드폰을 $actionDir 움직여주세요."
        }
    }

    /** 근거리(손 뻗기) 안내: 9구역 기준 "상품이 [구역]에 있습니다. 손을 뻗어 확인해보세요." */
    fun getProximityReachMessage(boxRect: RectF, imageWidth: Int, imageHeight: Int): String {
        if (imageWidth <= 0 || imageHeight <= 0) return "상품이 가까이 있습니다. 손을 뻗어 확인해보세요."
        val centerXNorm = (boxRect.left + boxRect.right) / 2f / imageWidth
        val centerYNorm = (boxRect.top + boxRect.bottom) / 2f / imageHeight
        val zoneCol = when {
            centerXNorm < 1f / 3f -> 0
            centerXNorm < 2f / 3f -> 1
            else -> 2
        }
        val zoneRow = when {
            centerYNorm < 1f / 3f -> 0
            centerYNorm < 2f / 3f -> 1
            else -> 2
        }
        val zoneName = when (zoneRow to zoneCol) {
            0 to 0 -> "좌측 상단"
            0 to 1 -> "중앙 상단"
            0 to 2 -> "우측 상단"
            1 to 0 -> "좌측"
            1 to 1 -> "정면"
            1 to 2 -> "우측"
            2 to 0 -> "좌측 하단"
            2 to 1 -> "중앙 하단"
            2 to 2 -> "우측 하단"
            else -> "화면 안"
        }
        return "상품이 ${zoneName}에 있습니다. 손을 뻗어 확인해보세요."
    }

    /** 다시 찾기 */
    fun onRetrySearch() {
        if (currentState != VoiceFlowState.SEARCH_FAILED && currentState != VoiceFlowState.SEARCH_RESULT) return
        transitionTo(VoiceFlowState.WAITING_PRODUCT_NAME)
        speak(MSG_ASK_PRODUCT) {
            onRequestStartStt()
        }
    }

    private fun speakHelp() {
        speak(MSG_HELP)
        if (currentState == VoiceFlowState.APP_START || currentState == VoiceFlowState.WAITING_PRODUCT_NAME) {
            transitionTo(VoiceFlowState.WAITING_PRODUCT_NAME)
        }
    }

    fun repeatLast() {
        if (lastSpokenText.isNotBlank()) {
            speak(lastSpokenText)
        }
    }

    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        lastSpokenText = text
        Log.i(TAG, "VOICE_FLOW_SPEAK_REQUEST state=$currentState text=$text")
        ttsManager.speak(text, TextToSpeech.QUEUE_FLUSH, onDone)
    }

    private fun transitionTo(state: VoiceFlowState) {
        val prevState = currentState
        currentState = state
        isNextStepVoiceInput = when (state) {
            VoiceFlowState.WAITING_PRODUCT_NAME,
            VoiceFlowState.CONFIRM_PRODUCT -> true
            VoiceFlowState.APP_START,
            VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM,
            VoiceFlowState.WAITING_CONFIRMATION,
            VoiceFlowState.SEARCHING_PRODUCT,
            VoiceFlowState.SEARCH_RESULT,
            VoiceFlowState.SEARCH_FAILED -> false
        }
        val stateLabel = when (state) {
            VoiceFlowState.APP_START -> "앱 시작"
            VoiceFlowState.WAITING_PRODUCT_NAME -> "상품명 입력 대기"
            VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM -> "상품명 해석 중"
            VoiceFlowState.CONFIRM_PRODUCT -> "상품 확인"
            VoiceFlowState.WAITING_CONFIRMATION -> "확인 대기"
            VoiceFlowState.SEARCHING_PRODUCT -> "탐색 중"
            VoiceFlowState.SEARCH_RESULT -> "탐색 결과"
            VoiceFlowState.SEARCH_FAILED -> "탐색 실패"
        }
        Log.i(TAG, "VOICE_FLOW_STATE from=$prevState to=$state nextVoiceInput=$isNextStepVoiceInput")
        onStateChanged(state, stateLabel)
    }

    fun getCurrentProductName(): String = currentProductName

    /** STT 대기 종료 또는 TOUCH_CONFIRM 해제 직후 호출. 4초간 자동 안내 TTS 및 TOUCH_CONFIRM 재진입 차단 */
    fun notifySttEnded() {
        lastSttEndTime = System.currentTimeMillis()
    }

    /** 사용자 대화 직후 4초 이내이면 true. 이 구간에는 자동 탐지 TTS·TOUCH_CONFIRM 재진입 무시 */
    fun isInSttBreathingRoom(): Boolean =
        (System.currentTimeMillis() - lastSttEndTime) < 4000L

    /** 완전 리셋 시 breathing room 해제 (resetGlobalState 등에서 호출) */
    fun resetBreathingRoom() {
        lastSttEndTime = 0L
    }
}
