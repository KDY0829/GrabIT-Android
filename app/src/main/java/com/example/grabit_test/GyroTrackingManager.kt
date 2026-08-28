package com.example.grabitTest

import android.content.Context
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs

private const val NS2S = 1.0f / 1_000_000_000f
private const val MIN_ROTATION_THRESHOLD = 0.02f
private const val SENSITIVITY_FACTOR = 0.8f
private const val SMOOTHING_ALPHA = 0.25f  // 0.15→0.25: 떨림 감소

/** 가속도 데드존: 0.1 미만은 노이즈로 무시 */
private const val ACCEL_DEADZONE = 0.1f
/** 속도 감쇠(마찰력): 매 프레임 velocity *= VELOCITY_DAMPING */
private const val VELOCITY_DAMPING = 0.8f
/** 물체까지 거리 가정 (팔 뻗은 거리, m) */
private const val ASSUMED_DISTANCE_M = 0.5f
/** 가속도 병진 반영 비중 (1.0=전부, 0.4=드리프트 억제) */
private const val TRANSLATION_WEIGHT = 0.4f
/** 화면 밖 허용 여유(px): 이 정도까지 벗어나도 바로 해제하지 않음 */
private const val OUT_OF_BOUNDS_MARGIN = 150f
/** 연속 N프레임 화면 밖이어야 고정 해제 (노이즈로 인한 급격한 해제 방지) */
private const val OUT_OF_BOUNDS_FRAMES_TO_LOSE = 15
private const val TAG_GYRO = "GyroTrack"
/** 자이로 워밍업: 이 시간(ms) 동안은 센서 보정 없이 고정 위치 유지. 찾은 직후 박스가 날아가는 것 방지 */
private const val GYRO_WARMUP_MS = 500L
/** 이 yaw 변화량(rad) 이상이면 빠른 회전으로 간주 → onDeltaYawTooHigh 콜백 (TTS "천천히 움직여주세요" 등) */
private const val DELTA_YAW_FAST_THRESHOLD_RAD = 0.25f
private const val DELTA_YAW_TOO_HIGH_COOLDOWN_MS = 5000L

/** 회전 적용한 박스 업데이트: rect + 화면 롤(옆으로 눕힌 각도, 도) */
data class BoxUpdate(val rect: RectF, val rotationDegrees: Float)

