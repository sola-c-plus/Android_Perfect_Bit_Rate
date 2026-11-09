package com.example.perfectbitrate

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * ★ DSP 設定リスト化 PR-C1: DSP 設定シート内の「行」部品ビルダー。
 *
 * 設定画面（SettingsActivity）の行作法（カテゴリ見出し＋スイッチ行＋
 * 現在値行）を、DSP 設定シート（BottomSheetDialog）の中でも使えるように
 * 切り出したもの。SettingsActivity は Activity 専用のため直接は共有
 * できず、本クラスが同じ作法をシート側で担う（寸法・書式は
 * SettingsActivity の addCategory / addRow / addSwitchRow と同一）。
 *
 * - スイッチ行: 行タップでもトグルできる。状態変更はトグルのリスナー
 *   経由に一本化する（SettingsActivity と同じ規約）。
 * - 現在値行: タイトル＋説明（左）と現在値＋「›」（右）。タップ時の
 *   選択 UI は呼び出し側が PbrChooserSheet で開く。
 * - 色はコンストラクタの isDark で確定する（DSP シートにはテーマ
 *   再生成の仕組みが無く、構築時に決めれば付け替えが不要になる）。
 *   全て PR-A の色トークンのみを使い、新規色は追加しない。
 * - 無効状態は isEnabled ラムダで宣言し、refreshStates() が行の
 *   有効/無効と減光（alpha 0.35、従来のセクション減光 0.3 と視認できる
 *   差が無い範囲で PR-C の選択行 0.35 に統一）を再適用する。あわせて
 *   現在値テキストも currentValue ラムダから再取得する（外部変更の
 *   反映はこの再取得で賄う）。
 */
