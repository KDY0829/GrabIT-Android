package com.example.grabitTest

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticFeedbackController(context: Context) {

    private val appContext = context.applicationContext

    private val vibrator: Vibrator?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun playDefault(durationMs: Long = 300L) {
        vibrate(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
    }

    fun playCandidateDetected() {
        vibrate(durationMs = 120L, amplitude = 80)
    }

    fun playTargetLock() {
        vibrate(durationMs = 300L, amplitude = 255)
    }

    fun playCenterEntered() {
        vibratePattern(longArrayOf(0L, 250L, 150L, 250L))
    }

    fun playVerifySuccess() {
        vibrate(durationMs = 300L, amplitude = 255)
    }

    fun playVerifyFailure() {
        vibratePattern(longArrayOf(0L, 100L, 100L, 100L, 100L, 100L))
    }

    private fun vibrate(durationMs: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }
}