class GyroTrackingManager(
    private val context: Context,
    private val onBoxUpdate: (BoxUpdate) -> Unit,
    private val onTrackingLost: () -> Unit
) : SensorEventListener {

    /** 빠른 yaw 회전 시 한 번 호출 (TTS "천천히 움직여주세요" 등). 쿨다운 적용됨 */
    var onDeltaYawTooHigh: (() -> Unit)? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    private var initialRotationMatrix = FloatArray(9)
    private var currentRotationMatrix = FloatArray(9)
    private var initialRect = RectF()
    private var currentSmoothedRect = RectF()

    var isLocked = false
    /** occlusion 시 optical flow 사용 → true면 onBoxUpdate 호출 안 함 */
    var suspendUpdates = false
    private var timestamp: Long = 0
    private var startTrackingWallTimeMs: Long = 0
    private var outOfBoundsCount = 0
    private var smoothedRollDegrees = 0f
    /** 워밍업 종료 시 기준 회전을 현재 값으로 리셋했는지 (한 번에 1.2초치 회전이 적용되는 것 방지) */
    private var warmupReferenceReset = false
    private var lastDeltaYawRad = 0f
    private var lastDeltaPitchRad = 0f
    private var lastDeltaYawTooHighInvokedMs = 0L

    // 화면 크기 및 FOV
    private var screenWidth = 1080f
    private var screenHeight = 2340f
    private var horizontalFov = Math.toRadians(79.0).toFloat()
    private var verticalFov = Math.toRadians(60.0).toFloat()
    private var pixelsPerRadianX = 0f
    private var pixelsPerRadianY = 0f

    // 가속도 적분용 (Sensor Fusion)
    private var velocityX = 0f
    private var velocityY = 0f
    private var distanceX = 0f  // m
    private var distanceY = 0f  // m
    private var lastTimestampAccel = 0L

    fun startTracking(rect: RectF, width: Int, height: Int) {
        initialRect.set(rect)
        currentSmoothedRect.set(rect)

        screenWidth = width.toFloat()
        screenHeight = height.toFloat()
        pixelsPerRadianX = screenWidth / horizontalFov
        pixelsPerRadianY = screenHeight / verticalFov

        velocityX = 0f
        velocityY = 0f
        distanceX = 0f
        distanceY = 0f
        lastTimestampAccel = 0L

        isLocked = true
        timestamp = 0
        startTrackingWallTimeMs = System.currentTimeMillis()
        outOfBoundsCount = 0
        smoothedRollDegrees = 0f
        warmupReferenceReset = false

        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopTracking() {
        isLocked = false
        sensorManager.unregisterListener(this)
    }

    /** YOLOX 검증 시 시각적 위치로 보정 (드리프트 제거) */
    fun correctPosition(rect: RectF) {
        initialRect.set(rect)
        currentSmoothedRect.set(rect)
        velocityX = 0f
        velocityY = 0f
        distanceX = 0f
        distanceY = 0f
        lastTimestampAccel = 0L
        timestamp = 0L
        smoothedRollDegrees = 0f
        outOfBoundsCount = 0
    }

    fun resetToSearchingFromUI() {
        stopTracking()
        onTrackingLost()
    }

    /** 자이로 센서로 예측된 현재 박스 위치 (Gyro-Guided Matching용). LOCKED가 아니면 null */
    fun getPredictedRect(): RectF? = if (isLocked) RectF(currentSmoothedRect) else null

    /** 현재 프레임 기준 yaw 변화량(도). 락 시작 시점 대비 */
    fun getCurrentDeltaYawDegrees(): Float = Math.toDegrees(lastDeltaYawRad.toDouble()).toFloat()

    /** 현재 프레임 기준 pitch 변화량(도). 락 시작 시점 대비 */
    fun getCurrentDeltaPitchDegrees(): Float = Math.toDegrees(lastDeltaPitchRad.toDouble()).toFloat()

    /** 화면 X 픽셀 오프셋을 수평 각도(도)로 변환 (이미지 너비/FOV 기반) */
    fun pixelOffsetToDegreesX(deltaPx: Float): Float {
        if (pixelsPerRadianX <= 0f) return 0f
        return Math.toDegrees((deltaPx / pixelsPerRadianX).toDouble()).toFloat()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isLocked || event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> processLinearAcceleration(event)
            Sensor.TYPE_ROTATION_VECTOR -> processRotationVector(event)
        }
    }

    private fun processLinearAcceleration(event: SensorEvent) {
        var ax = event.values.getOrNull(0) ?: 0f
        var ay = event.values.getOrNull(1) ?: 0f

        if (abs(ax) < ACCEL_DEADZONE) ax = 0f
        if (abs(ay) < ACCEL_DEADZONE) ay = 0f

        val now = event.timestamp
        if (lastTimestampAccel == 0L) {
            lastTimestampAccel = now
            return
        }
        val dt = (now - lastTimestampAccel) * NS2S
        lastTimestampAccel = now
        if (dt <= 0f || dt > 1f) return

        velocityX += ax * dt
        velocityY += ay * dt

        velocityX *= VELOCITY_DAMPING
        velocityY *= VELOCITY_DAMPING

        distanceX += velocityX * dt
        distanceY += velocityY * dt
    }

    private fun ensureRotationVector4(values: FloatArray): FloatArray {
        if (values.size >= 4) return values
        val x = values.getOrNull(0) ?: 0f
        val y = values.getOrNull(1) ?: 0f
        val z = values.getOrNull(2) ?: 0f
        val w = kotlin.math.sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
        return floatArrayOf(x, y, z, w)
    }

    private fun processRotationVector(event: SensorEvent) {
        val rv = ensureRotationVector4(event.values)
        SensorManager.getRotationMatrixFromVector(currentRotationMatrix, rv)

        val adjustedRotationMatrix = FloatArray(9)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_0 ->
                System.arraycopy(currentRotationMatrix, 0, adjustedRotationMatrix, 0, 9)
            Surface.ROTATION_90 ->
                SensorManager.remapCoordinateSystem(currentRotationMatrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjustedRotationMatrix)
            Surface.ROTATION_270 ->
                SensorManager.remapCoordinateSystem(currentRotationMatrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjustedRotationMatrix)
            else ->
                System.arraycopy(currentRotationMatrix, 0, adjustedRotationMatrix, 0, 9)
        }

        val orientation = FloatArray(3)
        SensorManager.getOrientation(adjustedRotationMatrix, orientation)

        if (timestamp == 0L) {
            initialRotationMatrix = adjustedRotationMatrix.clone()
            timestamp = event.timestamp
            return
        }

        val initialOrientation = FloatArray(3)
        SensorManager.getOrientation(initialRotationMatrix, initialOrientation)

        var deltaYaw = orientation[0] - initialOrientation[0]
        var deltaPitch = orientation[1] - initialOrientation[1]
        var deltaRoll = orientation[2] - initialOrientation[2]

        if (deltaYaw > Math.PI) deltaYaw -= (2 * Math.PI).toFloat()
        if (deltaYaw < -Math.PI) deltaYaw += (2 * Math.PI).toFloat()
        if (deltaPitch > Math.PI) deltaPitch -= (2 * Math.PI).toFloat()
        if (deltaPitch < -Math.PI) deltaPitch += (2 * Math.PI).toFloat()
        if (deltaRoll > Math.PI) deltaRoll -= (2 * Math.PI).toFloat()
        if (deltaRoll < -Math.PI) deltaRoll += (2 * Math.PI).toFloat()

        if (abs(deltaYaw) < MIN_ROTATION_THRESHOLD) deltaYaw = 0f
        if (abs(deltaPitch) < MIN_ROTATION_THRESHOLD) deltaPitch = 0f

        lastDeltaYawRad = deltaYaw
        lastDeltaPitchRad = deltaPitch
        val deltaRollDegrees = Math.toDegrees(deltaRoll.toDouble()).toFloat()
        smoothedRollDegrees += (deltaRollDegrees - smoothedRollDegrees) * SMOOTHING_ALPHA

        // 카메라 오른쪽 회전(양 yaw) → 물체는 화면에서 왼쪽으로 이동하므로 부호 반전
        // 워밍업: 시작 직후 센서가 불안정해 박스가 밀리지 않도록 Nms 동안 보정 스킵
        val elapsedMs = System.currentTimeMillis() - startTrackingWallTimeMs
        if (elapsedMs < GYRO_WARMUP_MS) {
            val update = BoxUpdate(currentSmoothedRect, -smoothedRollDegrees)
            if (!suspendUpdates) onBoxUpdate(update)
            return
        }

        // 워밍업 직후 첫 프레임: 기준 회전을 "지금"으로 리셋. 그렇지 않으면 1.2초치 누적 회전이 한 번에 적용되어 박스가 왼쪽으로 튐
        if (!warmupReferenceReset) {
            warmupReferenceReset = true
            initialRotationMatrix = adjustedRotationMatrix.clone()
            velocityX = 0f
            velocityY = 0f
            distanceX = 0f
            distanceY = 0f
            lastTimestampAccel = 0L
            return
        }
        if (kotlin.math.abs(deltaYaw) >= DELTA_YAW_FAST_THRESHOLD_RAD) {
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastDeltaYawTooHighInvokedMs >= DELTA_YAW_TOO_HIGH_COOLDOWN_MS) {
                lastDeltaYawTooHighInvokedMs = nowMs
                onDeltaYawTooHigh?.invoke()
            }
        }

        val rotationShiftX = -deltaYaw * pixelsPerRadianX * SENSITIVITY_FACTOR
        val rotationShiftY = deltaPitch * pixelsPerRadianY * SENSITIVITY_FACTOR

        val pixelsPerMeterX = pixelsPerRadianX / ASSUMED_DISTANCE_M
        val pixelsPerMeterY = pixelsPerRadianY / ASSUMED_DISTANCE_M
        val translationShiftX = distanceX * pixelsPerMeterX * TRANSLATION_WEIGHT
        val translationShiftY = distanceY * pixelsPerMeterY * TRANSLATION_WEIGHT

        val targetX = initialRect.left + rotationShiftX - translationShiftX
        val targetY = initialRect.top + rotationShiftY - translationShiftY

        val dx = (targetX - currentSmoothedRect.left) * SMOOTHING_ALPHA
        val dy = (targetY - currentSmoothedRect.top) * SMOOTHING_ALPHA

        val newLeft = currentSmoothedRect.left + dx
        val newTop = currentSmoothedRect.top + dy

        currentSmoothedRect.set(newLeft, newTop, newLeft + initialRect.width(), newTop + initialRect.height())

        val margin = OUT_OF_BOUNDS_MARGIN
        val isOutOfBounds = currentSmoothedRect.right < -margin || currentSmoothedRect.left > screenWidth + margin ||
            currentSmoothedRect.bottom < -margin || currentSmoothedRect.top > screenHeight + margin

        // 카메라 이미지는 폰 기울임에 따라 이미 한 번 기울어지므로, 박스는 반대 방향으로 회전해 보정
        val update = BoxUpdate(currentSmoothedRect, -smoothedRollDegrees)
        if (suspendUpdates) return  // occlusion: optical flow가 박스 위치 담당
        if (isOutOfBounds) {
            outOfBoundsCount++
            if (outOfBoundsCount >= OUT_OF_BOUNDS_FRAMES_TO_LOSE) {
                stopTracking()
                onTrackingLost()
            } else {
                onBoxUpdate(update)
            }
        } else {
            outOfBoundsCount = 0
            onBoxUpdate(update)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
