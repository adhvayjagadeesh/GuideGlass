package com.impairedvision.guideglass.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object VibrationHelper {

    fun vibrateOneShot(context: Context, durationMs: Long) {
        val vibrator = getVibrator(context) ?: return
        vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    fun vibrateWaveform(context: Context, timings: LongArray, amplitudes: IntArray) {
        val vibrator = getVibrator(context) ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    private fun getVibrator(context: Context): Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
}
