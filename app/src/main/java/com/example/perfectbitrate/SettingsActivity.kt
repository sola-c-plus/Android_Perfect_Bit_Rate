package com.example.perfectbitrate

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.documentfile.provider.DocumentFile

// ★ 設定画面（仕様書 §7）: Sony 系アプリ（Music Center / WALKMAN 系）の作法に
//   寄せたリスト形式の設定トップ。カテゴリ見出し（小さなキャプション）＋
//   「タイトル／現在値 or 要約（小さく下）／右端の遷移マーク」の項目で構成する。
//   Local モード Phase 1 の設定項目として、再生モード /
//   ライブラリ取得範囲（MediaStore ON/OFF、SAF フォルダの追加・削除、
//   再スキャン予約）を実装する。スイッチ項目は右端にトグルを置く。
//   音に関する設定（DSD 変換レートを含む）はドメインで寄せ、DSP ボタン
//   単押しの DSP 設定ダイアログ側を正規の置き場所とする（ここには
//   項目を置かない）。UI 設定（テーマ / 広告カット / システムモニター /
//   触覚フィードバック / バッテリー最適化の除外）は旧 UI 設定ダイアログを
//   廃止し、この画面の「画面と UI」カテゴリに実項目として統合した
//   （旧ダイアログへの導線 ACTION_OPEN_UI は廃止）。
//   モード変更はここでは永続化までを行い、実際の切替（再生停止＋flush＋
//   表示切替）は戻った先の MainActivity が永続値を正として同期する。
//   取得範囲の変更も同様に prefs への保存までで、戻った先の MainActivity が
//   ライブラリを再読込して一覧へ反映する（再スキャン予約フラグの消費も
//   MainActivity 側）。テーマと広告カットも同様に、ここでは prefs への
//   保存までを行い、MainActivity 側の表示への適用と Gecko への送信は
//   戻った先の MainActivity が差分を検知して行う（この画面自身の配色は
//   テーマ変更時に recreate して即時反映する）。システムモニターは表示側が
//   prefs を都度参照するため、保存だけで反映される。
class SettingsActivity : AppCompatActivity() {

    private val appPrefs by lazy { AppPreferences.get() }

    private lateinit var listContainer: LinearLayout

    private var isDark = true
    private var modeValueText: TextView? = null
    private var themeValueText: TextView? = null

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
        listContainer.removeAllViews()

        addCategory("再生")
        modeValueText = addRow(
            title = "再生モード",
            value = appPrefs.playbackMode.displayName,
            onClick = { showPlaybackModeDialog() }
        )

        addCategory("ライブラリ取得範囲")
        addSwitchRow(
            title = "MediaStore を使う",
            value = "端末内の曲を自動で取得します",
            checked = appPrefs.useMediaStore,
            onCheckedChange = { appPrefs.useMediaStore = it }
        )
        addRow(
            title = "フォルダを追加",
            value = "フォルダを選んで取得範囲に加えます（SAF）",
            onClick = { safFolderPickerLauncher.launch(null) }
        )
        // 追加済みフォルダは1行ずつ並べ、タップで削除する
        for (folderUri in appPrefs.safFolderUris.sorted()) {
            addRow(
                title = folderDisplayName(folderUri),
                value = "追加済みフォルダ（タップで削除）",
                onClick = { confirmRemoveFolder(folderUri) }
            )
        }
        addRow(
            title = "ライブラリの再スキャン",
            value = "次に Local Music を表示したときに全曲を読み直します",
            onClick = {
                appPrefs.localRescanRequested = true
                Toast.makeText(this, "再スキャンを予約しました", Toast.LENGTH_SHORT).show()
            }
        )

        addCategory("画面と UI")
        themeValueText = addRow(
            title = "テーマ",
            value = themeLabel(appPrefs.uiThemeMode),
            onClick = { showThemeDialog() }
        )
        addSwitchRow(
            title = "広告カット",
            value = "YouTube Music 動画広告の自動スキップ",
            checked = appPrefs.isAdBlockEnabled,
            onCheckedChange = { appPrefs.isAdBlockEnabled = it }
        )
        addSwitchRow(
            title = "システムモニター",
            value = "CPU/RAM負荷・バッファ健全度・出力遅延(LAT)の表示",
            checked = appPrefs.isSysMonitorEnabled,
            onCheckedChange = { appPrefs.isSysMonitorEnabled = it }
        )
        addSwitchRow(
            title = "触覚フィードバック",
            value = "ボタン操作時に振動でフィードバック",
            checked = appPrefs.isHapticFeedbackEnabled,
            onCheckedChange = { enabled ->
                appPrefs.isHapticFeedbackEnabled = enabled
                // ON にした瞬間に1回鳴らしてプレビューにする（旧ダイアログと同じ）
                if (enabled) hapticTick(appPrefs)
            }
        )
        addRow(
            title = "バッテリー最適化の除外",
            value = "画面消灯時や別アプリ使用時の再生停止を防ぎます",
            onClick = { requestIgnoreBatteryOptimizations() }
        )

