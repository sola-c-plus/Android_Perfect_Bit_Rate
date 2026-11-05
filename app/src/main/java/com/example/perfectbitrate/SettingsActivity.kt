package com.example.perfectbitrate

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

// ★ 設定画面（仕様書 §7）: Sony 系アプリ（Music Center / WALKMAN 系）の作法に
//   寄せたリスト形式の設定トップ。カテゴリ見出し（小さなキャプション）＋
//   「タイトル／現在値 or 要約（小さく下）／右端の遷移マーク」の項目で構成する。
//   本 PR（Local モード Phase 1 基盤）では土台と「再生モード」を実装する。
//   既存の DSP / UI 設定はダイアログのまま MainActivity 側に残し、項目タップで
//   設定画面を閉じて MainActivity に開かせる導線だけを用意する
//   （リスト画面への全面移行の仕上げは Phase 2、仕様書 §9）。
//   モード変更はここでは永続化までを行い、実際の切替（再生停止＋flush＋
//   表示切替）は戻った先の MainActivity が永続値を正として同期する。
class SettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "extra_action"
        const val ACTION_OPEN_DSP = "open_dsp"
        const val ACTION_OPEN_UI = "open_ui"
    }

    private val appPrefs by lazy { AppPreferences.get() }

    private lateinit var listContainer: LinearLayout

    private var isDark = true
    private var modeValueText: TextView? = null

    // テーマ色（MainActivity.applyThemeUi と同じパレットに揃える）
    private val colorBg get() = if (isDark) "#000000" else "#FFFFFF"
    private val colorPanel get() = if (isDark) "#0D0D0D" else "#F5F5F7"
    private val colorTitle get() = if (isDark) "#F0F0F0" else "#1C1C1E"
    private val colorValue get() = if (isDark) "#888888" else "#636366"
    private val colorAccent get() = if (isDark) "#E5A93C" else "#D49B28"
    private val colorDivider get() = if (isDark) "#1C1C1C" else "#E0E0E5"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.init(this)
        setContentView(R.layout.activity_settings)

        isDark = computeIsDark()

        val rootLayout = findViewById<LinearLayout>(R.id.settingsRoot)
        val titleBar = findViewById<LinearLayout>(R.id.settingsTitleBar)
        val textTitle = findViewById<TextView>(R.id.textSettingsTitle)
        val btnBack = findViewById<TextView>(R.id.btnSettingsBack)
        listContainer = findViewById(R.id.settingsList)

        rootLayout.setBackgroundColor(Color.parseColor(colorBg))
        titleBar.setBackgroundColor(Color.parseColor(colorPanel))
        textTitle.setTextColor(Color.parseColor(colorTitle))
        btnBack.setTextColor(Color.parseColor(colorAccent))
        btnBack.setOnClickListener { finish() }

        buildList()
    }

    private fun computeIsDark(): Boolean {
        return when (appPrefs.uiThemeMode) {
            "light" -> false
            "auto" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildList() {
        addCategory("再生")
        modeValueText = addRow(
            title = "再生モード",
            value = appPrefs.playbackMode.displayName,
            onClick = { showPlaybackModeDialog() }
        )
        addRow(
            title = "ライブラリ取得範囲",
            value = "Local Music の実装時に有効になります（準備中）",
            onClick = { showNotReadyToast() }
        )
        addRow(
            title = "DSD 変換レート",
            value = "Local Music の実装時に有効になります（準備中）",
            onClick = { showNotReadyToast() }
        )

        addCategory("音質")
        addRow(
            title = "DSP 設定",
            value = "従来のダイアログで開きます",
            onClick = { finishWithAction(ACTION_OPEN_DSP) }
        )

        addCategory("画面と UI")
        addRow(
            title = "UI 設定",
            value = "従来のダイアログで開きます",
            onClick = { finishWithAction(ACTION_OPEN_UI) }
        )

        addCategory("システム")
        addRow(
            title = "バージョン情報",
            value = versionName(),
            onClick = null
        )
        addRow(
            title = "ライセンス表記",
            value = "サードパーティ製ライブラリのライセンス",
            onClick = { showLicenseDialog() }
        )
    }

    private fun addCategory(title: String) {
        val caption = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.parseColor(colorAccent))
            setPadding(dp(2), dp(18), dp(2), dp(4))
        }
        listContainer.addView(caption)
    }

    // 項目行: 左に「タイトル＋現在値（小さく下）」、右端に遷移マーク（›）。
    // 戻り値は現在値の TextView（選択ダイアログで選んだら即時反映するため）。
    private fun addRow(title: String, value: String?, onClick: (() -> Unit)?): TextView? {
        val rowRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val rowMain = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), dp(2), dp(10))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor(colorTitle))
        }
        texts.addView(titleView)
        var valueView: TextView? = null
        if (value != null) {
            valueView = TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor(colorValue))
                setPadding(0, dp(2), 0, 0)
            }
            texts.addView(valueView)
        }
        rowMain.addView(texts)
        if (onClick != null) {
            val chevron = TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(Color.parseColor(colorValue))
            }
            rowMain.addView(chevron)
            val handler: () -> Unit = onClick
            rowRoot.setOnClickListener { handler() }
        }
        rowRoot.addView(rowMain)
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor(colorDivider))
        }
        rowRoot.addView(divider)
        listContainer.addView(rowRoot)
        return valueView
    }

    private fun showPlaybackModeDialog() {
        val modes = PlaybackMode.entries
        val labels = Array<CharSequence>(modes.size) { modes[it].displayName }
        val currentIndex = modes.indexOf(appPrefs.playbackMode)
        AlertDialog.Builder(this)
            .setTitle("再生モード")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selected = modes[which]
                appPrefs.playbackMode = selected
                modeValueText?.text = selected.displayName
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun finishWithAction(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }

    private fun showNotReadyToast() {
        Toast.makeText(this, "準備中です", Toast.LENGTH_SHORT).show()
    }

    private fun showLicenseDialog() {
        AlertDialog.Builder(this)
            .setTitle("ライセンス表記")
            .setMessage(
                "GeckoView\nMozilla Public License 2.0\n\n" +
                    "その他のサードパーティ製ライブラリのライセンスは、" +
                    "導入に合わせて順次ここに集約します。"
            )
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun versionName(): String {
        return try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }
}
