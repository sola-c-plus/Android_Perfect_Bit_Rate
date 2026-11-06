package com.example.perfectbitrate

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import java.util.Locale

// ★ 設定画面（仕様書 §7）: Sony 系アプリ（Music Center / WALKMAN 系）の作法に
//   寄せたリスト形式の設定トップ。カテゴリ見出し（小さなキャプション）＋
//   「タイトル／現在値 or 要約（小さく下）／右端の遷移マーク」の項目で構成する。
//   Local モード Phase 1 の設定項目として、再生モード / DSD 変換レート /
//   ライブラリ取得範囲（MediaStore ON/OFF、SAF フォルダの追加・削除、
//   再スキャン予約）を実装する。スイッチ項目は右端にトグルを置く。
//   既存の DSP / UI 設定はダイアログのまま MainActivity 側に残し、項目タップで
//   設定画面を閉じて MainActivity に開かせる導線を用意する
//   （リスト画面への全面移行の仕上げは Phase 2、仕様書 §9）。
//   モード変更はここでは永続化までを行い、実際の切替（再生停止＋flush＋
//   表示切替）は戻った先の MainActivity が永続値を正として同期する。
//   取得範囲の変更も同様に prefs への保存までで、戻った先の MainActivity が
//   ライブラリを再読込して一覧へ反映する（再スキャン予約フラグの消費も
//   MainActivity 側）。
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
    private var dsdValueText: TextView? = null

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
        dsdValueText = addRow(
            title = "DSD 変換レート",
            value = formatDsdRate(appPrefs.dsdPcmRateHz),
            onClick = { showDsdRateDialog() }
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

    // ★ DSD 変換レート（仕様書 §5.2）: 選択肢は AppPreferences の
    //   DSD_PCM_RATE_CHOICES が正。変更すると、次回のライブラリ
    //   リフレッシュで DSD 曲が再プローブされ、キャッシュの表示レートも
    //   新しい値に追いつく（LibraryRepository 側の再プローブ判定）。
    private fun showDsdRateDialog() {
        val choices = AppPreferences.DSD_PCM_RATE_CHOICES
        val labels = Array<CharSequence>(choices.size) { formatDsdRate(choices[it]) }
        val currentIndex = choices.indexOf(appPrefs.dsdPcmRateHz).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("DSD 変換レート")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val selected = choices[which]
                appPrefs.dsdPcmRateHz = selected
                dsdValueText?.text = formatDsdRate(selected)
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun formatDsdRate(hz: Int): String {
        return String.format(Locale.US, "%.1fkHz", hz / 1000.0)
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

    private fun finishWithAction(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
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
