package com.example.perfectbitrate

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 触覚フィードバックが有効な時だけ、ボタン操作の手応えとしてしっかり振動させる。
 * 設定は呼び出しのたびに読み直すため、変更は再起動なしで即反映される。
 *
 * 振動は Vibrator 経由で VibrationEffect.EFFECT_HEAVY_CLICK を鳴らす（VIBRATE 権限が必要、
 * マニフェストで宣言済み）。View.performHapticFeedback と違い、端末の「タッチフィードバック」
 * システム設定に抑制されない。Vibrator を使えない環境では performHapticFeedback に落ちる。
 * minSdk 33 のため、VibratorManager（API 31+）と createPredefined（API 29+）を無条件で使える。
 */
fun View.hapticTick(prefs: AppPreferences) {
    if (!prefs.isHapticFeedbackEnabled) return
    val vibrator = context.getVibrator()
    if (vibrator != null && vibrator.hasVibrator()) {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    } else {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

private fun Context.getVibrator(): Vibrator? {
    val manager = getSystemService(VibratorManager::class.java) ?: return null
    return manager.defaultVibrator
}
