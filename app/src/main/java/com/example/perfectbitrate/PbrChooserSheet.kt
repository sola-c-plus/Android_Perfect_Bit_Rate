package com.example.perfectbitrate

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * ★ ダイアログ UI 統一 PR-C: 選択系 UI の共通ボトムシート。
 *
 * PR-B でテーマ追随させた選択系ダイアログ（単一選択・リスト/アクション
 * 選択の計 9 件）を、DSP 設定シートの作法に倣った共通シートへ移行する
 * ための部品。土台は既存シートと同じ bg_bottom_sheet_dap（地
 * main_background、上角丸 16dp、border_sheet 1dp）、つまみは accent、
 * 見出しは DSP 見出し書式（accent・condensed bold）。確認系・入力系は
 * 形態として中央ダイアログが正しいため PR-B の PbrDialogs のまま残す。
 *
 * 振る舞いは移行前の AlertDialog と等価に保つ: 行をタップしたら
 * シートを閉じてから onSelect を呼ぶ（保存先・キー・既定値は呼び出し
 * 側の従来処理そのまま）。キャンセルはシートの外タップ／下スワイプ／
 * 戻る操作での dismiss が担う（既存シートと同じ dim なし作法）。
 * ダーク/ライトは PbrDialogs.isDark（uiThemeMode 連動）で判定し、
 * ライト時は地・見出し・文字・選択行の面を _light トークンへ寄せる。
 * 色は全て PR-A トークンのみ（新規色なし）。
 */
object PbrChooserSheet {

    /** 通常のリスト/アクション選択（タップ即確定）。 */
    fun showItems(
        context: Context,
        title: String,
        items: List<CharSequence>,
        onSelect: (Int) -> Unit
    ) {
        show(context, title, items, checkedIndex = -1, onSelect = onSelect)
    }

    /** 単一選択（現在の値にラジオ印と選択行の面を付け、タップ即確定）。 */
    fun showSingleChoice(
        context: Context,
        title: String,
        items: List<CharSequence>,
        checkedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        show(context, title, items, checkedIndex, onSelect)
    }

    private fun show(
        context: Context,
        title: String,
        items: List<CharSequence>,
        checkedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val dark = PbrDialogs.isDark(context)
        val dialog = BottomSheetDialog(context, R.style.CustomBottomSheetDialogTheme).apply {
            window?.setDimAmount(0f)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pbr_chooser, null)
        val root = view.findViewById<LinearLayout>(R.id.chooserRoot)
        val handle = view.findViewById<View>(R.id.chooserHandle)
        val titleView = view.findViewById<TextView>(R.id.chooserTitle)
        val scroll = view.findViewById<ScrollView>(R.id.chooserScroll)
        val list = view.findViewById<LinearLayout>(R.id.chooserList)

        titleView.text = title
        if (!dark) {
            root.setBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
            handle.setBackgroundColor(ContextCompat.getColor(context, R.color.accent_light))
            titleView.setTextColor(ContextCompat.getColor(context, R.color.accent_light))
        }

        val density = context.resources.displayMetrics.density
        val rowHeight = (48 * density).toInt()
        val padH = (20 * density).toInt()
        val textColor = ContextCompat.getColor(
            context, if (dark) R.color.text_primary else R.color.text_primary_light
        )
        val accentColor = ContextCompat.getColor(
            context, if (dark) R.color.accent else R.color.accent_light
        )
        val selectedBg = ContextCompat.getColor(
            context, if (dark) R.color.surface_input else R.color.surface_panel_light
        )
        // 行の押下フィードバック（テーマの selectableItemBackground を前景に被せる）
        val pressed = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, pressed, true)

        items.forEachIndexed { index, label ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
                )
                setPadding(padH, 0, padH, 0)
                if (index == checkedIndex) setBackgroundColor(selectedBg)
                if (pressed.resourceId != 0) {
                    foreground = ContextCompat.getDrawable(context, pressed.resourceId)
                }
                isClickable = true
                isFocusable = true
            }
            if (checkedIndex >= 0) {
                val radio = RadioButton(context).apply {
                    isChecked = index == checkedIndex
                    isClickable = false
                    isFocusable = false
                    buttonTintList = ColorStateList.valueOf(accentColor)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (12 * density).toInt() }
                }
                row.addView(radio)
            }
            val labelView = TextView(context).apply {
                text = label
                setTextColor(textColor)
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            row.addView(labelView)
            row.setOnClickListener {
                dialog.dismiss()
                onSelect(index)
            }
            list.addView(row)
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
            // 項目が多い場合はリスト領域だけをスクロールさせる
            // （画面の約 55% を上限にし、見出しは常に見えるようにする）。
            scroll.post {
                val maxH = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
                if (list.height > maxH) {
                    val lp = scroll.layoutParams
                    lp.height = maxH
                    scroll.layoutParams = lp
                }
            }
        }
        dialog.show()
    }
}