class DspSettingsRows(
    private val context: Context,
    private val container: LinearLayout,
    private val isDark: Boolean
) {
    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun color(@ColorRes resId: Int): Int = ContextCompat.getColor(context, resId)

    private val colorTitle
        get() = color(if (isDark) R.color.text_primary else R.color.text_primary_light)
    private val colorValue
        get() = color(if (isDark) R.color.text_secondary else R.color.text_secondary_light)
    private val colorAccent
        get() = color(if (isDark) R.color.accent else R.color.accent_light)
    private val colorDivider
        get() = color(if (isDark) R.color.divider else R.color.divider_light)

    private class SwitchEntry(
        val rowRoot: View,
        val toggle: SwitchCompat,
        val isEnabled: () -> Boolean,
        var holdDisabledUntilMs: Long = 0L
    )

    private class ValueEntry(
        val rowRoot: View,
        val valueView: TextView,
        val currentValue: () -> String,
        val isEnabled: () -> Boolean
    )

    private val switchEntries = mutableListOf<SwitchEntry>()
    private val valueEntries = mutableListOf<ValueEntry>()

    /** カテゴリ見出し（SettingsActivity の addCategory と同一書式）。 */
    fun categoryHeader(label: String) {
        val caption = TextView(context).apply {
            text = label
            textSize = 12f
            setTextColor(colorAccent)
            setPadding(dp(2), dp(18), dp(2), dp(4))
        }
        container.addView(caption)
    }

    /** 単独の区切り線をコンテナへ追加する。 */
    fun divider() {
        container.addView(dividerView())
    }

    private fun dividerView(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        )
        setBackgroundColor(colorDivider)
    }

    private fun textsColumn(title: String, description: String?): LinearLayout {
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        texts.addView(TextView(context).apply {
            text = title
            textSize = 15f
            setTextColor(colorTitle)
        })
        if (description != null) {
            texts.addView(TextView(context).apply {
                text = description
                textSize = 12f
                setTextColor(colorValue)
                setPadding(0, dp(2), 0, 0)
            })
        }
        return texts
    }

    private fun rowRootWith(main: View): LinearLayout {
        val rowRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rowRoot.addView(main)
        rowRoot.addView(dividerView())
        return rowRoot
    }

    private fun rowMain(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(10), dp(2), dp(10))
    }

    /**
     * スイッチ行を追加し、トグルを返す。初期値はリスナー登録前に設定
     * するため、構築時に onChange は発火しない。無効条件は isEnabled
     * ラムダで宣言し、refreshStates() で再評価される。
     */
    fun switchRow(
        title: String,
        description: String?,
        initial: Boolean,
        isEnabled: () -> Boolean = { true },
        onChange: (Boolean) -> Unit
    ): SwitchCompat {
        val main = rowMain()
        main.addView(textsColumn(title, description))
        val toggle = SwitchCompat(context).apply {
            // トグルの見た目は SettingsActivity / 従来の DSP シートと共通:
            // 同じ drawable を割り当て、テーマ tint は無効化する。
            trackDrawable = ContextCompat.getDrawable(
                context,
                if (isDark) R.drawable.switch_track_walkman_outline
                else R.drawable.switch_track_walkman_outline_light
            )
            thumbDrawable = ContextCompat.getDrawable(
                context,
                if (isDark) R.drawable.switch_thumb_white
                else R.drawable.switch_thumb_light
            )
            trackTintList = null
            thumbTintList = null
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
        main.addView(toggle)
        val rowRoot = rowRootWith(main)
        // 行タップでも切り替えられるようにし、状態変更はトグルの
        // リスナー経由に一本化する（SettingsActivity と同じ規約）。
        rowRoot.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        container.addView(rowRoot)
        val entry = SwitchEntry(rowRoot, toggle, isEnabled)
        switchEntries.add(entry)
        applySwitchState(entry)
        return toggle
    }

    /**
     * 現在値行を追加し、現在値の TextView を返す。現在値は currentValue
     * ラムダから取得し、refreshStates() のたびに再取得される。タップ時
     * の処理（選択シート表示と保存・反映）は呼び出し側の onClick が担う。
     */
    fun valueRow(
        title: String,
        description: String?,
        currentValue: () -> String,
        isEnabled: () -> Boolean = { true },
        onClick: () -> Unit
    ): TextView {
        val main = rowMain()
        main.addView(textsColumn(title, description))
        val valueView = TextView(context).apply {
            text = currentValue()
            textSize = 14f
            setTextColor(colorValue)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        main.addView(valueView)
        main.addView(TextView(context).apply {
            text = "›"
            textSize = 20f
            setTextColor(colorValue)
        })
        val rowRoot = rowRootWith(main)
        rowRoot.setOnClickListener { onClick() }
        container.addView(rowRoot)
        val entry = ValueEntry(rowRoot, valueView, currentValue, isEnabled)
        valueEntries.add(entry)
        applyValueState(entry)
        return valueView
    }

    /**
     * 指定したトグルを durationMs の間だけ無効化する（Direct 切替時の
     * 二重発火防止ガードの行版）。時間経過後に refreshStates() を呼び、
     * isEnabled ラムダの結果へ戻す。減光は論理的な有効/無効だけに連動
     * し、ガード中は見た目を変えない。
     */
    fun holdSwitchDisabled(toggle: SwitchCompat, durationMs: Long) {
        val entry = switchEntries.firstOrNull { it.toggle === toggle } ?: return
        entry.holdDisabledUntilMs = SystemClock.uptimeMillis() + durationMs
        applySwitchState(entry)
        toggle.postDelayed({ refreshStates() }, durationMs + 50L)
    }

    /** 全行の有効/無効・減光と、現在値テキストを再評価する。 */
    fun refreshStates() {
        switchEntries.forEach { applySwitchState(it) }
        valueEntries.forEach { applyValueState(it) }
    }

    private fun applySwitchState(entry: SwitchEntry) {
        val logicalEnabled = entry.isEnabled()
        val holdActive = SystemClock.uptimeMillis() < entry.holdDisabledUntilMs
        entry.toggle.isEnabled = logicalEnabled && !holdActive
        entry.rowRoot.isClickable = logicalEnabled && !holdActive
        entry.rowRoot.alpha = if (logicalEnabled) 1.0f else 0.35f
    }

    private fun applyValueState(entry: ValueEntry) {
        val enabled = entry.isEnabled()
        entry.rowRoot.isClickable = enabled
        entry.rowRoot.alpha = if (enabled) 1.0f else 0.35f
        entry.valueView.text = entry.currentValue()
    }
}
