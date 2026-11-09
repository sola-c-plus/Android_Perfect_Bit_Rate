package com.example.perfectbitrate

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * ★ ダイアログ UI 統一 PR-B: アプリ内ダイアログの共通生成口。
 *
 * 従来は全ダイアログがテーマ引数なしの AlertDialog.Builder で作られ、
 * 見た目が端末・システム側の標準に流れて黒 UI の上に白く浮くことがあった。
 * ここではアプリのテーマ設定（uiThemeMode: ダーク/ライト/自動）に合わせ、
 * ThemeOverlay.PBR.Dialog（または .Light）を明示選択した
 * MaterialAlertDialogBuilder を返すことで、全ダイアログを画面本体と
 * 同じ側の配色に揃える。色は themes.xml のオーバーレイが PR-A の
 * 色トークンを参照する形で完結させてあり、付け替えコードは持たない。
 */
object PbrDialogs {

    /**
     * アプリのテーマ設定に基づくダーク判定。MainActivity.isDarkThemeActive /
     * SettingsActivity.computeIsDark と同じ規則（"light"→false、"auto"→端末の
     * 夜間モード、それ以外→true）。
     */
    fun isDark(context: Context): Boolean {
        val mode = runCatching { AppPreferences.get().uiThemeMode }.getOrNull() ?: "dark"
        return when (mode) {
            "light" -> false
            "auto" -> (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    private fun overlayRes(context: Context): Int =
        if (isDark(context)) R.style.ThemeOverlay_PBR_Dialog else R.style.ThemeOverlay_PBR_Dialog_Light

    /**
     * ダイアログと同じオーバーレイを被せた Context。ダイアログへ渡す
     * EditText 等のカスタムビューの生成に使う（生成側の素の Activity
     * テーマのままだと、暗いダイアログ地の上で文字色が見えなくなるため）。
     */
    fun themedContext(context: Context): Context =
        ContextThemeWrapper(context, overlayRes(context))

    /** テーマ付きのダイアログビルダー。全ダイアログはここ経由で生成する。 */
    fun builder(context: Context): MaterialAlertDialogBuilder =
        MaterialAlertDialogBuilder(context, overlayRes(context))

    /**
     * 破壊的確認（削除・上書き）の肯定ボタンだけを danger 色にする。
     * オーバーレイでは肯定ボタン一律 accent になるため、対象ダイアログは
     * show() の直後にこれを呼ぶ。
     */
    fun applyDestructive(dialog: AlertDialog) {
        val colorRes = if (isDark(dialog.context)) R.color.danger else R.color.danger_light
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(ContextCompat.getColor(dialog.context, colorRes))
    }
}
