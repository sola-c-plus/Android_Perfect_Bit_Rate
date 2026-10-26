package com.example.perfectbitrate

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 触覚フィードバックが有効な時だけ、操作の手応えとしてしっかり振動させる。
 * 設定は呼び出しのたびに読み直すため、変更は再起動なしで即反映される。
 *
 * 振動は Vibrator 経由で VibrationEffect.EFFECT_HEAVY_CLICK を鳴らす（VIBRATE 権限が必要、
 * マニフェストで宣言済み）。View.performHapticFeedback と違い、端末の「タッチフィードバック」
 * システム設定に抑制されない。minSdk 33 のため、VibratorManager（API 31+）と
 * createPredefined（API 29+）を無条件で使える。
 *
 * Context 版は Service から使うためのもの。Vol ロック時に MediaSession がリモート再生扱いに
 * なると音量キーは Service 側の VolumeProvider が受け取るため、画面の無い経路でも
 * 同じ強さで鳴らせるよう、実処理をこちらに寄せている（View 版はそのラッパー）。
 */
fun Context.hapticTick(prefs: AppPreferences) {
    if (!prefs.isHapticFeedbackEnabled) return
    tryVibrate()
}

fun View.hapticTick(prefs: AppPreferences) {
    if (!prefs.isHapticFeedbackEnabled) return
    if (!context.tryVibrate()) {
        // Vibrator を使えない環境だけ、従来の View ハプティクスに落とす
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}

/** Vibrator で鳴らせたら true、使えない環境なら false を返す。 */
private fun Context.tryVibrate(): Boolean {
    val vibrator = getVibrator() ?: return false
    if (!vibrator.hasVibrator()) return false
    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    return true
}

private fun Context.getVibrator(): Vibrator? {
    val manager = getSystemService(VibratorManager::class.java) ?: return null
    return manager.defaultVibrator
}