        addCategory("システム")
        addRow(
            title = "ライセンス表記",
            value = "サードパーティ製ライブラリのライセンス",
            onClick = { startActivity(Intent(this, LicenseActivity::class.java)) }
        )
        addRow(
            title = "バージョン情報",
            value = versionName(),
            onClick = null
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

    // スイッチ項目行: 左に「タイトル＋要約」、右端にトグルを置く（Sony 系の
    // 作法）。行タップでも切り替えられるようにし、状態変更はトグルの
    // リスナー経由に一本化する（二重発火させない）。
    private fun addSwitchRow(
        title: String,
        value: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit
    ) {
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
        if (value != null) {
            val valueView = TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor(colorValue))
                setPadding(0, dp(2), 0, 0)
            }
            texts.addView(valueView)
        }
        rowMain.addView(texts)
        val toggle = SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onCheckedChange(isChecked) }
        }
        rowMain.addView(toggle)
        rowRoot.setOnClickListener { toggle.isChecked = !toggle.isChecked }
        rowRoot.addView(rowMain)
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.parseColor(colorDivider))
        }
        rowRoot.addView(divider)
        listContainer.addView(rowRoot)
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

    private fun themeLabel(mode: String): String {
        return when (mode) {
            "light" -> "ライト"
            "auto" -> "端末の設定に連動"
            else -> "ダーク"
        }
    }

    // ★ テーマ変更: ここでは prefs への保存までを行い、この画面自身の配色は
    //   recreate して即時反映する（旧 UI 設定ダイアログがテーマ変更時に
    //   開き直していたのと同じ見え方）。MainActivity 側の表示への適用は
    //   戻った先が差分を検知して行う。
    private fun showThemeDialog() {
        val values = arrayOf("dark", "light", "auto")
        val labels = Array<CharSequence>(values.size) { themeLabel(values[it]) }
        val currentIndex = values.indexOf(appPrefs.uiThemeMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("テーマ")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selected = values[which]
                dialog.dismiss()
                if (selected != appPrefs.uiThemeMode) {
                    appPrefs.uiThemeMode = selected
                    themeValueText?.text = themeLabel(selected)
                    recreate()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ★ バッテリー最適化の除外リクエスト（旧 UI 設定ダイアログから移設）。
    //   除外済みならその旨をトーストで知らせる。
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {}
                }
            } else {
                Toast.makeText(this, "バッテリー最適化は既に「無制限」に設定されています", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ★ SAF フォルダ追加: Local 画面側の導線と同じ prefs（safFolderUris）を
    //   共有する。永続化許可を取ってから加え、重複追加は防ぐ。追加後は
    //   一覧（フォルダ行）を組み直す。
    private val safFolderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val uriString = uri.toString()
            if (uriString in appPrefs.safFolderUris) {
                Toast.makeText(this, "このフォルダはすでに追加されています", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                appPrefs.safFolderUris = appPrefs.safFolderUris + uriString
                Toast.makeText(this, "フォルダを追加しました", Toast.LENGTH_SHORT).show()
                buildList()
            } catch (e: Exception) {
                Toast.makeText(this, "フォルダを追加できませんでした", Toast.LENGTH_SHORT).show()
            }
        }

    // フォルダ行の表示名: ドキュメント名が取れればそれを、取れなければ
    // ドキュメント ID のパス部分で代用する。
    private fun folderDisplayName(treeUriString: String): String {
        val uri = Uri.parse(treeUriString)
        val name = try {
            DocumentFile.fromTreeUri(this, uri)?.name
        } catch (e: Exception) {
            null
        }
        if (!name.isNullOrBlank()) return name
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            null
        }
        val path = docId?.substringAfter(':')?.trim('/')
        return path?.takeIf { it.isNotBlank() } ?: "フォルダ"
    }

    private fun confirmRemoveFolder(treeUriString: String) {
        AlertDialog.Builder(this)
            .setTitle("フォルダを削除")
            .setMessage("「${folderDisplayName(treeUriString)}」を取得範囲から外しますか？")
            .setPositiveButton("削除") { _, _ -> removeFolder(treeUriString) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun removeFolder(treeUriString: String) {
        try {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUriString), Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            // 許可の解放に失敗しても、取得範囲からは外す
        }
        appPrefs.safFolderUris = appPrefs.safFolderUris - treeUriString
        Toast.makeText(this, "フォルダを削除しました", Toast.LENGTH_SHORT).show()
        buildList()
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
