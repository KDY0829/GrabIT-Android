package com.example.grabitTest

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.grabit_test.data.AppDatabase
import com.example.grabit_test.data.product.ProductDimensionRepository
import com.example.grabit_test.data.SearchHistoryRepository
import com.example.grabit_test.data.history.SearchHistoryItem
import com.example.grabitTest.data.synonym.SynonymRepository
import com.example.grabitTest.databinding.FragmentHomeBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val sharedViewModel: SharedViewModel by activityViewModels()

    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    /** 줌 미사용: 거리/ghost 박스 계산에서 1f로 고정 */
    private val currentZoomRatio = 1f
    private val lastTargetZoomRatio = 1f

    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private val SCAN_INITIAL_DELAY_MS = 3000L

    private var yoloxInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var handLandmarker: HandLandmarker? = null
    private val latestHandsResult = AtomicReference<HandLandmarkerResult?>(null)

    private var classLabels: List<String> = emptyList()
    private var hasLoggedYoloxClassMismatch = false

    enum class SearchState { IDLE, SEARCHING, LOCKED }
    private var searchState = SearchState.IDLE
    private val guidanceStateMachine = GuidanceStateMachine()
    private val verifyHeldItemFlow = VerifyHeldItemFlow()
    private var frozenBox: OverlayView.DetectionBox? = null
    private var frozenImageWidth = 0
    private var frozenImageHeight = 0
    private var lockedTargetLabel: String = ""
    private var lastTargetBox: RectF? = null
    private var missedFramesCount = 0
    private val GHOST_BOX_MAX_MISSED_FRAMES = 30

    private val LOCK_CONFIRM_FRAMES = 2
    /** 탐색 중 한두 프레임 놓쳐도 락 대기 유지. 이 프레임 수만큼 연속 미매칭 시에만 초기화 */
    private val PENDING_LOCK_MAX_MISS_FRAMES = 12
    /** 이전 박스와 IoU가 이 값 이상이면 같은 타겟으로 인정 (타일링/노이즈로 박스가 살짝 흔들려도 유지) */
    private val PENDING_LOCK_IOU_THRESHOLD = 0.2f
    private val CANDIDATE_FEEDBACK_COOLDOWN_MS = 5000L
    @Volatile private var lastCandidateFeedbackTimeMs = 0L
    private val TARGET_LOCK_STABILITY_MS = 1000L
    private val TARGET_LOCK_MAX_MOVEMENT_NORM = 0.03f
    private var pendingLockBox: OverlayView.DetectionBox? = null
    private var pendingLockCount = 0
    private var pendingLockMissCount = 0
    /** 시나리오1: 락은 휴대폰 움직임이 멎은 뒤에만. 같은 타겟을 처음 본 시각(ms). */
    private var pendingLockStableSinceMs = 0L

    /** 박스 추적 YOLOX 검증 간격 (프레임). 정면/비정면 동일 */
    private val VALIDATION_INTERVAL = 6
    /** 연속으로 이만큼 검증 실패 시 락 해제 (10회 × 8프레임 ≈ 2.5~3초) */
    private val VALIDATION_FAIL_LIMIT = 10
    private var validationFailCount = 0
    private var lockedFrameCount = 0

    private lateinit var gyroManager: GyroTrackingManager
    private val opticalFlowTracker = OpticalFlowTracker()
    private var wasOccluded = false

    private val TARGET_CONFIDENCE_THRESHOLD = 0.5f
    private val TARGET_TRACKING_CONFIDENCE_THRESHOLD = 0.15f  // 줌 아웃 시 타겟 추적용 (낮춤)
    private val currentTargetLabel = AtomicReference<String>("")

    private var lastDirectionGuidanceTime = 0L
    private val DIRECTION_GUIDANCE_COOLDOWN_MS = 4000L
    private val SEARCH_PING_INTERVAL_MS = 12000L  // 탐색 침묵 12초 시 핑 안내
    private var lastSearchPingTime = 0L
    /** SEARCHING에서 5초 이상 미탐지 시 "상품이 보이지 않습니다" 안내 */
    private val SEARCH_OBJECT_NOT_FOUND_ANNOUNCE_MS = 5000L
    private var lastSearchNoDetectionStartMs = 0L
    private var searchObjectNotFoundAnnounced = false
    private val TARGET_BOX_RATIO = 0.12f  // 폴백: 거리 계산 실패 시 사용
    /** 55cm 이하에서 "손을 뻗어" 말하기 전 연속 프레임 수 */
    private val REACH_STABILITY_FRAMES = 5
    private var reachDistanceFrameCount = 0
    private val FOCAL_LENGTH_FACTOR = 1.1f   // pinhole: focal along width = imageWidth * this (박스 가로와 동일 축)
    private val DISTANCE_CALIBRATION_FACTOR = 1f   // 거리 보정 (표준: 보정 없음)
    /** 55cm: 손에 잡을 수 있는 거리. 이하일 때 진동+음성 "손을 뻗어 확인해보세요" */
    private val REACH_DISTANCE_MM = 550f

    /** YOLOX 타일링: 먼/작은 물체용. 1x1 → 2x2 → 3x3 단계적 사용, 상호 배제 */
    private val ENABLE_YOLOX_TILING = true
    private val TILING_GRID = 2  // 2x2 기본
    /** 탐색 단계: 1=1x1만, 2=1x1+2x2, 3=2x2+3x3(3x3은 N프레임마다) */
    private var searchTilingTier = 1
    /** 1x1만 실패한 지 이 시간(ms) 지나면 2단계(1x1+2x2)로 */
    private val ESCALATE_TO_TIER2_AFTER_MS = 2500L
    /** 2x2까지 실패한 지 이 시간(ms) 지나면 3단계(2x2+3x3)로. 3x3 연산 절약용 */
    private val ESCALATE_TO_TIER3_AFTER_MS = 6000L
    /** 3단계에서 3x3 추론을 이 프레임마다만 실행 (2x2는 매 프레임) */
    private val TIER3_GRID_INTERVAL = 3
    private var lastTier1NoMatchTimeMs = 0L
    private var lastTier2NoMatchTimeMs = 0L
    private var searchFrameCountInTier3 = 0
    private val YOLOX_DIAG_LOG_INTERVAL_MS = 1000L
    private var lastYoloxParseDiagLogMs = 0L
    private var lastYoloxMatchDiagLogMs = 0L
    private var lastYoloxSearchDiagLogMs = 0L
    /** 락 시 타일링으로 탐지했으면 true. 락 해제 시 SEARCHING에서 tier 1부터 재시작 */
    private var useTilingWhenLocked = false
    /** 락 시 타일링이었으면 사용한 그리드(2 또는 3). 검증 시 동일 그리드 사용 */
    private var tilingGridWhenLocked = 2

    private var sttManager: STTManager? = null
    private var ttsManager: TTSManager? = null
    private lateinit var ttsFeedbackController: TtsFeedbackController
    private lateinit var beepFeedbackController: BeepFeedbackController
    private lateinit var hapticFeedbackController: HapticFeedbackController
    private var speakerVerificationManager: SpeakerVerificationManager? = null
    private var voiceActivityDetector: VoiceActivityDetector? = null
    private var isSpeakerGateInProgress = false
    private var proximityModeActive = false
    private var voiceFlowController: VoiceFlowController? = null
    private val searchTimeoutHandler = Handler(Looper.getMainLooper())
    private var searchTimeoutRunnable: Runnable? = null
    @Volatile private var searchTimeoutAborted = false
    private val SEARCH_TIMEOUT_MS = 30_000L
    private val POSITION_ANNOUNCE_INTERVAL_MS = 5000L
    /** 같은 상품 위치 안내 중복 방지: 이 간격 미만이면 안내 생략(재인식 시 말 끊김/반복 방지) */
    private val POSITION_ANNOUNCE_MIN_GAP_MS = 4500L
    /** 상품 위치 안내 중 못 들을 수 있어 15초마다 한 번씩 주기 알림 (상태 변경 없어도) */
    private val POSITION_PERIODIC_REMINDER_MS = 15000L
    private var lastPositionPeriodicReminderMs = 0L
    /** 위치 안내 시작: 사용자가 1초간 큰 움직임 없을 때부터 안내 시작 */
    private val POSITION_ANNOUNCE_STABILITY_MS = 1000L
    private val POSITION_ANNOUNCE_STABILITY_CHECK_MS = 500L
    /** 박스 중심이 이 비율(화면 대비) 이상 움직이면 '큰 움직임'으로 간주 */
    private val POSITION_ANNOUNCE_MOVEMENT_THRESHOLD_NORM = 0.03f
    /** 시나리오3·4: 진동은 중앙 진입 1회, 팔길이 도달 1회만. 이 간격 미만이면 진동 생략 */
    private val VIBRATE_COOLDOWN_MS = 6000L
    private var lastVibrateTimeMs = 0L
    /** 직전 프레임에 정면(중앙) 구역이었는지. 진동+멘트 ①가운데 맞음 ②55cm 진입 둘만 쓰기 위해 */
    private var wasInCenterZone = false
    /** 직전에 55cm 이하였는지. 가까이 있다가 멀어지면 "방향 유지한 채 걸어가세요" 한 번 안내 */
    private var wasInReachDistance = false
    /** 55cm 구간에서 "손을 뻗어" 이미 한 번 호출했으면 true. 55cm 벗어나면 false로 리셋 → 한 번만 말하고 삐리삐리 방지 */
    private var reachAnnouncedThisSession = false
    /** 비정면 같은 구역 유지 시 이 시간(ms) 동안 변동 없으면 재알림 */
    private val POSITION_ANNOUNCE_SAME_ZONE_REMIND_MS = 5000L
    /** 비정면 구역 감지(구역 변경 즉시 안내용) 재검사 간격. 짧을수록 포지션 변경 감지 빠름 */
    private val POSITION_ANNOUNCE_NON_CENTER_POLL_MS = 400L
    private var positionAnnounceRunnable: Runnable? = null
    private var positionAnnounceStabilitySatisfied = false
    private var lastPositionStabilityRefRect: RectF? = null
    private var lastLargeMovementTime = 0L
    private var lastAnnouncedZone: String? = null
    /** 정면일 때만 사용. 55cm 이하(true) / 초과(false). 비정면이면 null */
    private var lastAnnouncedInReach: Boolean? = null
    /** 방향·거리: 한 곳(프레임)에서만 갱신하는 현재 값 */
    private var currentZone: String? = null
    private var currentDistMm: Float = 0f
    /** 정면일 때만 55cm 이하 여부. 비정면이면 null */
    private var currentInReach: Boolean? = null
    /** 구역이 이 시간(ms) 동안 동일해야 안내. 짧을수록 우측→우측상단 등 전환 시 말 잘 나옴. 너무 짧으면 경계에서 플립플롭 시 잔말 많음 */
    private val ZONE_STABLE_MS = 200L
    private var pendingAnnounceZone: String? = null
    private var pendingAnnounceInReach: Boolean? = null
    private var pendingAnnounceSinceMs = 0L
    /** 상태 로그 쓰로틀용 (원인 파악용) */
    private var lastPositionStateLogMs = 0L
    private var voiceSearchTargetLabel: String? = null
    /** 마이페이지/관리자에서 상품 선택 후 홈 진입 시, TTS 준비되면 재생·탐지 (TTS 비동기 초기화 대응) */
    private var pendingSearchTarget: String? = null
    private val PENDING_SEARCH_TARGET_MAX_WAIT_MS = 8000L
    private var pendingSearchTargetPostTime = 0L

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val REQUEST_CODE_PERMISSIONS = 10

    private var ttsDetectedPlayed = false
    /** 이번 세션에서 "찾았습니다" 피드백 재생 여부 (락 해제 후 다시 탐색할 때 중복 방지) */
    private var hasPlayedTargetLockFeedbackThisSearchSession = false
    /** “찾았습니다. 멈춰주세요” 재생 중에는 위치 안내를 막는다. */
    @Volatile private var isTargetLockFeedbackPlaying = false
    private var ttsGrabPlayed = false
    private var ttsGrabbedPlayed = false
    private var ttsAskAnotherPlayed = false
    @Volatile private var waitingForTouchConfirm = false
    @Volatile private var touchConfirmInProgress = false
    /** 터치확인 '예' 처리 직후 onResult가 같은 "예"를 음성플로우로 넘기지 않도록 사용 */
    private var lastTouchConfirmHandledTimeMs = 0L
    private val STT_MAX_RETRIES = 3
    private var touchConfirmSttRetryCount = 0
    private var voiceFlowSttRetryCount = 0
    private var voiceConfirmSilentRetryCount = 0
    private var handsOverlapFrameCount = 0
    private var pinchGrabFrameCount = 0

    private val handGuidanceHandler = Handler(Looper.getMainLooper())
    private var handGuidanceRunnable: Runnable? = null
    private var lastHandGuidanceTimeMs = 0L
    private val HAND_GUIDANCE_INTERVAL_MS = 5000L

    private var lastSuccessfulValidationTimeMs = 0L
    private val REACQUIRE_INFERENCE_AFTER_MS = 2000L
    /** 객체를 이 시간(ms) 동안 못 찾으면 락 해제 (FPS와 무관하게 빠르게 박스 제거) */
    private val LOCK_RELEASE_AFTER_MS = 2500L
    private val MIN_SEARCH_DURATION_BEFORE_DETECTED_TTS_MS = 2500L
    /** 같은 타겟으로 잡았다고 안내한 뒤 이 시간 이내에 다시 잡아도 "찾았습니다" 반복 안 함 */
    private val FOUND_ANNOUNCE_COOLDOWN_MS = 12000L
    private var lastFoundAnnounceTimeMs = 0L
    private var lastFoundAnnounceLabel: String? = null
    private var lastTimeEnteredSearchingMs = 0L
    private var hasAnnouncedDetectedThisSearchSession = false
    private var touchConfirmScheduled = false
    private var touchConfirmAskedTime = 0L
    private val TOUCH_CONFIRM_ANSWER_WAIT_MS = 3000L

    private val TOUCH_BOX_EXPAND_RATIO = 0.22f
    private val TOUCH_CONFIRM_FRAMES = 30
    private val RELEASE_HOLD_FRAMES = 10
    private val TOUCH_TTS_COOLDOWN_MS = 1800L

    private var touchFrameCount = 0
    private var releaseFrameCount = 0
    private var touchActive = false
    private var lastTouchTtsTimeMs = 0L
    private var lastTouchMidpointPx: Pair<Float, Float>? = null

    private var firstInferenceLogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        hapticFeedbackController = HapticFeedbackController(requireContext())
        loadClassLabels()
        ProductDictionary.load(requireContext())
        loadSynonymFromRemote()
        initYOLOX()
        initMediaPipeHands()
        initGyroTrackingManager()
        initSttTts()

        binding.confirmBtn.setOnClickListener {
            if (waitingForTouchConfirm || touchConfirmInProgress) {
                handleTouchConfirmYesNo("예")
            } else {
                voiceFlowController?.onConfirmClicked()
            }
        }
        binding.reinputBtn.setOnClickListener {
            if (waitingForTouchConfirm || touchConfirmInProgress) {
                handleTouchConfirmYesNo("아니오")
            } else {
                voiceFlowController?.onReinputClicked()
            }
        }
        binding.retryBtn.setOnClickListener { voiceFlowController?.onRetrySearch() }
        binding.startButton.setOnClickListener {
            binding.startOverlay.visibility = View.GONE
            onVoiceInputClicked()
        }
        sharedViewModel.selectedSearchTarget.observe(viewLifecycleOwner) { target ->
            target?.let {
                sharedViewModel.consumeSelectedSearchTarget()
                pendingSearchTarget = it
                pendingSearchTargetPostTime = System.currentTimeMillis()
                tryStartDetectionWithPendingTarget()
            }
        }

        sharedViewModel.volumeLongPressTrigger.observe(viewLifecycleOwner) { event ->
            if (event != null) {
                sharedViewModel.consumeVolumeLongPressTrigger()
                onVoiceInputClicked()
            }
        }

        sharedViewModel.volumeDownLongPressTrigger.observe(viewLifecycleOwner) { event ->
            if (event != null) {
                sharedViewModel.consumeVolumeDownLongPressTrigger()
                onVolumeDownLongPress()
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
            voiceFlowController?.start()
        } else {
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted() && _binding != null) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        resetGlobalState()
        // 화면 꺼짐(일시정지) 시 즉시 리소스 해제 — 카메라·TTS·STT 중단 (배터리·정책 대응)
        cancelSearchTimeout()
        stopPositionAnnounce()
        try { stopCamera() } catch (_: Exception) {}
        sttManager?.stopListening()
        ttsFeedbackController.stop()
    }

    override fun onStop() {
        super.onStop()
        cancelSearchTimeout()
        stopPositionAnnounce()
        try { stopCamera() } catch (_: Exception) {}
        sttManager?.stopListening()
        ttsFeedbackController.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        yoloxInterpreter?.close()
        gpuDelegate?.close()
        gyroManager.stopTracking()
        sttManager?.release()
        ttsFeedbackController.release()
        beepFeedbackController.release()
        speakerVerificationManager?.close()
        speakerVerificationManager = null
        handLandmarker?.close()
        _binding = null
    }

    private fun loadSynonymFromRemote() {
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) { SynonymRepository.loadFromLocal(requireContext().applicationContext) }
        }
    }

    private fun onVoiceInputClicked() {
        pendingSearchTarget = null
        if (!allPermissionsGranted()) {
            Toast.makeText(requireContext(), "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }
        if (voiceFlowController == null) {
            Toast.makeText(requireContext(), "준비 중입니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        ttsFeedbackController.stop()
        sttManager?.cancelListening()
        voiceFlowController?.startProductNameInput()
    }

    /** 볼륨 다운 길게 누르기: 스킵할 TTS가 있고 다음이 음성 입력일 때만 TTS 스킵 후 STT. (상품명 입력 대기, 상품 확인, 터치 확인만) */
    private fun onVolumeDownLongPress() {
        if (!allPermissionsGranted()) {
            Toast.makeText(requireContext(), "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }
        val inTouchConfirm = waitingForTouchConfirm || touchConfirmInProgress
        val canSkipToVoice = inTouchConfirm ||
            (voiceFlowController != null && voiceFlowController!!.isNextStepVoiceInput)
        if (!canSkipToVoice) return
        if (!inTouchConfirm && voiceFlowController == null) return
        pendingSearchTarget = null
        ttsFeedbackController.stop()
        sttManager?.cancelListening()
        if (inTouchConfirm) {
            startSpeakerVerifiedStt("product_search_touch_confirm_volume_skip")
        } else {
            voiceFlowController?.requestSttOnly()
        }
    }

    private fun startSpeakerVerifiedStt(reason: String) {
        val gateRequestAtMs = System.currentTimeMillis()
        Log.i(TAG, "VOICE_DIAG_STT_GATE_REQUEST reason=$reason inProgress=$isSpeakerGateInProgress")
        if (isSpeakerGateInProgress) {
            Log.i(
                SpeakerVerificationConfig.LOG_TAG,
                "SV_STT_GATE_REQUEST ignored=true reason=$reason gate_in_progress=true"
            )
            return
        }
        val verifier = speakerVerificationManager
        val registered = verifier?.hasVoiceprint() == true
        Log.i(
            SpeakerVerificationConfig.LOG_TAG,
            "SV_STT_GATE_REQUEST reason=$reason registered=$registered"
        )
        isSpeakerGateInProgress = true
        setSttGateUiEnabled(false)

        Log.i(TAG, "VOICE_DIAG_STT_GATE_BEEP_START reason=$reason elapsedMs=${System.currentTimeMillis() - gateRequestAtMs}")
        beepFeedbackController.playListeningStart {
            Log.i(TAG, "VOICE_DIAG_STT_GATE_BEEP_DONE reason=$reason elapsedMs=${System.currentTimeMillis() - gateRequestAtMs}")
            if (verifier == null || !registered) {
                Log.i(
                    SpeakerVerificationConfig.LOG_TAG,
                    "SV_STT_GATE registered=false action=fallback_stt_without_extra_beep reason=$reason"
                )
                isSpeakerGateInProgress = false
                setSttGateUiEnabled(true)
                sttManager?.startListeningWithoutBeep()
                return@playListeningStart
            }

            val vad = voiceActivityDetector
            if (vad == null) {
                isSpeakerGateInProgress = false
                setSttGateUiEnabled(true)
                sttManager?.startListeningWithoutBeep()
                return@playListeningStart
            }

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val capture = vad.recordAndAnalyze(SpeakerVerificationConfig.RECORD_MILLIS)
                    val vadResult = capture.result
                    Log.i(
                        TAG,
                        "VAD_GATE reason=$reason speech=${vadResult.isSpeech} vadReason=${vadResult.reason} " +
                            "rmsDb=${"%.1f".format(vadResult.rmsDb)} speechFrameRatio=${"%.2f".format(vadResult.speechFrameRatio)} " +
                            "capturedSamples=${vadResult.capturedSamples} elapsedMs=${System.currentTimeMillis() - gateRequestAtMs}"
                    )
                    if (!vadResult.isSpeech) {
                        voiceFlowController?.notifySttEnded()
                        isSpeakerGateInProgress = false
                        setSttGateUiEnabled(true)
                        Toast.makeText(requireContext(), "\uB9D0\uC18C\uB9AC\uAC00 \uAC10\uC9C0\uB418\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4. \uB2E4\uC2DC \uC2DC\uC791\uD574\uC8FC\uC138\uC694.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val result = verifier.verifyRecordedAudio(capture.audio, SpeakerVerificationConfig.DEFAULT_THRESHOLD)
                    if (result.accepted) {
                        Log.i(
                            SpeakerVerificationConfig.LOG_TAG,
                            "SV_STT_GATE accepted=true action=startSpeechRecognizerFromRecordedAudio reason=$reason"
                        )
                        isSpeakerGateInProgress = false
                        setSttGateUiEnabled(true)
                        Log.i(TAG, "VOICE_DIAG_STT_GATE_ACCEPTED_RECORDED reason=$reason elapsedMs=${System.currentTimeMillis() - gateRequestAtMs}")
                        val consumed = sttManager?.startListeningFromAudio(capture.audio) == true
                        if (!consumed) {
                            Log.w(TAG, "VOICE_DIAG_STT_RECORDED_UNSUPPORTED_FALLBACK_LIVE reason=$reason")
                            sttManager?.startListeningWithoutBeep()
                        }
                    } else {
                        Log.i(
                            SpeakerVerificationConfig.LOG_TAG,
                            "SV_STT_GATE accepted=false action=blocked reason=$reason"
                        )
                        Toast.makeText(requireContext(), "\uB4F1\uB85D\uB41C \uC0AC\uC6A9\uC790 \uC74C\uC131\uC774 \uC544\uB2D9\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show()
                        voiceFlowController?.notifySttEnded()
                        isSpeakerGateInProgress = false
                        setSttGateUiEnabled(true)
                    }
                } catch (e: Exception) {
                    Log.e(SpeakerVerificationConfig.LOG_TAG, "SV_STT_GATE_FAILED", e)
                    Toast.makeText(requireContext(), "\uD654\uC790 \uAC80\uC99D\uC744 \uC2E4\uD589\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show()
                    voiceFlowController?.notifySttEnded()
                    isSpeakerGateInProgress = false
                    setSttGateUiEnabled(true)
                }
            }
        }
    }

    private fun setSttGateUiEnabled(enabled: Boolean) {
        if (_binding == null) return
        binding.startButton.isEnabled = enabled
        binding.confirmBtn.isEnabled = enabled
        binding.reinputBtn.isEnabled = enabled
        binding.retryBtn.isEnabled = enabled
    }

    private fun currentSttGateReason(): String {
        if (waitingForTouchConfirm || touchConfirmInProgress) return "product_search_touch_confirm"
        return when (voiceFlowController?.currentState) {
            VoiceFlowController.VoiceFlowState.WAITING_PRODUCT_NAME -> "product_search_product_name"
            VoiceFlowController.VoiceFlowState.WAITING_CONFIRMATION -> "product_search_command"
            VoiceFlowController.VoiceFlowState.CONFIRM_PRODUCT -> "product_search_command"
            else -> "voice_command"
        }
    }

    /** pendingSearchTarget이 있으면 TTS 준비 시 탐지 시작(안내 TTS 포함). TTS 미준비 시 짧은 간격으로 재시도. */
    private fun tryStartDetectionWithPendingTarget() {
        if (_binding == null || !isAdded) {
            pendingSearchTarget = null
            return
        }
        val target = pendingSearchTarget ?: return
        if (ttsFeedbackController.isReady() == true) {
            pendingSearchTarget = null
            startDetectionWithTarget(target)
            return
        }
        if (System.currentTimeMillis() - pendingSearchTargetPostTime > PENDING_SEARCH_TARGET_MAX_WAIT_MS) {
            pendingSearchTarget = null
            requireActivity().runOnUiThread {
                _binding?.startOverlay?.visibility = View.GONE
                startScanningDirect(target)
            }
            val displayName = if (ProductDictionary.isLoaded()) ProductDictionary.getDisplayNameKo(target) else target
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val repo = SearchHistoryRepository(AppDatabase.getInstance(requireContext().applicationContext))
                repo.insert(SearchHistoryItem(query = displayName, classLabel = target, searchedAt = System.currentTimeMillis(), source = "selection"))
            }
            return
        }
        _binding?.root?.postDelayed({ tryStartDetectionWithPendingTarget() }, 250)
    }

    /** 관리자 탭 또는 마이페이지(자주/최근 찾은 상품)에서 상품 선택 시 탐지 시작 */
    private fun startDetectionWithTarget(targetLabel: String) {
        currentTargetLabel.set(targetLabel)
        val displayName = if (ProductDictionary.isLoaded()) ProductDictionary.getDisplayNameKo(targetLabel) else targetLabel
        requireActivity().runOnUiThread {
            _binding?.startOverlay?.visibility = View.GONE
            speak(VoiceFlowController.msgSearching(displayName), urgent = true, isAutoGuidance = false)
            startScanningDirect(targetLabel)
        }
        // 검색 이력: 찾고 싶은 상품을 선택한 시점에 한 번만 저장
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val repo = SearchHistoryRepository(AppDatabase.getInstance(requireContext().applicationContext))
            repo.insert(SearchHistoryItem(query = displayName, classLabel = targetLabel, searchedAt = System.currentTimeMillis(), source = "selection"))
        }
    }

    /** @param urgent true면 TTS 재생 중이어도 즉시 송출. false면 항상 큐에 넣어 순차 재생(직접 FLUSH 안 함 → "찾았습니다" onDone 덮어쓰기 방지).
     * @param isAutoGuidance true면 STT 대화 직후 4초 breathing room 동안 무시(Drop).
     * onDone은 마지막 인자로 두어 trailing lambda 사용 가능. */
    private fun speak(text: String, urgent: Boolean = true, isAutoGuidance: Boolean = true, onDone: (() -> Unit)? = null) {
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        ttsFeedbackController.speak(text, urgent, isAutoGuidance, onDone)
    }

    /** 완전 무결점 초기화(Kill-All Reset). 탭 전환·TOUCH_CONFIRM 긍정 종료 시 호출. */
    private fun resetGlobalState() {
        pendingSearchTarget = null
        pendingSearchTargetPostTime = 0L
        _binding?.startOverlay?.visibility = View.VISIBLE
        searchState = SearchState.IDLE
        guidanceStateMachine.reset()
        verifyHeldItemFlow.reset()
        currentTargetLabel.set("")
        proximityModeActive = false
        ttsFeedbackController.clear()
        ttsFeedbackController.stop()
        sttManager?.cancelListening()
        isSpeakerGateInProgress = false
        setSttGateUiEnabled(true)
        isScanning = false
        cancelSearchTimeout()
        stopPositionAnnounce()
        stopScanning()
        stopHandGuidanceTTS()
        scanHandler.removeCallbacksAndMessages(null)
        voiceSearchTargetLabel = null
        ttsDetectedPlayed = false
        lastCandidateFeedbackTimeMs = 0L
        hasPlayedTargetLockFeedbackThisSearchSession = false
        isTargetLockFeedbackPlaying = false
        ttsGrabPlayed = false
        ttsGrabbedPlayed = false
        ttsAskAnotherPlayed = false
        waitingForTouchConfirm = false
        touchConfirmInProgress = false
        touchConfirmScheduled = false
        lastTouchConfirmHandledTimeMs = 0L
        touchActive = false
        touchFrameCount = 0
        releaseFrameCount = 0
        lastTouchTtsTimeMs = 0L
        wasOccluded = false
        pendingLockBox = null
        pendingLockCount = 0
        pendingLockMissCount = 0
        pendingLockStableSinceMs = 0L
        validationFailCount = 0
        reachDistanceFrameCount = 0
        lastDirectionGuidanceTime = 0L
        lastSearchPingTime = 0L
        lastTargetBox = null
        missedFramesCount = 0
        lockedTargetLabel = ""
        frozenBox = null
        frozenImageWidth = 0
        frozenImageHeight = 0
        latestHandsResult.set(null)
        opticalFlowTracker.reset()
        if (::gyroManager.isInitialized) {
            try { gyroManager.stopTracking() } catch (_: Exception) {}
        }
        voiceFlowController?.resetBreathingRoom()
        voiceFlowController?.start()
        _binding?.let { b ->
            b.overlayView.setDetections(emptyList(), 0, 0)
            b.overlayView.setFrozen(false)
            b.voiceFlowButtons.visibility = View.GONE
        }
    }

    private fun startScanningDirect(targetLabel: String) {
        cancelSearchTimeout()
        currentTargetLabel.set(targetLabel)
        transitionToSearching(isNewSearchSession = true)
        startCamera()
        startSearchTimeout()
    }

    private fun initSttTts() {
        ttsManager = TTSManager(
            context = requireContext(),
            onReady = { },
            onSpeakDone = { },
            onError = { msg ->
                requireActivity().runOnUiThread {
                    Log.e(TAG, "[TTS 에러] $msg")
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
        ttsFeedbackController = TtsFeedbackController(
            ttsManager = ttsManager!!,
            shouldDropAutoGuidance = {
                voiceFlowController?.isInSttBreathingRoom() == true
            },
            shouldBlockQueuedGuidance = {
                waitingForTouchConfirm ||
                    touchConfirmInProgress ||
                    sttManager?.isListening() == true
            }
        )
        beepFeedbackController = BeepFeedbackController()
        beepFeedbackController.init()
        speakerVerificationManager = SpeakerVerificationManager(requireContext())
        voiceActivityDetector = VoiceActivityDetector(requireContext())

        ttsManager?.init { success ->
            requireActivity().runOnUiThread {
                if (success) {
                    voiceFlowController = VoiceFlowController(
                        ttsManager = ttsManager!!,
                        onStateChanged = { _, _ -> requireActivity().runOnUiThread { updateVoiceFlowButtons() } },
                        onRequestStartStt = {
                            requireActivity().runOnUiThread {
                                val reason = currentSttGateReason()
                                Log.i(TAG, "VOICE_DIAG_REQUEST_STT state=${voiceFlowController?.currentState} reason=$reason waitingTouch=$waitingForTouchConfirm")
                                startSpeakerVerifiedStt(reason)
                            }
                        },
                        onStartSearch = { productName ->
                            requireActivity().runOnUiThread {
                                Log.i(TAG, "VOICE_DIAG_START_SEARCH productName=$productName state=${voiceFlowController?.currentState}")
                                onStartSearchFromVoiceFlow(productName)
                            }
                        },
                        onProductNameEntered = { spoken ->
                            Log.i(TAG, "VOICE_DIAG_PRODUCT_NAME_ENTERED spoken=$spoken state=${voiceFlowController?.currentState}")
                            resolveSpokenProductThenConfirmVoice(spoken)
                        }
                    )
                    voiceFlowController?.start()
                    tryStartDetectionWithPendingTarget()
                }
            }
        }

        sttManager = STTManager(
            context = requireContext(),
            onResult = { text ->
                requireActivity().runOnUiThread {
                    Log.i(TAG, "VOICE_DIAG_STT_RESULT text=$text state=${voiceFlowController?.currentState} waitingTouch=$waitingForTouchConfirm touchConfirm=$touchConfirmInProgress")
                    if (waitingForTouchConfirm) {
                        waitingForTouchConfirm = false
                        handleTouchConfirmYesNo(text)
                        return@runOnUiThread
                    }
                    // 터치확인 '예' 직후 onResult로 같은 "예"가 오면 상품명으로 넘기지 않음 (찾으시는 상품이 예 맞나요? 방지)
                    val nowStt = System.currentTimeMillis()
                    if (isShortYesLike(text) && (nowStt - lastTouchConfirmHandledTimeMs) < 3000L) {
                        lastTouchConfirmHandledTimeMs = 0L
                        voiceFlowController?.notifySttEnded()
                        return@runOnUiThread
                    }
                    voiceFlowSttRetryCount = 0
                    if (voiceFlowController?.currentState == VoiceFlowController.VoiceFlowState.WAITING_PRODUCT_NAME) {
                        voiceConfirmSilentRetryCount = 0
                    }
                    voiceFlowController?.onSttResult(text)
                    voiceFlowController?.notifySttEnded()
                }
            },
            onError = { msg ->
                requireActivity().runOnUiThread {
                    Log.w(TAG, "VOICE_DIAG_STT_ERROR msg=$msg state=${voiceFlowController?.currentState} waitingTouch=$waitingForTouchConfirm")
                    if (waitingForTouchConfirm) {
                        if (System.currentTimeMillis() - touchConfirmAskedTime >= TOUCH_CONFIRM_ANSWER_WAIT_MS) {
                            waitingForTouchConfirm = false
                            touchActive = false
                            startPositionAnnounce()
                            Toast.makeText(requireContext(), "음성 인식 실패. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                        } else {
                            view?.postDelayed({ if (waitingForTouchConfirm) startSpeakerVerifiedStt("product_search_touch_confirm_retry") }, 400L)
                        }
                    } else {
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onErrorWithCode = { msg, errorCode ->
                requireActivity().runOnUiThread {
                    val state = voiceFlowController?.currentState
                    Log.w(TAG, "VOICE_DIAG_STT_ERROR_CODE code=$errorCode msg=$msg state=$state waitingTouch=$waitingForTouchConfirm voiceRetry=$voiceFlowSttRetryCount touchRetry=$touchConfirmSttRetryCount confirmSilentRetry=$voiceConfirmSilentRetryCount")
                    val isNoMatchOrTimeout =
                        errorCode == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                            errorCode == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    val isSttWaiting = waitingForTouchConfirm ||
                        state == VoiceFlowController.VoiceFlowState.WAITING_PRODUCT_NAME ||
                        state == VoiceFlowController.VoiceFlowState.WAITING_CONFIRMATION

                    if (isNoMatchOrTimeout && isSttWaiting) {
                        val isTouchConfirm = waitingForTouchConfirm
                        val isVoiceConfirm = state == VoiceFlowController.VoiceFlowState.WAITING_CONFIRMATION
                        val retryCount = when {
                            isTouchConfirm -> touchConfirmSttRetryCount
                            else -> voiceFlowSttRetryCount
                        }
                        if (isTouchConfirm && retryCount < 2) {
                            touchConfirmSttRetryCount++
                            view?.postDelayed({ if (waitingForTouchConfirm) startSpeakerVerifiedStt("product_search_touch_confirm_retry") }, 400L)
                            return@runOnUiThread
                        }
                        if (isVoiceConfirm && voiceConfirmSilentRetryCount < 2) {
                            voiceConfirmSilentRetryCount++
                            view?.postDelayed({
                                if (voiceFlowController?.currentState != VoiceFlowController.VoiceFlowState.WAITING_CONFIRMATION) return@postDelayed
                                startSpeakerVerifiedStt("product_search_command_retry")
                            }, 400L)
                            return@runOnUiThread
                        }
                        if (retryCount < STT_MAX_RETRIES) {
                            if (isTouchConfirm) touchConfirmSttRetryCount++ else voiceFlowSttRetryCount++
                            speak("$msg") {
                                requireActivity().runOnUiThread {
                                    speak("다시 말해주세요.") {
                                        requireActivity().runOnUiThread {
                                            view?.postDelayed({ startSpeakerVerifiedStt("product_search_command_retry") }, 800L)
                                        }
                                    }
                                }
                            }
                        } else {
                            if (isTouchConfirm) {
                                if (System.currentTimeMillis() - touchConfirmAskedTime < TOUCH_CONFIRM_ANSWER_WAIT_MS) {
                                    view?.postDelayed({ if (waitingForTouchConfirm) startSpeakerVerifiedStt("product_search_touch_confirm_retry") }, 400L)
                                    return@runOnUiThread
                                }
                                touchConfirmSttRetryCount = 0
                                waitingForTouchConfirm = false
                                touchActive = false
                                startPositionAnnounce()
                            } else {
                                voiceFlowSttRetryCount = 0
                                voiceConfirmSilentRetryCount = 0
                            }
                            speak("$msg") {
                                if (isTouchConfirm) {
                                    speak("음성 입력 버튼을 눌러 다시 시작해주세요.") {}
                                } else {
                                    speak(VoicePrompts.PROMPT_TOUCH_RESTART) {
                                        voiceFlowController?.start()
                                        transitionToSearching(isNewSearchSession = true)
                                        currentTargetLabel.set("")
                                        updateSearchTargetLabel()
                                    }
                                }
                            }
                        }
                        return@runOnUiThread
                    }

                    val isRetryable = errorCode == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    val isVoiceFlowWaiting = state == VoiceFlowController.VoiceFlowState.WAITING_PRODUCT_NAME ||
                        state == VoiceFlowController.VoiceFlowState.WAITING_CONFIRMATION
                    if (isRetryable && isVoiceFlowWaiting) {
                        view?.postDelayed({ startSpeakerVerifiedStt("product_search_command_retry") }, 800L)
                    }
                }
            },
            onListeningChanged = { _ ->
                requireActivity().runOnUiThread {  }
            },
            onPartialResult = { text ->
                requireActivity().runOnUiThread {
                    if (text.isNullOrBlank()) return@runOnUiThread
                    val shortYes = isShortYesLike(text)
                    if (!shortYes) return@runOnUiThread
                    val state = voiceFlowController?.currentState
                    val isConfirmWaiting = state == VoiceFlowController.VoiceFlowState.WAITING_CONFIRMATION
                    if (isConfirmWaiting || waitingForTouchConfirm) {
                        sttManager?.stopListening()
                        if (waitingForTouchConfirm) {
                            waitingForTouchConfirm = false
                            voiceFlowController?.notifySttEnded()
                            handleTouchConfirmYesNo(text)
                        } else {
                            voiceFlowController?.onSttResult(text)
                            voiceFlowController?.notifySttEnded()
                        }
                    }
                }
            },
            playStartBeep = beepFeedbackController::playListeningStart,
            onListeningEndedReason = { }
        ).also { it.init() }
    }

    private fun updateVoiceFlowButtons() {
        // 터치 확인 단계(상품에 닿았나요?): 예 / 아니오 버튼 표시
        if (waitingForTouchConfirm || touchConfirmInProgress) {
            binding.startOverlay.visibility = View.GONE
            binding.voiceFlowButtons.visibility = View.VISIBLE
            binding.confirmBtn.text = "예"
            binding.reinputBtn.text = "아니오"
            binding.confirmBtn.visibility = View.VISIBLE
            binding.reinputBtn.visibility = View.VISIBLE
            binding.retryBtn.visibility = View.GONE
            return
        }

        val state = voiceFlowController?.currentState ?: run {
            binding.voiceFlowButtons.visibility = View.GONE
            return
        }
        binding.confirmBtn.text = "확인"
        binding.reinputBtn.text = "재입력"
        when (state) {
            VoiceFlowController.VoiceFlowState.CONFIRM_PRODUCT,
            VoiceFlowController.VoiceFlowState.WAITING_CONFIRMATION -> {
                binding.startOverlay.visibility = View.GONE
                binding.voiceFlowButtons.visibility = View.VISIBLE
                binding.confirmBtn.visibility = View.VISIBLE
                binding.reinputBtn.visibility = View.VISIBLE
                binding.retryBtn.visibility = View.GONE
            }
            VoiceFlowController.VoiceFlowState.SEARCH_RESULT -> {
                binding.startOverlay.visibility = View.GONE
                binding.voiceFlowButtons.visibility = View.VISIBLE
                binding.confirmBtn.visibility = View.GONE
                binding.reinputBtn.visibility = View.GONE
                binding.retryBtn.visibility = View.VISIBLE
            }
            VoiceFlowController.VoiceFlowState.SEARCH_FAILED -> {
                binding.startOverlay.visibility = View.GONE
                binding.voiceFlowButtons.visibility = View.VISIBLE
                binding.confirmBtn.visibility = View.GONE
                binding.reinputBtn.visibility = View.GONE
                binding.retryBtn.visibility = View.VISIBLE
            }
            VoiceFlowController.VoiceFlowState.APP_START -> {
                // 검색 중 또는 타겟 락(닿았나요? 아니오 후 재탐지) 중이면 시작 버튼 숨김
                binding.startOverlay.visibility = if (searchState == SearchState.SEARCHING || searchState == SearchState.LOCKED) View.GONE else View.VISIBLE
                binding.voiceFlowButtons.visibility = View.GONE
            }
            else -> {
                binding.startOverlay.visibility = View.GONE
                binding.voiceFlowButtons.visibility = View.GONE
            }
        }
    }

    private fun onStartSearchFromVoiceFlow(productName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val startedAtMs = System.currentTimeMillis()
            Log.i(TAG, "VOICE_DIAG_START_SEARCH_REQUEST spoken=$productName")
            val targetClass = mapSpokenToClass(productName)
            Log.i(TAG, "VOICE_DIAG_START_SEARCH_MAPPED spoken=$productName targetClass=$targetClass elapsedMs=${System.currentTimeMillis() - startedAtMs}")
            if (productName.isNotBlank() && targetClass.isBlank()) {
                voiceSearchTargetLabel = null
                val failReason = "인식된 말을 상품 목록에서 찾지 못했어요. '$productName'"
                speak(failReason) { speak(VoicePrompts.PROMPT_PRODUCT_RECOGNITION_FAILED) { } }
                return@launch
            }
            cancelSearchTimeout()
            transitionToSearching(isNewSearchSession = true)
            voiceSearchTargetLabel = targetClass
            currentTargetLabel.set(targetClass)
            startCamera()
            startSearchTimeout()
            // Search history is recorded once when a target is chosen.
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val repo = SearchHistoryRepository(AppDatabase.getInstance(requireContext().applicationContext))
                repo.insert(SearchHistoryItem(query = productName.trim(), classLabel = targetClass, searchedAt = System.currentTimeMillis(), source = "voice"))
            }
        }
    }

    private suspend fun mapSpokenToClass(spoken: String): String {
        val startedAtMs = System.currentTimeMillis()
        Log.i(TAG, "VOICE_DIAG_MAP_START spoken=$spoken")
        if (spoken.isBlank()) {
            Log.i(TAG, "VOICE_DIAG_MAP_MISS reason=blank elapsedMs=${System.currentTimeMillis() - startedAtMs}")
            return ""
        }
        SynonymRepository.findClassByProximity(spoken)?.let {
            Log.i(TAG, "VOICE_DIAG_MAP_HIT source=proximity classLabel=$it elapsedMs=${System.currentTimeMillis() - startedAtMs}")
            return it
        }
        ProductDictionary.findClassByStt(spoken)?.let {
            Log.i(TAG, "VOICE_DIAG_MAP_HIT source=productDictionary classLabel=$it elapsedMs=${System.currentTimeMillis() - startedAtMs}")
            return it
        }
        SynonymRepository.findClassByNameWithFallback(spoken)?.let {
            Log.i(TAG, "VOICE_DIAG_MAP_HIT source=nameFallback classLabel=$it elapsedMs=${System.currentTimeMillis() - startedAtMs}")
            return it
        }
        val s = spoken.trim().lowercase().replace(" ", "")
        for (label in classLabels) {
            val labelNorm = label.lowercase().replace("_", "")
            if (labelNorm.contains(s) || s.contains(labelNorm.take(3))) {
                Log.i(TAG, "VOICE_DIAG_MAP_HIT source=labelScan classLabel=$label elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                return label
            }
        }
        Log.i(TAG, "VOICE_DIAG_MAP_MISS spoken=$spoken elapsedMs=${System.currentTimeMillis() - startedAtMs}")
        return ""
    }

    /**
     * 음성으로 받은 상품명을 클래스로 해석한 뒤, 사전/동의어의 표시명으로 확인 TTS를 재생한다.
     */
    private fun resolveSpokenProductThenConfirmVoice(spoken: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val trimmed = spoken.trim()
            val startedAtMs = System.currentTimeMillis()
            Log.i(TAG, "VOICE_DIAG_CONFIRM_RESOLVE_START spoken=$trimmed")
            val targetClass = mapSpokenToClass(trimmed)
            withContext(Dispatchers.Main.immediate) {
                if (!isAdded) return@withContext
                val vfc = voiceFlowController ?: return@withContext
                if (vfc.currentState != VoiceFlowController.VoiceFlowState.RESOLVING_PRODUCT_FOR_CONFIRM) {
                    Log.i(TAG, "VOICE_DIAG_CONFIRM_RESOLVE_SKIPPED spoken=$trimmed targetClass=$targetClass state=${vfc.currentState} elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                    return@withContext
                }
                if (trimmed.isNotBlank() && targetClass.isBlank()) {
                    Log.i(TAG, "VOICE_DIAG_CONFIRM_RESOLVE_UNMAPPED spoken=$trimmed elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                    vfc.resetVoiceFlowAfterUnmappedProduct()
                    val failReason = "인식된 말을 상품 목록에서 찾지 못했어요. '$trimmed'"
                    speak(failReason) { speak(VoicePrompts.PROMPT_PRODUCT_RECOGNITION_FAILED) { } }
                    return@withContext
                }
                currentTargetLabel.set(targetClass)
                val displayName = SynonymRepository.getDisplayName(targetClass)
                    ?: if (ProductDictionary.isLoaded()) ProductDictionary.getDisplayNameKo(targetClass) else targetClass
                Log.i(TAG, "VOICE_DIAG_CONFIRM_RESOLVE_DONE spoken=$trimmed targetClass=$targetClass displayName=$displayName elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                vfc.speakResolvedProductConfirmation(displayName)
            }
        }
    }

    private fun isShortYesLike(text: String): Boolean {
        val t = text.trim().lowercase().replace(" ", "")
        if (t.length > 6) return false
        return t.contains("예") || t.contains("네") || t.contains("내") || t.contains("응") ||
            t.contains("맞") || t.contains("그래") || t.contains("좋아") || t == "yes" || t == "y"
    }

    private fun handleTouchConfirmYesNo(text: String) {
        lastTouchConfirmHandledTimeMs = System.currentTimeMillis()
        val t = text.trim().lowercase().replace(" ", "")
        val isYes = t.contains("예") || t.contains("네") || t.contains("내") || t.contains("응") ||
            t.contains("맞") || t.contains("그래") || t.contains("좋아") || t.contains("찾았") || t.contains("닿았") ||
            t == "yes" || t == "y"
        val isExplicitNo = t.contains("아니") || t.contains("아직") || t.contains("없어") || t.contains("못 찾") || t == "no" || t == "n"
        when {
            isYes -> startHeldItemVerification()
            isExplicitNo -> resetTouchConfirmAndRetrack()
            else -> speak(VoicePrompts.PROMPT_TOUCH_RESTART, urgent = true, isAutoGuidance = false) {
                requireActivity().runOnUiThread {
                    touchConfirmInProgress = false
                    waitingForTouchConfirm = false
                    updateVoiceFlowButtons()
                    performKillAllResetFromTouch()
                }
            }
        }
    }

    private fun startHeldItemVerification() {
        requireActivity().runOnUiThread {
            sttManager?.cancelListening()
            waitingForTouchConfirm = false
            touchConfirmScheduled = true
            touchActive = false
            touchFrameCount = 0
            releaseFrameCount = 0
            updateVoiceFlowButtons()
            speak("상품을 집은 뒤 카메라에 비춰주세요.", urgent = true, isAutoGuidance = false) {
                requireActivity().runOnUiThread {
                    if (verifyHeldItemFlow.start(lockedTargetLabel)) {
                        touchConfirmInProgress = false
                        binding.overlayView.setFrozen(false)
                        updateVoiceFlowButtons()
                    } else {
                        resetTouchConfirmAndRetrack()
                    }
                }
            }
        }
    }

    /** 긍정 대답 또는 재시작 시: IDLE·타겟 삭제·전역 리셋·카메라 재시작(일반 프리뷰). runOnUiThread 내부에서 호출. */
    private fun performKillAllResetFromTouch() {
        searchState = SearchState.IDLE
        currentTargetLabel.set("")
        lockedTargetLabel = ""
        resetGlobalState()
        stopCamera()
        _binding?.overlayView?.setDetections(emptyList(), 0, 0)
        _binding?.overlayView?.setFrozen(false)
        updateSearchTargetLabel()
        updateVoiceFlowButtons()
        startCamera()
    }

    /** '손 닿았나요?'에 부정 답변 시: 쿨다운·롤백 후 4초간 TOUCH_CONFIRM 재진입 락 */
    private fun resetTouchConfirmAndRetrack() {
        requireActivity().runOnUiThread {
            guidanceStateMachine.verificationFailed()
            sttManager?.cancelListening()
            touchConfirmInProgress = false
            touchConfirmScheduled = false
            waitingForTouchConfirm = false
            touchActive = false
            touchFrameCount = 0
            releaseFrameCount = 0
            reachDistanceFrameCount = 0
            missedFramesCount = 0
            updateVoiceFlowButtons()
            voiceFlowController?.notifySttEnded()
            speak("다시 위치를 확인합니다.", urgent = true, isAutoGuidance = false) {
                requireActivity().runOnUiThread {
                    startPositionAnnounce()
                }
            }
        }
    }

    private fun enterTouchConfirm() {
        if (touchConfirmInProgress) return
        guidanceStateMachine.startVerification()
        touchConfirmInProgress = true
        touchConfirmScheduled = false
        waitingForTouchConfirm = true
        touchConfirmAskedTime = System.currentTimeMillis()
        touchConfirmSttRetryCount = 0
        hapticFeedbackController.playDefault()
        requireActivity().runOnUiThread { updateVoiceFlowButtons() }
        speak("상품에 닿았나요? 닿았으면 예라고 말해주세요.", urgent = true, isAutoGuidance = false) {
            requireActivity().runOnUiThread { startSpeakerVerifiedStt("product_search_touch_confirm") }
        }
    }

    private fun resetToIdleFromTouch() {
        requireActivity().runOnUiThread {
            touchConfirmInProgress = false
            touchConfirmScheduled = false
            waitingForTouchConfirm = false
            touchActive = false
            touchFrameCount = 0
            releaseFrameCount = 0
            performKillAllResetFromTouch()
            speak(VoicePrompts.PROMPT_IDLE_TOUCH, urgent = true, isAutoGuidance = false)
        }
    }

    private fun startSearchTimeout() {
        cancelSearchTimeout()
        searchTimeoutAborted = false
        searchTimeoutRunnable = Runnable {
            if (searchTimeoutAborted || searchState == SearchState.LOCKED) return@Runnable
            if (voiceFlowController?.currentState == VoiceFlowController.VoiceFlowState.SEARCHING_PRODUCT) {
                requireActivity().runOnUiThread {
                    if (searchTimeoutAborted || searchState == SearchState.LOCKED) return@runOnUiThread
                    voiceSearchTargetLabel = null
                    voiceFlowController?.onSearchComplete(false)
                }
            }
        }
        searchTimeoutHandler.postDelayed(searchTimeoutRunnable!!, SEARCH_TIMEOUT_MS)
    }

    private fun cancelSearchTimeout() {
        searchTimeoutAborted = true
        searchTimeoutRunnable?.let { searchTimeoutHandler.removeCallbacks(it) }
        searchTimeoutRunnable = null
    }

    /** 위치 안내: 방향/거리·즉시 안내는 프레임에서 처리. runnable은 15초 주기 알림만. */
    private fun startPositionAnnounce() {
        stopPositionAnnounce()
        lastPositionPeriodicReminderMs = System.currentTimeMillis()
        lastAnnouncedZone = null
        lastAnnouncedInReach = null
        pendingAnnounceZone = null
        pendingAnnounceInReach = null
        pendingAnnounceSinceMs = 0L
        positionAnnounceRunnable = object : Runnable {
            override fun run() {
                if (waitingForTouchConfirm) {
                    searchTimeoutHandler.postDelayed(this, POSITION_ANNOUNCE_NON_CENTER_POLL_MS)
                    return
                }
                val box = frozenBox ?: run {
                    searchTimeoutHandler.postDelayed(this, POSITION_ANNOUNCE_NON_CENTER_POLL_MS)
                    return
                }
                val w = frozenImageWidth
                val h = frozenImageHeight
                if (w <= 0 || h <= 0) {
                    searchTimeoutHandler.postDelayed(this, POSITION_ANNOUNCE_NON_CENTER_POLL_MS)
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastPositionPeriodicReminderMs >= POSITION_PERIODIC_REMINDER_MS) {
                    lastPositionPeriodicReminderMs = now
                    val zone = currentZone
                    val distMm = currentDistMm
                    val periodicMsg = if (zone == "정면") {
                        voiceFlowController?.getCenterDistanceMessage(distMm)
                            ?: "상품이 정면에 있습니다. 방향을 유지한 채 앞으로 걸어가세요."
                    } else {
                        val displayName = if (ProductDictionary.isLoaded()) ProductDictionary.getDisplayNameKo(lockedTargetLabel) else lockedTargetLabel
                        voiceFlowController?.getPositionAnnounceMessage(displayName, box.rect, w, h, distMm)
                    }
                    if (!periodicMsg.isNullOrBlank()) speak(periodicMsg, urgent = true, isAutoGuidance = false)
                }
                searchTimeoutHandler.postDelayed(this, POSITION_ANNOUNCE_NON_CENTER_POLL_MS)
            }
        }
        searchTimeoutHandler.post(positionAnnounceRunnable!!)
    }

    /** LOCKED 시 매 프레임: 방향·거리 변수 갱신, 화면 표시, 구역이 ZONE_STABLE_MS 동안 안정되면 안내. */
    private fun updatePositionStateAndAnnounceIfStable() {
        if (isTargetLockFeedbackPlaying) return
        val box = frozenBox ?: return
        val w = frozenImageWidth
        val h = frozenImageHeight
        if (w <= 0 || h <= 0) return
        val rect = box.rect
        val zone = voiceFlowController?.getZoneName(rect, w, h) ?: return
        val distMm = computeDistanceMm(box.rect.width(), w, lockedTargetLabel, currentZoomRatio)
        val inReach = if (zone == "정면") distMm <= REACH_DISTANCE_MM else null

        currentZone = zone
        currentDistMm = distMm
        currentInReach = inReach

        val now = System.currentTimeMillis()
        val stateKey = Pair(zone, inReach)
        val pendingKey = Pair(pendingAnnounceZone, pendingAnnounceInReach)
        if (stateKey != pendingKey) {
            pendingAnnounceZone = zone
            pendingAnnounceInReach = inReach
            pendingAnnounceSinceMs = now
        }
        val elapsed = now - pendingAnnounceSinceMs
        val stable = stateKey == pendingKey && elapsed >= ZONE_STABLE_MS
        val lastKey = Pair(lastAnnouncedZone, lastAnnouncedInReach)
        val shouldAnnounce = stable && stateKey != lastKey

        if (now - lastPositionStateLogMs >= 250L) lastPositionStateLogMs = now

        if (shouldAnnounce) {
            lastAnnouncedZone = zone
            lastAnnouncedInReach = inReach
            val message = if (zone == "정면") {
                voiceFlowController?.getCenterDistanceMessage(distMm)
                    ?: "상품이 정면에 있습니다. 방향을 유지한 채 앞으로 걸어가세요."
            } else {
                val displayName = if (ProductDictionary.isLoaded()) ProductDictionary.getDisplayNameKo(lockedTargetLabel) else lockedTargetLabel
                voiceFlowController?.getPositionAnnounceMessage(displayName, box.rect, w, h, distMm)
            }
            if (!message.isNullOrBlank()) speak(message, urgent = true, isAutoGuidance = false)
        }
    }

    private fun stopPositionAnnounce() {
        positionAnnounceRunnable?.let { searchTimeoutHandler.removeCallbacks(it) }
        positionAnnounceRunnable = null
    }

    private fun initGyroTrackingManager() {
        gyroManager = GyroTrackingManager(
            context = requireContext(),
            onBoxUpdate = { update ->
                requireActivity().runOnUiThread {
                    val box = OverlayView.DetectionBox(
                        label = lockedTargetLabel,
                        confidence = 0.9f,
                        rect = update.rect,
                        topLabels = listOf(lockedTargetLabel to 90),
                        rotationDegrees = update.rotationDegrees
                    )
                    frozenBox = box
                    _binding?.overlayView?.setDetections(listOf(box), frozenImageWidth, frozenImageHeight)
                }
            },
            onTrackingLost = { transitionToSearching() }
        )
        gyroManager.onDeltaYawTooHigh = {
            requireActivity().runOnUiThread {
                if (!isTargetLockFeedbackPlaying && searchState == SearchState.LOCKED) {
                    speak("천천히 움직여주세요", false)
                }
            }
        }
    }

    private fun initMediaPipeHands() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ -> latestHandsResult.set(result) }
                .setErrorListener { e -> Log.e(TAG, "Hands LIVE_STREAM error", e) }
                .build()
            handLandmarker = HandLandmarker.createFromOptions(requireContext(), options)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe Hands 초기화 실패", e)
        }
    }

    private fun getTargetLabel(): String = currentTargetLabel.get().trim()

    private fun updateSearchTargetLabel() {
        // 검색 타겟 라벨 UI 제거로 별도 처리 없음
    }

    private fun hasSpecificTarget(): Boolean = getTargetLabel().isNotEmpty()

    private fun stopCamera() {
        stopScanning()
        try {
            ProcessCameraProvider.getInstance(requireContext()).get().unbindAll()
            camera = null
        } catch (_: Exception) {}
    }

    private fun startScanning() {
        // 줌 미사용: 스캔 줌 비활성화
    }

    private fun stopScanning() {
        isScanning = false
        scanHandler.removeCallbacksAndMessages(null)
    }

    /** 줌 보정 거리: raw = (Focal * Physical_mm) / Real_Pixel_Width, 반환 = raw * DISTANCE_CALIBRATION_FACTOR. boxWidthPx는 이미지 가로 축이므로 focal도 imageWidth 기준. */
    private fun computeDistanceMm(boxWidthPx: Float, imageWidth: Int, label: String, zoomRatio: Float = 1f): Float {
        if (boxWidthPx <= 0f || imageWidth <= 0) return Float.MAX_VALUE
        val zoom = zoomRatio.coerceAtLeast(0.1f)
        val realPixelWidth = boxWidthPx / zoom
        val focalLengthPx = imageWidth * FOCAL_LENGTH_FACTOR
        // 1) Local dimensions memory cache (synced from server), 2) fallback heuristic.
        val physicalWidthMm = ProductDimensionRepository.getWidthMmByClassId(label)
            ?: ProductDictionary.getPhysicalWidthMm(label)
        val rawDistanceMm = (focalLengthPx * physicalWidthMm) / realPixelWidth
        return rawDistanceMm * DISTANCE_CALIBRATION_FACTOR
    }

    /** 1순위: 방향(9구역) → 2순위: 5초 모드 락 → 3순위: 거리(55cm 손 뻗기). */
    private fun processDistanceGuidance(box: OverlayView.DetectionBox, imageWidth: Int, imageHeight: Int) {
        if (isTargetLockFeedbackPlaying) return
        if (waitingForTouchConfirm || touchConfirmInProgress) return
        val totalArea = imageWidth * imageHeight
        if (totalArea <= 0 || imageWidth <= 0) return
        val boxCenterX = (box.rect.left + box.rect.right) / 2f
        val centerXNorm = boxCenterX / imageWidth
        val centerYNorm = (box.rect.top + box.rect.bottom) / 2f / imageHeight.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        requireActivity().runOnUiThread {
            val boxWidthPx = box.rect.width()
            val zoomRatio = currentZoomRatio
            val distMm = computeDistanceMm(boxWidthPx, imageWidth, box.label, zoomRatio)
            val zone = voiceFlowController?.getZoneName(box.rect, imageWidth, imageHeight)

            if (voiceFlowController?.isInSttBreathingRoom() == true) return@runOnUiThread

            // 9구역 "정면"과 동일: centerXNorm [1/3, 2/3] = 정면 (진동/손 뻗기), 그 외 좌/우
            val centerLeftBound = 1f / 3f
            val centerRightBound = 2f / 3f
            val inCenterZone = centerXNorm in centerLeftBound..centerRightBound &&
                centerYNorm in centerLeftBound..centerRightBound
            if (!inCenterZone) guidanceStateMachine.targetLocked()

            when {
                centerXNorm < centerLeftBound -> {
                    wasInCenterZone = false
                    wasInReachDistance = false
                    reachAnnouncedThisSession = false
                    reachDistanceFrameCount = 0
                    if (now - lastDirectionGuidanceTime >= DIRECTION_GUIDANCE_COOLDOWN_MS) {
                        lastDirectionGuidanceTime = now
                        val msg = voiceFlowController?.getPositionAnnounceMessage(box.label, box.rect, imageWidth, imageHeight, distMm)
                        if (msg != null) speak(msg, false)
                    }
                    return@runOnUiThread
                }
                centerXNorm > centerRightBound -> {
                    wasInCenterZone = false
                    wasInReachDistance = false
                    reachAnnouncedThisSession = false
                    reachDistanceFrameCount = 0
                    if (now - lastDirectionGuidanceTime >= DIRECTION_GUIDANCE_COOLDOWN_MS) {
                        lastDirectionGuidanceTime = now
                        val msg = voiceFlowController?.getPositionAnnounceMessage(box.label, box.rect, imageWidth, imageHeight, distMm)
                        if (msg != null) speak(msg, false)
                    }
                    return@runOnUiThread
                }
                inCenterZone -> {
                    // 진동+멘트는 두 상황만: ①가운데 맞았을 때 ②가운데 유지한 채 55cm 안으로 들어왔을 때
                    val distanceMm = distMm
                    val justEnteredCenter = !wasInCenterZone
                    if (justEnteredCenter) guidanceStateMachine.targetCentered()
                    // ① 가운데 맞음: 진동+멘트 한 세트. 55cm 이하면 "손을 뻗어" 한 번만 쓰기 위해 플래그 설정
                    if (justEnteredCenter && now - lastVibrateTimeMs >= VIBRATE_COOLDOWN_MS) {
                        val centerMsg = voiceFlowController?.getCenterDistanceMessage(distMm)
                            ?: "상품이 정면에 있습니다. 방향을 유지한 채 앞으로 걸어가세요."
                        speak(centerMsg, urgent = true)
                        hapticFeedbackController.playCenterEntered()
                        lastVibrateTimeMs = now
                        if (distanceMm <= REACH_DISTANCE_MM) reachAnnouncedThisSession = true
                        lastAnnouncedZone = "정면"
                        lastAnnouncedInReach = distanceMm <= REACH_DISTANCE_MM
                    }
                    when {
                        distanceMm <= REACH_DISTANCE_MM -> {
                            wasInReachDistance = true
                            if (missedFramesCount > 0) {
                                reachDistanceFrameCount = 0
                            } else if (!reachAnnouncedThisSession) {
                                reachDistanceFrameCount++
                                if (reachDistanceFrameCount >= REACH_STABILITY_FRAMES) {
                                    reachAnnouncedThisSession = true
                                    reachDistanceFrameCount = 0
                                    speak("상품이 정면에 있습니다. 손을 뻗어 확인해보세요.", urgent = true)
                                    lastAnnouncedZone = "정면"
                                    lastAnnouncedInReach = true
                                    if (now - lastVibrateTimeMs >= VIBRATE_COOLDOWN_MS) {
                                        hapticFeedbackController.playDefault(400L)
                                        lastVibrateTimeMs = now
                                    }
                                }
                            }
                        }
                        else -> {
                            reachDistanceFrameCount = 0
                            reachAnnouncedThisSession = false  // 55cm 벗어나면 다음 진입 시 한 번 더 말할 수 있게
                            if (wasInReachDistance) {
                                wasInReachDistance = false
                                lastDirectionGuidanceTime = now
                                val msg = voiceFlowController?.getCenterDistanceMessage(distMm)
                                    ?: "상품이 정면에 있습니다. 방향을 유지한 채 앞으로 걸어가세요."
                                speak(msg, urgent = true, isAutoGuidance = false)
                                lastAnnouncedZone = "정면"
                                lastAnnouncedInReach = false
                            } else if (!justEnteredCenter && lastAnnouncedZone != "정면" && now - lastDirectionGuidanceTime >= DIRECTION_GUIDANCE_COOLDOWN_MS) {
                                lastDirectionGuidanceTime = now
                                val msg = voiceFlowController?.getCenterDistanceMessage(distMm)
                                    ?: "상품이 정면에 있습니다. 방향을 유지한 채 앞으로 걸어가세요."
                                speak(msg, urgent = true, isAutoGuidance = false)
                                lastAnnouncedZone = "정면"
                                lastAnnouncedInReach = false
                            }
                        }
                    }
                    wasInCenterZone = true
                }
                else -> {
                    wasInCenterZone = false
                    wasInReachDistance = false
                    reachAnnouncedThisSession = false
                    reachDistanceFrameCount = 0
                    if (now - lastDirectionGuidanceTime >= DIRECTION_GUIDANCE_COOLDOWN_MS) {
                        lastDirectionGuidanceTime = now
                        val msg = voiceFlowController?.getPositionAnnounceMessage(box.label, box.rect, imageWidth, imageHeight, distMm)
                        if (msg != null) speak(msg, false)
                    }
                }
            }
        }
    }

    private fun findTrackedTarget(
        detections: List<OverlayView.DetectionBox>,
        targetLabel: String,
        prevBox: OverlayView.DetectionBox?,
        minConfidence: Float = TARGET_CONFIDENCE_THRESHOLD * 0.5f
    ): OverlayView.DetectionBox? {
        val target = targetLabel.trim()
        if (target.isBlank()) return null
        val candidates = detections.filter { d ->
            (d.label.trim().equals(target, ignoreCase = true) ||
                d.topLabels.any { it.first.trim().equals(target, ignoreCase = true) }) &&
            d.confidence >= minConfidence
        }.map { d ->
            if (d.label.trim().equals(target, ignoreCase = true)) d
            else {
                val conf = d.topLabels.find { it.first.trim().equals(target, ignoreCase = true) }?.second?.div(100f) ?: d.confidence
                d.copy(label = target, confidence = conf, topLabels = listOf(target to (conf * 100).toInt()))
            }
        }
        if (candidates.isEmpty()) return null
        val predicted = gyroManager.getPredictedRect()
        return when {
            predicted != null -> candidates.minByOrNull { box ->
                val cx = (box.rect.left + box.rect.right) / 2f
                val cy = (box.rect.top + box.rect.bottom) / 2f
                val px = (predicted.left + predicted.right) / 2f
                val py = (predicted.top + predicted.bottom) / 2f
                (cx - px) * (cx - px) + (cy - py) * (cy - py)
            }
            prevBox != null -> candidates.maxByOrNull { iou(it.rect, prevBox.rect) }
            else -> candidates.maxByOrNull { it.rect.width() * it.rect.height() }
        }
    }

    private fun filterDetectionsByTarget(detections: List<OverlayView.DetectionBox>, targetLabel: String): List<OverlayView.DetectionBox> {
        val target = targetLabel.trim()
        if (target.isBlank()) return emptyList()
        return detections.mapNotNull { box ->
            val targetConf = when {
                box.label.trim().equals(target, ignoreCase = true) -> box.confidence
                else -> box.topLabels.find { it.first.trim().equals(target, ignoreCase = true) }?.second?.div(100f)
            }
            if (targetConf != null && targetConf >= TARGET_TRACKING_CONFIDENCE_THRESHOLD) {
                if (box.label.trim().equals(target, ignoreCase = true)) box
                else box.copy(label = target, confidence = targetConf, topLabels = listOf(target to (targetConf * 100).toInt()))
            } else null
        }
    }

    private fun findTargetMatch(detections: List<OverlayView.DetectionBox>, targetLabel: String): Pair<OverlayView.DetectionBox, String>? {
        val target = targetLabel.trim()
        if (target.isBlank()) return null
        val result = detections.mapNotNull { box ->
            val targetConf = when {
                box.label.trim().equals(target, ignoreCase = true) -> box.confidence
                else -> box.topLabels.find { it.first.trim().equals(target, ignoreCase = true) }?.second?.div(100f)
            }
            if (targetConf != null && targetConf >= TARGET_TRACKING_CONFIDENCE_THRESHOLD) {
                val displayBox = if (box.label.trim().equals(target, ignoreCase = true)) box
                else box.copy(label = target, confidence = targetConf, topLabels = listOf(target to (targetConf * 100).toInt()))
                Pair(displayBox, box.label)
            } else null
        }.maxByOrNull { (box, _) -> box.rect.width() * box.rect.height() }
        val now = System.currentTimeMillis()
        if (searchState == SearchState.SEARCHING && now - lastYoloxMatchDiagLogMs >= YOLOX_DIAG_LOG_INTERVAL_MS) {
            lastYoloxMatchDiagLogMs = now
            if (result == null) {
                val best = detections.maxByOrNull { it.confidence }
                Log.i(
                    TAG,
                    "YOLOX_MATCH_DIAG target=$target result=none detections=${detections.size} best=${best?.label}:${best?.confidence} bestTopLabels=${best?.topLabels?.joinToString("|") { "${it.first}:${it.second}" }} trackingThreshold=$TARGET_TRACKING_CONFIDENCE_THRESHOLD lockThreshold=$TARGET_CONFIDENCE_THRESHOLD"
                )
            } else {
                Log.i(
                    TAG,
                    "YOLOX_MATCH_DIAG target=$target result=match matchedConf=${"%.3f".format(result.first.confidence)} actualPrimary=${result.second} detections=${detections.size} belowLock=${result.first.confidence < TARGET_CONFIDENCE_THRESHOLD}"
                )
            }
        }
        return result
    }

    private fun transitionToLocked(box: OverlayView.DetectionBox, imageWidth: Int, imageHeight: Int, actualPrimaryLabel: String? = null, fromTiling: Boolean = false, tilingGridUsed: Int = 2) {
        cancelSearchTimeout()
        requireActivity().runOnUiThread {
            if (searchState == SearchState.LOCKED) return@runOnUiThread
            Log.i(TAG, "YOLOX_LOCKED target=${box.label} conf=${"%.3f".format(box.confidence)} actualPrimary=$actualPrimaryLabel fromTiling=$fromTiling grid=$tilingGridUsed")
            stopScanning()
            useTilingWhenLocked = fromTiling
            if (fromTiling) tilingGridWhenLocked = tilingGridUsed
            searchState = SearchState.LOCKED
            guidanceStateMachine.targetLocked()
            lockedTargetLabel = box.label
            val shouldPlayLockFeedback = !hasPlayedTargetLockFeedbackThisSearchSession
            if (shouldPlayLockFeedback) {
                hasPlayedTargetLockFeedbackThisSearchSession = true
                proximityModeActive = false
                hapticFeedbackController.playTargetLock()
                isTargetLockFeedbackPlaying = true
                speak(
                    "찾았습니다. 멈춰주세요.",
                    urgent = true,
                    isAutoGuidance = false
                ) { // 중괄호는 lambda로, TTS가 끝난 후 실행할 코드 블록을 나타냄
                    isTargetLockFeedbackPlaying = false
                }
            }
            validationFailCount = 0
            lastSuccessfulValidationTimeMs = System.currentTimeMillis() // 락 진입 시점을 성공 시각으로 두어 시간 기반 해제가 즉시 걸리지 않도록 함
            frozenImageWidth = imageWidth
            frozenImageHeight = imageHeight
            ttsGrabPlayed = false
            ttsGrabbedPlayed = false
            ttsAskAnotherPlayed = false
            handsOverlapFrameCount = 0
            pinchGrabFrameCount = 0
            touchFrameCount = 0
            releaseFrameCount = 0
            touchActive = false
            reachDistanceFrameCount = 0
            lastVibrateTimeMs = 0L  // 락 진입 후 중앙 진입/손 뻗기 거리 진동 허용
            wasInCenterZone = false
            wasInReachDistance = false
            reachAnnouncedThisSession = false
            lastTargetBox = box.rect
            if (System.currentTimeMillis() - touchConfirmAskedTime >= TOUCH_CONFIRM_ANSWER_WAIT_MS) {
                touchConfirmInProgress = false
                touchConfirmScheduled = false
                waitingForTouchConfirm = false
            }
            missedFramesCount = 0
            lastTouchMidpointPx = null
            lastTouchTtsTimeMs = 0L
            latestHandsResult.set(null)
            binding.overlayView.setDetections(listOf(box), imageWidth, imageHeight)
            binding.overlayView.setFrozen(true)
            val percent = (box.confidence * 100).toInt().coerceIn(0, 100)
            val actualLabel = actualPrimaryLabel ?: box.label
            val fromVoice = voiceFlowController?.currentState == VoiceFlowController.VoiceFlowState.SEARCHING_PRODUCT
            val searchDurationMs = System.currentTimeMillis() - lastTimeEnteredSearchingMs
            val nowMs = System.currentTimeMillis()
            val sameTargetRecently = actualLabel == lastFoundAnnounceLabel &&
                (nowMs - lastFoundAnnounceTimeMs) < FOUND_ANNOUNCE_COOLDOWN_MS
            val shouldAnnounceDetected = (!hasAnnouncedDetectedThisSearchSession ||
                searchDurationMs >= MIN_SEARCH_DURATION_BEFORE_DETECTED_TTS_MS) && !sameTargetRecently
            var announcedZone: String? = null
            var announcedInReach: Boolean? = null
            if (shouldAnnounceDetected && !ttsDetectedPlayed) {
                ttsDetectedPlayed = true
                hasAnnouncedDetectedThisSearchSession = true
                lastFoundAnnounceTimeMs = nowMs
                lastFoundAnnounceLabel = actualLabel
                val distMm = computeDistanceMm(box.rect.width(), imageWidth, lockedTargetLabel, currentZoomRatio)
                val zone = voiceFlowController?.getZoneName(box.rect, imageWidth, imageHeight)
                val displayNameForPos = if (ProductDictionary.isLoaded()) ProductDictionary.getDisplayNameKo(lockedTargetLabel) else lockedTargetLabel
                val positionMsg = voiceFlowController?.getPositionAnnounceMessage(displayNameForPos, box.rect, imageWidth, imageHeight, distMm)
                if (!positionMsg.isNullOrBlank() && !shouldPlayLockFeedback) {
                    speak(
                        positionMsg,
                        urgent = true,
                        isAutoGuidance = false
                    )
                    announcedZone = zone
                    announcedInReach = if (zone == "정면") distMm <= REACH_DISTANCE_MM else null
                }
            }
            if (fromVoice) {
                val requested = voiceSearchTargetLabel
                val isRequestedProduct = requested.isNullOrBlank() || requested == actualLabel
                if (isRequestedProduct) {
                    voiceSearchTargetLabel = null
                    voiceFlowController?.onSearchComplete(true, actualLabel, box.rect, imageWidth, imageHeight, percent)
                }
            }
            startPositionAnnounce()
            // 방금 구역 안내를 했으면, updatePositionStateAndAnnounceIfStable()에서 같은 구역을 다시 말하지 않도록 기록
            if (announcedZone != null) {
                lastAnnouncedZone = announcedZone
                lastAnnouncedInReach = announcedInReach
                pendingAnnounceZone = announcedZone
                pendingAnnounceInReach = announcedInReach
                pendingAnnounceSinceMs = System.currentTimeMillis()
            }
        }
    }

    private fun transitionToSearching(skipTtsDetectedReset: Boolean = false, isNewSearchSession: Boolean = false) {
        requireActivity().runOnUiThread {
            lastTimeEnteredSearchingMs = System.currentTimeMillis()
            searchTilingTier = 1
            lastTier1NoMatchTimeMs = 0L
            lastTier2NoMatchTimeMs = 0L
            searchFrameCountInTier3 = 0
            lastSearchNoDetectionStartMs = 0L
            searchObjectNotFoundAnnounced = false
            if (isNewSearchSession) {
                hasAnnouncedDetectedThisSearchSession = false
                lastCandidateFeedbackTimeMs = 0L
                hasPlayedTargetLockFeedbackThisSearchSession = false
                lastFoundAnnounceLabel = null
            }
            stopPositionAnnounce()
            searchState = SearchState.SEARCHING
            guidanceStateMachine.startSearching()
            lockedTargetLabel = ""
            pendingLockBox = null
            pendingLockCount = 0
            pendingLockMissCount = 0
            pendingLockStableSinceMs = 0L
            validationFailCount = 0
            wasOccluded = false
            if (!skipTtsDetectedReset) ttsDetectedPlayed = false
            ttsGrabPlayed = false
            ttsGrabbedPlayed = false
            ttsAskAnotherPlayed = false
            handsOverlapFrameCount = 0
            pinchGrabFrameCount = 0
            touchFrameCount = 0
            releaseFrameCount = 0
            touchActive = false
            lastTouchMidpointPx = null
            lastTouchTtsTimeMs = 0L
            lastDirectionGuidanceTime = 0L
            reachDistanceFrameCount = 0
            lastTargetBox = null
            missedFramesCount = 0
            lastSearchPingTime = System.currentTimeMillis()
            latestHandsResult.set(null)
            opticalFlowTracker.reset()
            if (System.currentTimeMillis() - touchConfirmAskedTime >= TOUCH_CONFIRM_ANSWER_WAIT_MS) {
                touchConfirmInProgress = false
                touchConfirmScheduled = false
                waitingForTouchConfirm = false
            }
            gyroManager.stopTracking()
            binding.overlayView.setDetections(emptyList(), 0, 0)
            binding.overlayView.setFrozen(false)
            updateSearchTargetLabel()
            stopHandGuidanceTTS()
            startScanning()
        }
    }

    private fun loadClassLabels() {
        try {
            requireContext().assets.open("classes.txt").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    classLabels = reader.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                }
            }
            Log.i(TAG, "YOLOX_LABELS_LOADED count=${classLabels.size}")
        } catch (e: Exception) {
            Log.e(TAG, "classes.txt 로드 실패", e)
            classLabels = emptyList()
        }
    }

    private fun getClassLabel(classId: Int): String =
        classLabels.getOrNull(classId)?.takeIf { it.isNotBlank() } ?: "Object_$classId"

    private fun letterboxBitmap(bitmap: Bitmap, inputSize: Int): Pair<Bitmap, Float> =
        BitmapUtils.createLetterboxBitmap(bitmap, inputSize)

    /** 타일 겹침 비율 (0.15 = 15%). 경계에 걸친 물체도 한 타일 안에 들어가도록. */
    private val TILING_OVERLAP_RATIO = 0.15f

    /**
     * 전체 프레임을 grid x grid 타일로 자른다. 인접 타일이 [TILING_OVERLAP_RATIO]만큼 겹치도록 stride를 줄인다.
     * YOLOX는 각 타일마다 실행하고, 결과는 (offsetX, offsetY)를 더해 전역 좌표로 쓴다.
     * @return List of (타일 비트맵, 전역 기준 offsetX, offsetY)
     */
    private fun createTiles(bitmap: Bitmap, grid: Int): List<Triple<Bitmap, Int, Int>> {
        val w = bitmap.width
        val h = bitmap.height
        if (w < grid || h < grid) return listOf(Triple(bitmap, 0, 0))
        val tiles = mutableListOf<Triple<Bitmap, Int, Int>>()
        val rawTileW = w / grid
        val rawTileH = h / grid
        val overlapX = (rawTileW * TILING_OVERLAP_RATIO).toInt().coerceIn(1, maxOf(1, rawTileW - 1))
        val overlapY = (rawTileH * TILING_OVERLAP_RATIO).toInt().coerceIn(1, maxOf(1, rawTileH - 1))
        val strideX = rawTileW - overlapX
        val strideY = rawTileH - overlapY
        for (jy in 0 until grid) {
            for (ix in 0 until grid) {
                val left = ix * strideX
                val top = jy * strideY
                val tileW = if (ix == grid - 1) (w - left).coerceAtLeast(1) else rawTileW
                val tileH = if (jy == grid - 1) (h - top).coerceAtLeast(1) else rawTileH
                if (tileW <= 0 || tileH <= 0) continue
                val tile = Bitmap.createBitmap(bitmap, left, top, tileW, tileH)
                tiles.add(Triple(tile, left, top))
            }
        }
        return tiles
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap, size: Int, isNchw: Boolean = false): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * size * size * 3)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        if (isNchw) {
            for (c in 0 until 3) {
                val shift = when (c) { 0 -> 0; 1 -> 8; else -> 16 }
                for (i in pixels.indices) {
                    buffer.putFloat(((pixels[i] shr shift) and 0xFF).toFloat())
                }
            }
        } else {
            for (p in pixels) {
                buffer.putFloat((p and 0xFF).toFloat())
                buffer.putFloat(((p shr 8) and 0xFF).toFloat())
                buffer.putFloat(((p shr 16) and 0xFF).toFloat())
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun initYOLOX() {
        try {
            val modelFile = loadModelFile(YOLOX_MODEL_FILE)
            val options = Interpreter.Options()
            try {
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
                options.setAllowFp16PrecisionForFp32(true)
                requireActivity().runOnUiThread { }
            } catch (e: Exception) {
                Log.e(TAG, "GPU 실패 -> CPU 전환", e)
                options.setNumThreads(4)
                gpuDelegate = null
                requireActivity().runOnUiThread { }
            }
            yoloxInterpreter = Interpreter(modelFile, options)
            val inputShape = yoloxInterpreter?.getInputTensor(0)?.shape()
            val outputShape = yoloxInterpreter?.getOutputTensor(0)?.shape()
            val outputDim = outputShape?.lastOrNull()
            val expectedDim = if (classLabels.isNotEmpty()) classLabels.size + 5 else null
            Log.i(
                TAG,
                "YOLOX_MODEL_LOADED file=$YOLOX_MODEL_FILE"
            )
            Log.i(TAG, "YOLOX_INPUT_SHAPE ${inputShape?.contentToString()}")
            Log.i(TAG, "YOLOX_OUTPUT_SHAPE ${outputShape?.contentToString()}")
            Log.i(
                TAG,
                "YOLOX_CLASS_COUNT_CHECK labels=${classLabels.size} outputDim=$outputDim expectedDim=$expectedDim"
            )
        } catch (e: Exception) {
            Log.e(TAG, "YOLOX 초기화 실패", e)
            requireActivity().runOnUiThread { }
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = requireContext().assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun startCamera() {
        if (_binding == null) return
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1440, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, ImageAnalyzer()) }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer
                )
                scanHandler.postDelayed({ startScanning() }, SCAN_INITIAL_DELAY_MS)
            } catch (e: Exception) {
                Log.e(TAG, "카메라 바인딩 실패", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun isImageTooDark(bitmap: Bitmap, threshold: Int = 28): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return true
        val step = max(1, min(w, h) / 20)
        var sum = 0L
        var count = 0
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val p = bitmap.getPixel(x, y)
                sum += ((p shr 16) and 0xFF) + ((p shr 8) and 0xFF) + (p and 0xFF)
                count++
            }
        }
        return if (count == 0) true else (sum / count) / 3 < threshold
    }

    /**
     * 단일 비트맵에 대해 YOLOX 1회 추론. 반환 박스는 해당 비트맵 좌표계(타일이면 타일 로컬).
     */
    private fun runYOLOXSingle(bitmap: Bitmap): List<OverlayView.DetectionBox> {
        if (yoloxInterpreter == null) return emptyList()
        try {
            val inputShape = yoloxInterpreter!!.getInputTensor(0).shape()
            val isNchw = inputShape.size >= 4 && inputShape[1] == 3
            val inputSize = when {
                isNchw -> inputShape[2]
                inputShape.size >= 4 -> inputShape[1]
                else -> inputShape.last()
            }
            val expectedBytes = 4 * inputSize * inputSize * 3
            val (preprocBitmap, letterboxScale) = letterboxBitmap(bitmap, inputSize)
            val inputBuffer = bitmapToFloatBuffer(preprocBitmap, inputSize, isNchw)
            if (preprocBitmap != bitmap) preprocBitmap.recycle()
            if (inputBuffer.remaining() != expectedBytes) return emptyList()
            val outputShape = yoloxInterpreter!!.getOutputTensor(0).shape()
            val output = Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
            yoloxInterpreter!!.run(inputBuffer, output)
            if (isImageTooDark(bitmap)) return emptyList()
            val targetLabel = when (searchState) {
                SearchState.SEARCHING -> getTargetLabel().takeIf { it.isNotBlank() }
                SearchState.LOCKED -> lockedTargetLabel.takeIf { it.isNotBlank() }
                else -> null
            }
            return parseYOLOXOutput(
                output[0], outputShape[1], outputShape[2],
                bitmap.width, bitmap.height, inputSize, letterboxScaleR = letterboxScale, targetLabel = targetLabel
            )
        } catch (e: Exception) {
            Log.e(TAG, "YOLOX 추론 실패", e)
            return emptyList()
        }
    }

    /** 타일링만 수행. grid=2 → 2x2, grid=3 → 3x3 */
    private fun runYOLOXTiled(bitmap: Bitmap, grid: Int = TILING_GRID): List<OverlayView.DetectionBox> {
        if (!ENABLE_YOLOX_TILING || grid <= 1) return runYOLOXSingle(bitmap)
        val tiles = createTiles(bitmap, grid)
        val allBoxes = mutableListOf<OverlayView.DetectionBox>()
        for ((tile, offsetX, offsetY) in tiles) {
            val boxes = runYOLOXSingle(tile)
            for (box in boxes) {
                val r = box.rect
                allBoxes.add(
                    box.copy(
                        rect = RectF(
                            r.left + offsetX,
                            r.top + offsetY,
                            r.right + offsetX,
                            r.bottom + offsetY
                        )
                    )
                )
            }
            if (tile != bitmap) tile.recycle()
        }
        return mergeOverlappingSameLabel(nms(allBoxes, 0.6f))
    }

    /**
     * LOCKED일 때만 사용. 락 시 풀스케일이면 1x1, 타일링이면 tilingGridWhenLocked(2 또는 3) 그리드로 실행.
     */
    private fun runYOLOX(bitmap: Bitmap): List<OverlayView.DetectionBox> {
        return if (searchState == SearchState.LOCKED && useTilingWhenLocked) {
            runYOLOXTiled(bitmap, tilingGridWhenLocked)
        } else {
            runYOLOXSingle(bitmap)
        }
    }

    private fun parseYOLOXOutput(
        output: Array<FloatArray>,
        dim1: Int, dim2: Int,
        imageWidth: Int, imageHeight: Int,
        inputSize: Int,
        letterboxScaleR: Float? = null,
        targetLabel: String? = null
    ): List<OverlayView.DetectionBox> {
        val numBoxes: Int
        val boxSize: Int
        val isRowMajor: Boolean
        if (dim1 >= dim2) {
            numBoxes = dim1
            boxSize = dim2
            isRowMajor = true
        } else {
            numBoxes = dim2
            boxSize = dim1
            isRowMajor = false
        }
        val isNormalized = if (isRowMajor && output.isNotEmpty() && output[0].size >= 4) {
            maxOf(output[0][0], output[0][1], output[0][2], output[0][3]) <= 1.5f
        } else if (!isRowMajor && output.size >= 4) {
            maxOf(output[0][0], output[1][0], output[2][0], output[3][0]) <= 1.5f
        } else true

        val expectedObjectnessDim = if (classLabels.isNotEmpty()) classLabels.size + 5 else -1
        val hasObjectness = boxSize == expectedObjectnessDim || boxSize == 85 || boxSize == 58 || boxSize == 55 || boxSize == 54
        val scoreStart = if (hasObjectness) 5 else 4
        val classCount = (boxSize - scoreStart).coerceAtLeast(1)
        if (!hasLoggedYoloxClassMismatch && classLabels.isNotEmpty() && classLabels.size != classCount) {
            hasLoggedYoloxClassMismatch = true
            Log.w(
                TAG,
                "YOLOX_CLASS_COUNT_MISMATCH outputClassCount=$classCount labelCount=${classLabels.size} boxSize=$boxSize"
            )
        }
        val useSingleScoreFormat = (numBoxes <= 100 && boxSize >= 5)
        val minConfidenceToShow = when {
            useSingleScoreFormat -> 0.35f
            hasObjectness -> 0.45f
            else -> 0.78f
        }
        val target = targetLabel?.trim().orEmpty()
        val targetClassIndex = if (target.isNotBlank()) {
            classLabels.indexOfFirst { it.trim().equals(target, ignoreCase = true) }
        } else -1
        val nowDiagMs = System.currentTimeMillis()
        val shouldLogParseDiag = target.isNotBlank() &&
            searchState == SearchState.SEARCHING &&
            nowDiagMs - lastYoloxParseDiagLogMs >= YOLOX_DIAG_LOG_INTERVAL_MS
        var bestAnyLabel = ""
        var bestAnyConfidence = 0f
        var bestTargetConfidence = 0f
        var bestTargetTopLabel = ""
        var acceptedBeforeNms = 0

        val candidates = mutableListOf<OverlayView.DetectionBox>()
        for (j in 0 until numBoxes) {
            val v0: Float
            val v1: Float
            val v2: Float
            val v3: Float
            val classScores = FloatArray(classCount)
            var singleScore = 0f

            if (isRowMajor) {
                val row = output[j]
                if (row.size < boxSize) continue
                v0 = row[0]; v1 = row[1]; v2 = row[2]; v3 = row[3]
                if (row.size > 4) singleScore = row[4]
                for (c in 0 until classCount) classScores[c] = row[scoreStart + c]
            } else {
                v0 = output[0][j]; v1 = output[1][j]; v2 = output[2][j]; v3 = output[3][j]
                if (dim1 > 4) singleScore = output[4][j]
                for (c in 0 until classCount) classScores[c] = output[scoreStart + c][j]
            }

            val objScore = singleScore.coerceIn(0f, 1f)
            val topClassIds = classScores.indices
                .map { c ->
                    var rs = classScores[c]
                    if (rs > 1f || rs < 0f) rs = 1f / (1f + exp(-rs))
                    c to (objScore * rs).coerceIn(0f, 1f)
                }
                .sortedByDescending { it.second }
                .take(3)
            val confidence = topClassIds.firstOrNull()?.second ?: 0f
            val topLabel = topClassIds.firstOrNull()?.let { getClassLabel(it.first) } ?: ""
            if (confidence > bestAnyConfidence) {
                bestAnyConfidence = confidence
                bestAnyLabel = topLabel
            }
            if (targetClassIndex in 0 until classCount) {
                var targetRawScore = classScores[targetClassIndex]
                if (targetRawScore > 1f || targetRawScore < 0f) targetRawScore = 1f / (1f + exp(-targetRawScore))
                val targetScore = (objScore * targetRawScore).coerceIn(0f, 1f)
                if (targetScore > bestTargetConfidence) {
                    bestTargetConfidence = targetScore
                    bestTargetTopLabel = topLabel
                }
            }
            val minForThis = if (targetLabel != null && topLabel.trim().equals(targetLabel.trim(), ignoreCase = true))
                TARGET_TRACKING_CONFIDENCE_THRESHOLD else minConfidenceToShow
            if (confidence < minForThis) continue

            val topLabels = topClassIds
                .filter { (_, conf) -> conf >= 0.5f }
                .map { (cid, conf) -> getClassLabel(cid) to (conf * 100).toInt() }

            val left: Float
            val top: Float
            val right: Float
            val bottom: Float
            if (isNormalized && v2 > v0 && v3 > v1) {
                left = (v0 * imageWidth).coerceIn(0f, imageWidth.toFloat())
                top = (v1 * imageHeight).coerceIn(0f, imageHeight.toFloat())
                right = (v2 * imageWidth).coerceIn(0f, imageWidth.toFloat())
                bottom = (v3 * imageHeight).coerceIn(0f, imageHeight.toFloat())
            } else {
                if (letterboxScaleR != null && letterboxScaleR > 0f) {
                    val cx = (v0 / letterboxScaleR).coerceIn(0f, imageWidth.toFloat())
                    val cy = (v1 / letterboxScaleR).coerceIn(0f, imageHeight.toFloat())
                    val w = (v2 / letterboxScaleR) / 2f
                    val h = (v3 / letterboxScaleR) / 2f
                    left = max(0f, cx - w)
                    top = max(0f, cy - h)
                    right = min(imageWidth.toFloat(), cx + w)
                    bottom = min(imageHeight.toFloat(), cy + h)
                } else if (isNormalized) {
                    val cx = v0.coerceIn(0f, 1f) * imageWidth
                    val cy = v1.coerceIn(0f, 1f) * imageHeight
                    val w = (v2.coerceIn(0f, 1f) * imageWidth) / 2f
                    val h = (v3.coerceIn(0f, 1f) * imageHeight) / 2f
                    left = max(0f, cx - w)
                    top = max(0f, cy - h)
                    right = min(imageWidth.toFloat(), cx + w)
                    bottom = min(imageHeight.toFloat(), cy + h)
                } else {
                    val cx = v0 / inputSize * imageWidth
                    val cy = v1 / inputSize * imageHeight
                    val w = (v2 / inputSize * imageWidth) / 2f
                    val h = (v3 / inputSize * imageHeight) / 2f
                    left = max(0f, cx - w)
                    top = max(0f, cy - h)
                    right = min(imageWidth.toFloat(), cx + w)
                    bottom = min(imageHeight.toFloat(), cy + h)
                }
            }

            if (right <= left || bottom <= top) continue
            acceptedBeforeNms++
            candidates.add(
                OverlayView.DetectionBox(
                    label = topLabels.firstOrNull()?.first ?: "",
                    confidence = confidence,
                    rect = RectF(left, top, right, bottom),
                    topLabels = topLabels
                )
            )
        }
        val merged = mergeOverlappingSameLabel(nms(candidates, 0.6f))
        if (shouldLogParseDiag) {
            lastYoloxParseDiagLogMs = nowDiagMs
            Log.i(
                TAG,
                "YOLOX_PARSE_DIAG target=$target boxes=$numBoxes boxSize=$boxSize objectness=$hasObjectness classCount=$classCount labelCount=${classLabels.size} acceptedBeforeNms=$acceptedBeforeNms accepted=${merged.size} bestAny=$bestAnyLabel:${"%.3f".format(bestAnyConfidence)} targetClassIndex=$targetClassIndex bestTarget=${"%.3f".format(bestTargetConfidence)} bestTargetTopLabel=$bestTargetTopLabel trackingThreshold=$TARGET_TRACKING_CONFIDENCE_THRESHOLD lockThreshold=$TARGET_CONFIDENCE_THRESHOLD showThreshold=$minConfidenceToShow"
            )
        }
        return merged
    }

    private fun nms(boxes: List<OverlayView.DetectionBox>, iouThreshold: Float): List<OverlayView.DetectionBox> {
        val sorted = boxes.sortedByDescending { it.confidence }
        val picked = mutableListOf<OverlayView.DetectionBox>()
        val used = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (used[i]) continue
            picked.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                if (iou(sorted[i].rect, sorted[j].rect) > iouThreshold) used[j] = true
            }
        }
        return picked
    }

    /**
     * 같은 라벨의 겹치거나 인접한 박스를 하나로 합침.
     * 타일링/노이즈로 하나의 물체가 2~3개 작은 박스로 나올 때 하나의 큰 박스로 만듦.
     */
    private fun mergeOverlappingSameLabel(
        boxes: List<OverlayView.DetectionBox>,
        iouThreshold: Float = 0.1f,
        expandRatio: Float = 0.25f
    ): List<OverlayView.DetectionBox> {
        if (boxes.size <= 1) return boxes
        val byLabel = boxes.groupBy { it.label.trim().lowercase() }
        val result = mutableListOf<OverlayView.DetectionBox>()
        for ((_, group) in byLabel) {
            if (group.size <= 1) {
                result.addAll(group)
                continue
            }
            var current = group.toMutableList()
            var changed = true
            while (changed) {
                changed = false
                for (i in current.indices) {
                    for (j in i + 1 until current.size) {
                        val a = current[i].rect
                        val b = current[j].rect
                        val iouVal = iou(a, b)
                        val aExp = RectF(
                            a.left - a.width() * expandRatio,
                            a.top - a.height() * expandRatio,
                            a.right + a.width() * expandRatio,
                            a.bottom + a.height() * expandRatio
                        )
                        val bExp = RectF(
                            b.left - b.width() * expandRatio,
                            b.top - b.height() * expandRatio,
                            b.right + b.width() * expandRatio,
                            b.bottom + b.height() * expandRatio
                        )
                        val nearOrOverlap = iouVal > iouThreshold || RectF.intersects(aExp, bExp)
                        if (nearOrOverlap) {
                            val union = RectF(a).apply { union(b) }
                            val maxConf = maxOf(current[i].confidence, current[j].confidence)
                            val better = if (current[i].confidence >= current[j].confidence) current[i] else current[j]
                            val merged = better.copy(
                                rect = union,
                                confidence = maxConf
                            )
                            current = current.filterIndexed { idx, _ -> idx != i && idx != j }.toMutableList()
                            current.add(merged)
                            changed = true
                            break
                        }
                    }
                    if (changed) break
                }
            }
            result.addAll(current)
        }
        return result
    }

    private fun handLandmarksToRect(landmarks: List<NormalizedLandmark>, imageWidth: Int, imageHeight: Int): RectF {
        if (landmarks.isEmpty()) return RectF(0f, 0f, 0f, 0f)
        val xs = landmarks.map { it.x().coerceIn(0f, 1f) * imageWidth }
        val ys = landmarks.map { it.y().coerceIn(0f, 1f) * imageHeight }
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    private fun mergedHandRect(handsResult: HandLandmarkerResult?, imageWidth: Int, imageHeight: Int): RectF? {
        val landmarks = handsResult?.landmarks() ?: return null
        if (landmarks.isEmpty()) return null
        var union: RectF? = null
        for (hand in landmarks) {
            val r = handLandmarksToRect(hand, imageWidth, imageHeight)
            union = if (union == null) r else RectF(union).apply { union(r) }
        }
        return union
    }

    /** 손이 물체를 가리는지: 손 전체(21개 랜드마크 min/max 사각형)와 물체 박스 IoU. 접촉(엄지·검지)과 별개. 낮을수록 "좀 가려도" occlusion 인정. */
    private val handBoxOverlapIouThreshold = 0.03f
    private fun isHandOverlappingBox(handsResult: HandLandmarkerResult?, boxRect: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        val landmarks = handsResult?.landmarks() ?: return false
        if (imageWidth <= 0 || imageHeight <= 0) return false
        return landmarks.any { hand ->
            val handRect = handLandmarksToRect(hand, imageWidth, imageHeight)
            iou(handRect, boxRect) > handBoxOverlapIouThreshold
        }
    }

    /**
     * 접촉 판정(Touch/Grab Detection) — 3중 조건이 모두 만족될 때만 true.
     * 1) 렌즈 가림 필터: 손 박스가 화면 60% 이상이면 false.
     * 2) 중심점/검지·엄지 끝 기반 교집합: 손 중심이 객체 안 / 객체 중심이 손 안 / 검지·엄지 끝이 객체 안.
     * 3) 체공 시간(30프레임)은 호출부에서 touchFrameCount >= TOUCH_CONFIRM_FRAMES 로 강제.
     */
    private fun isHandTouchingTargetBox(handsResult: HandLandmarkerResult?, targetBox: RectF, imageWidth: Int, imageHeight: Int): Boolean {
        lastTouchMidpointPx = null
        val landmarks = handsResult?.landmarks() ?: return false
        if (imageWidth <= 0 || imageHeight <= 0) return false
        val hand = landmarks.firstOrNull() ?: return false
        if (hand.size < 9) return false

        val handBox = handLandmarksToRect(hand, imageWidth, imageHeight)
        val handW = handBox.width()
        val handH = handBox.height()

        // 1. 렌즈 가림 필터 (Anti-Occlusion by Size): 60% 이상이면 객체를 잡은 게 아니라 렌즈를 가린 것
        if (handW >= imageWidth * 0.6f || handH >= imageHeight * 0.6f) return false

        val handCenterX = handBox.centerX()
        val handCenterY = handBox.centerY()
        val objCenterX = targetBox.centerX()
        val objCenterY = targetBox.centerY()
        val thumb = landmarkToPoint(hand, 4, imageWidth, imageHeight)
        val index = landmarkToPoint(hand, 8, imageWidth, imageHeight)
        val midX = if (thumb != null && index != null) (thumb.first + index.first) / 2f else handCenterX
        val midY = if (thumb != null && index != null) (thumb.second + index.second) / 2f else handCenterY
        lastTouchMidpointPx = Pair(midX, midY)

        // 2. 중심점 기반 정밀 교집합 (Center-Biased): contains만 인정, 단순 intersects 미인정
        val centerInObject = targetBox.contains(handCenterX, handCenterY)
        val objectCenterInHand = handBox.contains(objCenterX, objCenterY)
        val indexTipInObject = index != null && targetBox.contains(index.first, index.second)
        val thumbTipInObject = thumb != null && targetBox.contains(thumb.first, thumb.second)
        if (!centerInObject && !objectCenterInHand && !indexTipInObject && !thumbTipInObject) return false

        // 1·2 통과. 3(연속 30프레임)은 호출부에서 touchFrameCount로 검사
        return true
    }

    private fun landmarkToPoint(hand: List<NormalizedLandmark>, index: Int, imageWidth: Int, imageHeight: Int): Pair<Float, Float>? {
        if (index < 0 || index >= hand.size) return null
        val lm = hand[index]
        return Pair(lm.x().coerceIn(0f, 1f) * imageWidth, lm.y().coerceIn(0f, 1f) * imageHeight)
    }

    private fun buildHandPositionGuidance(handsResult: HandLandmarkerResult?, boxRect: RectF, imageWidth: Int, imageHeight: Int): String? {
        val landmarks = handsResult?.landmarks() ?: return null
        if (landmarks.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        val hand = landmarks.first()
        if (hand.size <= 0) return null
        val wrist = landmarkToPoint(hand, 0, imageWidth, imageHeight) ?: return null
        val boxCenterX = (boxRect.left + boxRect.right) / 2f
        val boxCenterY = (boxRect.top + boxRect.bottom) / 2f
        val dx = (wrist.first - boxCenterX) / imageWidth
        val dy = (wrist.second - boxCenterY) / imageHeight
        val dist = sqrt(dx * dx + dy * dy)
        return when {
            dist > 0.45f -> "손을 더 뻗어주세요. 앞으로 조금 더 움직여주세요."
            dist > 0.3f -> "조금 더 앞으로 다가가 주세요."
            dx > 0.15f -> "오른쪽으로 조금 움직여주세요."
            dx < -0.15f -> "왼쪽으로 조금 움직여주세요."
            dy > 0.12f -> "아래로 조금 움직여주세요."
            dy < -0.12f -> "위로 조금 움직여주세요."
            else -> null
        }
    }

    private fun startHandGuidanceTTS() {
        stopHandGuidanceTTS()
        handGuidanceRunnable = object : Runnable {
            override fun run() {
                if (searchState != SearchState.LOCKED || ttsGrabbedPlayed) return
                val box = frozenBox ?: return
                val w = frozenImageWidth
                val h = frozenImageHeight
                if (w <= 0 || h <= 0) return
                val guidance = buildHandPositionGuidance(latestHandsResult.get(), box.rect, w, h)
                if (!guidance.isNullOrBlank()) {
                    lastHandGuidanceTimeMs = System.currentTimeMillis()
                    speak(guidance, false)
                }
                handGuidanceHandler.postDelayed(this, HAND_GUIDANCE_INTERVAL_MS)
            }
        }
        handGuidanceHandler.postDelayed(handGuidanceRunnable!!, HAND_GUIDANCE_INTERVAL_MS)
    }

    private fun stopHandGuidanceTTS() {
        handGuidanceRunnable?.let { handGuidanceHandler.removeCallbacks(it) }
        handGuidanceRunnable = null
    }

    private fun sendFrameToHands(bitmap: Bitmap, timestampNs: Long) {
        handLandmarker ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker!!.detectAsync(mpImage, timestampNs / 1_000_000)
        } catch (e: Exception) {
            Log.e(TAG, "Hands detectAsync 실패", e)
        }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        return interArea / (a.width() * a.height() + b.width() * b.height() - interArea + 1e-6f)
    }

    private fun displayResults(detections: List<OverlayView.DetectionBox>, inferenceTime: Long, imageWidth: Int, imageHeight: Int) {
        requireActivity().runOnUiThread {
            _binding?.overlayView?.setDetections(detections, imageWidth, imageHeight)
            _binding?.overlayView?.setHands(if (searchState == SearchState.LOCKED) latestHandsResult.get() else null)
            if (searchState == SearchState.LOCKED && lastTouchMidpointPx != null) {
                _binding?.overlayView?.setTouchDebugPoint(lastTouchMidpointPx!!.first, lastTouchMidpointPx!!.second)
            } else {
                _binding?.overlayView?.setTouchDebugPoint(null, null)
            }
            when (searchState) {
                SearchState.IDLE -> { }
                SearchState.SEARCHING -> { }
                SearchState.LOCKED -> { }
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCamera()
            voiceFlowController?.start()
        } else if (requestCode == REQUEST_CODE_PERMISSIONS) {
            Toast.makeText(requireContext(), "카메라 및 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val startTime = System.currentTimeMillis()
            var bitmap = imageProxy.toBitmap()
            if (bitmap == null) {
                imageProxy.close()
                return
            }
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                bitmap = BitmapUtils.rotateBitmap(bitmap!!, rotationDegrees) ?: bitmap!!
            }
            val w = bitmap!!.width
            val h = bitmap.height
            // 터치확인 중에는 YOLOX 미실행 → 리셋/카메라 중단 시 SIGSEGV 방지. "닿았나요?" 안내가 끝날 때까지 bbox는 유지
            if (waitingForTouchConfirm || touchConfirmInProgress) {
                val boxes = if (searchState == SearchState.LOCKED && frozenBox != null)
                    listOf(frozenBox!!) else emptyList()
                val bw = if (frozenImageWidth > 0) frozenImageWidth else w
                val bh = if (frozenImageHeight > 0) frozenImageHeight else h
                displayResults(boxes, System.currentTimeMillis() - startTime, bw, bh)
                imageProxy.close()
                return
            }
            var inferMs = System.currentTimeMillis() - startTime
            if (verifyHeldItemFlow.currentState != VerifyHeldItemFlow.State.IDLE) {
                if (verifyHeldItemFlow.currentState == VerifyHeldItemFlow.State.VERIFYING) {
                    val detections = runYOLOXSingle(bitmap)
                    inferMs = System.currentTimeMillis() - startTime
                    val detected = detections.maxByOrNull {
                        it.rect.width() * it.rect.height()
                    }
                    val result = verifyHeldItemFlow.onDetection(
                        detectedLabel = detected?.label,
                        confidence = detected?.confidence ?: 0f
                    )
                    displayResults(detections, inferMs, w, h)
                    when (result) {
                        VerifyHeldItemFlow.VerificationResult.SUCCESS -> {
                            requireActivity().runOnUiThread {
                                guidanceStateMachine.complete()
                                hapticFeedbackController.playVerifySuccess()
                                touchConfirmScheduled = false
                                speak(VoicePrompts.PROMPT_VERIFY_SUCCESS, urgent = true, isAutoGuidance = false) {
                                    requireActivity().runOnUiThread {
                                        performKillAllResetFromTouch()
                                    }
                                }
                            }
                        }
                        VerifyHeldItemFlow.VerificationResult.FAILURE -> {
                            requireActivity().runOnUiThread {
                                guidanceStateMachine.verificationFailed()
                                hapticFeedbackController.playVerifyFailure()
                                speak(VoicePrompts.PROMPT_VERIFY_FAILURE, urgent = true, isAutoGuidance = false) {
                                    view?.postDelayed({
                                        if (verifyHeldItemFlow.currentState == VerifyHeldItemFlow.State.FAILURE) {
                                            guidanceStateMachine.startVerification()
                                            verifyHeldItemFlow.start(lockedTargetLabel)
                                        }
                                    }, 3000L)
                                }
                            }
                        }
                        VerifyHeldItemFlow.VerificationResult.PENDING -> Unit
                    }
                }
                imageProxy.close()
                return
            }

            if (searchState == SearchState.LOCKED) {
                sendFrameToHands(bitmap, imageProxy.imageInfo.timestamp)
            }

            when (searchState) {
                SearchState.IDLE -> {
                    displayResults(emptyList(), inferMs, w, h)
                }
                SearchState.SEARCHING -> {
                    if (!hasSpecificTarget()) {
                        displayResults(emptyList(), inferMs, w, h)
                    } else {
                        val nowMs = System.currentTimeMillis()
                        val targetLabel = getTargetLabel()
                        val whenResult = when (searchTilingTier) {
                            1 -> {
                                val detectionsFull = runYOLOXSingle(bitmap)
                                val matchFull = findTargetMatch(detectionsFull, targetLabel)
                                if (matchFull != null) Triple(matchFull, false, 2) to detectionsFull
                                else {
                                    if (lastTier1NoMatchTimeMs == 0L) lastTier1NoMatchTimeMs = nowMs
                                    if (nowMs - lastTier1NoMatchTimeMs >= ESCALATE_TO_TIER2_AFTER_MS) {
                                        Log.i(TAG, "YOLOX_SEARCH_TIER_CHANGE target=$targetLabel from=1 to=2 noMatchMs=${nowMs - lastTier1NoMatchTimeMs}")
                                        searchTilingTier = 2
                                        lastTier2NoMatchTimeMs = 0L
                                    }
                                    Triple(null, false, 2) to detectionsFull
                                }
                            }
                            2 -> {
                                val detectionsFull = runYOLOXSingle(bitmap)
                                val detections2 = if (ENABLE_YOLOX_TILING) runYOLOXTiled(bitmap, 2) else emptyList()
                                val matchFull = findTargetMatch(detectionsFull, targetLabel)
                                val matchTiled2 = findTargetMatch(detections2, targetLabel)
                                val (result, fromTiling, grid) = when {
                                    matchFull == null && matchTiled2 == null -> Triple(null, false, 2)
                                    matchFull == null -> Triple(matchTiled2!!, true, 2)
                                    matchTiled2 == null -> Triple(matchFull, false, 2)
                                    else -> if (matchFull.first.confidence >= matchTiled2.first.confidence) Triple(matchFull, false, 2) else Triple(matchTiled2, true, 2)
                                }
                                if (result == null) {
                                    if (lastTier2NoMatchTimeMs == 0L) lastTier2NoMatchTimeMs = nowMs
                                    if (nowMs - lastTier2NoMatchTimeMs >= ESCALATE_TO_TIER3_AFTER_MS) {
                                        Log.i(TAG, "YOLOX_SEARCH_TIER_CHANGE target=$targetLabel from=2 to=3 noMatchMs=${nowMs - lastTier2NoMatchTimeMs}")
                                        searchTilingTier = 3
                                        searchFrameCountInTier3 = 0
                                    }
                                } else if (!fromTiling) {
                                    if (searchTilingTier != 1) {
                                        Log.i(TAG, "YOLOX_SEARCH_TIER_CHANGE target=$targetLabel from=$searchTilingTier to=1 reason=full_frame_match")
                                    }
                                    searchTilingTier = 1
                                    lastTier1NoMatchTimeMs = 0L
                                }
                                Triple(result, fromTiling, grid) to mergeOverlappingSameLabel(nms(detectionsFull + detections2, 0.6f))
                            }
                            else -> {
                                val detections2 = runYOLOXTiled(bitmap, 2)
                                val run3x3 = (searchFrameCountInTier3 % TIER3_GRID_INTERVAL == 0)
                                val detections3 = if (run3x3 && ENABLE_YOLOX_TILING) runYOLOXTiled(bitmap, 3) else emptyList()
                                searchFrameCountInTier3++
                                val match2 = findTargetMatch(detections2, targetLabel)
                                val match3 = findTargetMatch(detections3, targetLabel)
                                val (result, fromTiling, grid) = when {
                                    match2 == null && match3 == null -> Triple(null, true, 2)
                                    match2 == null -> Triple(match3!!, true, 3)
                                    match3 == null -> Triple(match2, true, 2)
                                    else -> if (match2.first.confidence >= match3.first.confidence) Triple(match2, true, 2) else Triple(match3, true, 3)
                                }
                                if (result != null && grid == 2) {
                                    searchTilingTier = 2
                                    lastTier2NoMatchTimeMs = 0L
                                }
                                Triple(result, fromTiling, grid) to mergeOverlappingSameLabel(nms(detections2 + detections3, 0.6f))
                            }
                        }
                        val (matchRes, fromTilingRes, tilingGridUsedRes) = whenResult.first
                        val combined = whenResult.second
                        if (matchRes != null) {
                            lastSearchNoDetectionStartMs = 0L
                            searchObjectNotFoundAnnounced = false
                            val (matched, actualPrimaryLabel) = matchRes
                            Log.i(TAG, "YOLOX_SEARCH_MATCH target=$targetLabel conf=${"%.3f".format(matched.confidence)} actualPrimary=$actualPrimaryLabel tier=$searchTilingTier fromTiling=$fromTilingRes grid=$tilingGridUsedRes lockThreshold=$TARGET_CONFIDENCE_THRESHOLD trackingThreshold=$TARGET_TRACKING_CONFIDENCE_THRESHOLD")
                            pendingLockMissCount = 0
                            if (matched.confidence >= TARGET_CONFIDENCE_THRESHOLD) {
                                guidanceStateMachine.candidateDetected()
                                val nowCandidate = System.currentTimeMillis()
                                if (nowCandidate - lastCandidateFeedbackTimeMs >= CANDIDATE_FEEDBACK_COOLDOWN_MS) {
                                    Log.i(TAG, "VOICE_DIAG_FEEDBACK_CANDIDATE_DETECTED target=$targetLabel conf=${"%.3f".format(matched.confidence)} cooldownMs=$CANDIDATE_FEEDBACK_COOLDOWN_MS")
                                    lastCandidateFeedbackTimeMs = nowCandidate
                                    hapticFeedbackController.playCandidateDetected()
                                    requireActivity().runOnUiThread {
                                        speak(
                                            VoicePrompts.PROMPT_CANDIDATE_DETECTED,
                                            urgent = false,
                                            isAutoGuidance = false
                                        )
                                    }
                                } else {
                                    Log.i(TAG, "VOICE_DIAG_FEEDBACK_SUPPRESSED reason=candidate_cooldown target=$targetLabel elapsedMs=${nowCandidate - lastCandidateFeedbackTimeMs} cooldownMs=$CANDIDATE_FEEDBACK_COOLDOWN_MS")
                                }
                                val prev = pendingLockBox
                                val movementNorm = if (prev != null) {
                                    val dx = (matched.rect.centerX() - prev.rect.centerX()) / w.coerceAtLeast(1)
                                    val dy = (matched.rect.centerY() - prev.rect.centerY()) / h.coerceAtLeast(1)
                                    sqrt(dx * dx + dy * dy)
                                } else {
                                    Float.MAX_VALUE
                                }
                                val sameTarget = prev != null &&
                                    prev.label == matched.label &&
                                    iou(matched.rect, prev.rect) >= PENDING_LOCK_IOU_THRESHOLD &&
                                    movementNorm <= TARGET_LOCK_MAX_MOVEMENT_NORM
                                if (sameTarget) {
                                    pendingLockCount++
                                    val nowLock = System.currentTimeMillis()
                                    val stableDurationMs = if (pendingLockStableSinceMs > 0L) nowLock - pendingLockStableSinceMs else 0L
                                    if (pendingLockCount >= LOCK_CONFIRM_FRAMES &&
                                        stableDurationMs >= TARGET_LOCK_STABILITY_MS) {
                                        pendingLockBox = null
                                        pendingLockCount = 0
                                        pendingLockMissCount = 0
                                        pendingLockStableSinceMs = 0L
                                        transitionToLocked(matched, w, h, actualPrimaryLabel, fromTilingRes, tilingGridUsedRes)
                                        frozenBox = matched
                                        frozenImageWidth = w
                                        frozenImageHeight = h
                                        wasOccluded = false
                                        opticalFlowTracker.reset()
                                        gyroManager.startTracking(matched.rect, w, h)
                                        lockedFrameCount = 0
                                        validationFailCount = 0
                                        imageProxy.close()
                                        return
                                    }
                                } else {
                                    pendingLockBox = matched
                                    pendingLockCount = 1
                                    pendingLockStableSinceMs = System.currentTimeMillis()
                                }
                            } else {
                                Log.i(TAG, "YOLOX_SEARCH_MATCH_BELOW_LOCK target=$targetLabel conf=${"%.3f".format(matched.confidence)} lockThreshold=$TARGET_CONFIDENCE_THRESHOLD tier=$searchTilingTier fromTiling=$fromTilingRes grid=$tilingGridUsedRes")
                            }
                        } else {
                            pendingLockMissCount++
                            if (pendingLockMissCount >= PENDING_LOCK_MAX_MISS_FRAMES) {
                                guidanceStateMachine.startSearching()
                                pendingLockBox = null
                                pendingLockCount = 0
                                pendingLockMissCount = 0
                                pendingLockStableSinceMs = 0L
                            }
                            val nowPing = System.currentTimeMillis()
                            if (lastSearchNoDetectionStartMs == 0L) lastSearchNoDetectionStartMs = nowPing
                            if (!searchObjectNotFoundAnnounced && (nowPing - lastSearchNoDetectionStartMs >= SEARCH_OBJECT_NOT_FOUND_ANNOUNCE_MS)) {
                                searchObjectNotFoundAnnounced = true
                                Log.i(TAG, "VOICE_DIAG_SEARCH_FEEDBACK_NOT_FOUND target=$targetLabel noMatchMs=${nowPing - lastSearchNoDetectionStartMs}")
                                requireActivity().runOnUiThread {
                                    speak("상품이 보이지 않습니다. 주변을 확인하기 위해 핸드폰을 옆으로 천천히 움직여보세요.", false)
                                }
                            }
                            if (nowPing - lastSearchPingTime >= SEARCH_PING_INTERVAL_MS) {
                                Log.i(TAG, "VOICE_DIAG_SEARCH_FEEDBACK_PING target=$targetLabel intervalMs=$SEARCH_PING_INTERVAL_MS")
                                requireActivity().runOnUiThread {
                                    lastSearchPingTime = System.currentTimeMillis()
                                    speak("천천히 주변을 더 넓게 비춰보세요", false)
                                }
                            }
                            if (nowPing - lastYoloxSearchDiagLogMs >= YOLOX_DIAG_LOG_INTERVAL_MS) {
                                lastYoloxSearchDiagLogMs = nowPing
                                Log.i(TAG, "YOLOX_SEARCH_NO_MATCH target=$targetLabel tier=$searchTilingTier combined=${combined.size} pendingMiss=$pendingLockMissCount noMatchMs=${if (lastSearchNoDetectionStartMs == 0L) 0L else nowPing - lastSearchNoDetectionStartMs}")
                            }
                        }
                        displayResults(filterDetectionsByTarget(combined, targetLabel), inferMs, w, h)
                    }
                }
                SearchState.LOCKED -> {
                    lockedFrameCount++
                    val nowMs = System.currentTimeMillis()
                    val handsOverlap = frozenBox?.let { isHandOverlappingBox(latestHandsResult.get(), it.rect, w, h) } ?: false
                    // 손이 물체를 가린 동안에는 락 해제 타이머를 적용하지 않음 (손 뻗어 잡을 때 상품 잃어버리지 않도록)
                    if (handsOverlap) lastSuccessfulValidationTimeMs = nowMs
                    val boxLostDurationMs = nowMs - lastSuccessfulValidationTimeMs
                    val shouldReacquire = boxLostDurationMs >= REACQUIRE_INFERENCE_AFTER_MS
                    if (boxLostDurationMs >= LOCK_RELEASE_AFTER_MS) {
                        validationFailCount = 0
                        frozenBox = null
                        lastTargetBox = null
                        gyroManager.resetToSearchingFromUI()
                        displayResults(emptyList(), 0, w, h)
                        imageProxy.close()
                        return
                    }
                    val shouldValidate = shouldReacquire || (lockedFrameCount % VALIDATION_INTERVAL) == 0
                    val handTouch = frozenBox?.let { isHandTouchingTargetBox(latestHandsResult.get(), it.rect, w, h) } ?: false

                    if (handTouch) {
                        touchFrameCount++
                        releaseFrameCount = 0
                        val inBreathingRoom = voiceFlowController?.isInSttBreathingRoom() == true
                        if (!touchActive && !touchConfirmInProgress && !touchConfirmScheduled &&
                            touchFrameCount >= TOUCH_CONFIRM_FRAMES && !inBreathingRoom) {
                            touchActive = true
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastTouchTtsTimeMs >= TOUCH_TTS_COOLDOWN_MS) {
                                lastTouchTtsTimeMs = nowMs
                                touchConfirmScheduled = true
                                requireActivity().runOnUiThread {
                                    stopPositionAnnounce()
                                    stopHandGuidanceTTS()
                                    enterTouchConfirm()
                                }
                            }
                        }
                    } else {
                        releaseFrameCount++
                        if (releaseFrameCount >= RELEASE_HOLD_FRAMES) {
                            touchActive = false
                            touchFrameCount = 0
                        } else {
                            touchFrameCount = 0
                        }
                    }

                    gyroManager.suspendUpdates = handsOverlap

                    if (handsOverlap) {
                        if (shouldReacquire) {
                            val detections = when {
                                useTilingWhenLocked -> {
                                    val full = runYOLOXSingle(bitmap)
                                    val tFull = findTrackedTarget(full, lockedTargetLabel, frozenBox, TARGET_TRACKING_CONFIDENCE_THRESHOLD)
                                    if (tFull != null) {
                                        useTilingWhenLocked = false
                                        full
                                    } else runYOLOXTiled(bitmap, tilingGridWhenLocked)
                                }
                                else -> runYOLOXSingle(bitmap)
                            }
                            inferMs = System.currentTimeMillis() - startTime
                            val tracked = findTrackedTarget(detections, lockedTargetLabel, frozenBox, TARGET_TRACKING_CONFIDENCE_THRESHOLD)
                            if (tracked != null) {
                                lastSuccessfulValidationTimeMs = System.currentTimeMillis()
                                lastTargetBox = tracked.rect
                                missedFramesCount = 0
                                gyroManager.correctPosition(tracked.rect)
                                frozenBox = tracked.copy(rotationDegrees = 0f)
                                frozenImageWidth = w
                                frozenImageHeight = h
                                if (!touchConfirmInProgress && !waitingForTouchConfirm) {
                                    touchActive = false
                                    touchFrameCount = 0
                                    releaseFrameCount = 0
                                    lastTouchTtsTimeMs = 0L
                                }
                            }
                        } else {
                            val handRect = mergedHandRect(latestHandsResult.get(), w, h)
                            val flow = opticalFlowTracker.update(bitmap, handRect, frozenBox?.rect)
                            flow?.let { (dx, dy) ->
                                val box = frozenBox ?: return@let
                                val r = box.rect
                                val newRect = RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
                                gyroManager.correctPosition(newRect)
                                frozenBox = box.copy(rect = newRect)
                            }
                        }
                    } else {
                        if (wasOccluded) {
                            frozenBox?.rect?.let { gyroManager.correctPosition(it) }
                        }
                        if (shouldValidate) {
                            // 풀스케일 우선: 타일링 락이어도 검증 시 풀스케일 먼저 시도 → 잡히면 풀스케일로 전환. 풀스케일로 잡고 있으면 타일링 미사용.
                            val detections = when {
                                useTilingWhenLocked -> {
                                    val full = runYOLOXSingle(bitmap)
                                    val tFull = findTrackedTarget(full, lockedTargetLabel, frozenBox, TARGET_TRACKING_CONFIDENCE_THRESHOLD)
                                    if (tFull != null) {
                                        useTilingWhenLocked = false
                                        full
                                    } else runYOLOXTiled(bitmap, tilingGridWhenLocked)
                                }
                                else -> runYOLOXSingle(bitmap)
                            }
                            inferMs = System.currentTimeMillis() - startTime
                            val minConf = TARGET_TRACKING_CONFIDENCE_THRESHOLD
                            val tracked = findTrackedTarget(detections, lockedTargetLabel, frozenBox, minConf)
                            if (tracked != null) {
                                validationFailCount = 0
                                lastSuccessfulValidationTimeMs = System.currentTimeMillis()
                                lastTargetBox = tracked.rect
                                missedFramesCount = 0
                                val cur = frozenBox?.rect ?: tracked.rect
                                val blend = 0.7f
                                val blendedRect = RectF(
                                    cur.left * blend + tracked.rect.left * (1f - blend),
                                    cur.top * blend + tracked.rect.top * (1f - blend),
                                    cur.right * blend + tracked.rect.right * (1f - blend),
                                    cur.bottom * blend + tracked.rect.bottom * (1f - blend)
                                )
                                gyroManager.correctPosition(blendedRect)
                                frozenBox = tracked.copy(rect = blendedRect, rotationDegrees = 0f)
                                frozenImageWidth = w
                                frozenImageHeight = h
                                if (!touchConfirmInProgress && !waitingForTouchConfirm) {
                                    touchActive = false
                                    touchFrameCount = 0
                                    releaseFrameCount = 0
                                    lastTouchTtsTimeMs = 0L
                                }
                            } else {
                                missedFramesCount++
                                if (missedFramesCount < GHOST_BOX_MAX_MISSED_FRAMES && lastTargetBox != null) {
                                    val zoomDenom = lastTargetZoomRatio.coerceAtLeast(0.1f)
                                    val scale = currentZoomRatio / zoomDenom
                                    val r = lastTargetBox!!
                                    val screenCenterX = w / 2f
                                    val screenCenterY = h / 2f
                                    val oldBoxCenterX = r.centerX()
                                    val oldBoxCenterY = r.centerY()
                                    val oldWidth = r.width()
                                    val oldHeight = r.height()
                                    val newBoxCenterX = screenCenterX + (oldBoxCenterX - screenCenterX) * scale
                                    val newBoxCenterY = screenCenterY + (oldBoxCenterY - screenCenterY) * scale
                                    val newWidth = oldWidth * scale
                                    val newHeight = oldHeight * scale
                                    val ghostRect = RectF(
                                        newBoxCenterX - newWidth / 2f,
                                        newBoxCenterY - newHeight / 2f,
                                        newBoxCenterX + newWidth / 2f,
                                        newBoxCenterY + newHeight / 2f
                                    )
                                    frozenBox = (frozenBox ?: OverlayView.DetectionBox(lockedTargetLabel, 0.5f, ghostRect, listOf(lockedTargetLabel to 50)))
                                        .copy(rect = ghostRect)
                                }
                                validationFailCount++
                                if (validationFailCount >= VALIDATION_FAIL_LIMIT) {
                                    validationFailCount = 0
                                    gyroManager.resetToSearchingFromUI()
                                }
                            }
                        } else {
                            // 검증 프레임이 아니어도 optical flow로 박스 위치를 매 프레임 갱신 → 위치 안내(중앙/왼쪽 등)가 즉시 반영되도록
                            val flow = opticalFlowTracker.update(bitmap, null, frozenBox?.rect)
                            flow?.let { (dx, dy) ->
                                val box = frozenBox ?: return@let
                                val r = box.rect
                                val newRect = RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
                                gyroManager.correctPosition(newRect)
                                frozenBox = box.copy(rect = newRect)
                            }
                        }
                    }
                    wasOccluded = handsOverlap

                    frozenBox?.let { processDistanceGuidance(it, frozenImageWidth, frozenImageHeight) }
                    val boxes = if (frozenBox != null) listOf(frozenBox!!) else emptyList()
                    displayResults(boxes, inferMs, frozenImageWidth, frozenImageHeight)
                    requireActivity().runOnUiThread { updatePositionStateAndAnnounceIfStable() }
                }
            }
            imageProxy.close()
        }

        @androidx.camera.core.ExperimentalGetImage
        private fun ImageProxy.toBitmap(): Bitmap? {
            val image = this.image ?: return null
            return BitmapUtils.yuv420ToBitmap(image)
        }
    }

    companion object {
        private const val TAG = "GrabIT_Home"
        private const val YOLOX_MODEL_FILE = "yolox_nano_retail_640_balanced_split_fp16.tflite"
        private const val MIC_RELEASE_TO_STT_DELAY_MS = 150L
    }
}
