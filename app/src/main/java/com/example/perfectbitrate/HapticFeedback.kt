package com.example.perfectbitrate

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 触覚フィードバックが有効な時だけ、ボタン操作の手応えとして短く振動させる。
 * 設定は呼び出しのたびに読み直すため、変更は再起動なしで即反映される。
 * View.performHapticFeedback は VIBRATE 権限不要。
 */
fun View.hapticTick(prefs: AppPreferences) {
    if (prefs.isHapticFeedbackEnabled) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}
