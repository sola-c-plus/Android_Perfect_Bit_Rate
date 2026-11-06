package com.example.perfectbitrate

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// ★ ライセンス表記画面（仕様書 §7 の「システム」カテゴリ）。
//   同梱するサードパーティ製ライブラリのライセンスを一覧表示する、
//   シンプルなスクロールテキスト画面。
//   FFmpeg は LGPL 2.1 表記とする（リポジトリ同梱の
//   native/ffmpeg/LICENSE.md と COPYING.LGPLv2.1 の内容に沿う。
//   本アプリの FFmpeg は LGPL 構成の自前ビルドで、GPL 部品は含まない）。
//   その他のライブラリは既存の表記（GeckoView = MPL 2.0 等）に合わせる。
class LicenseActivity : AppCompatActivity() {

    private val appPrefs by lazy { AppPreferences.get() }

    private var isDark = true

    // テーマ色（SettingsActivity と同じパレットに揃える）
    private val colorBg get() = if (isDark) "#000000" else "#FFFFFF"
    private val colorPanel get() = if (isDark) "#0D0D0D" else "#F5F5F7"
    private val colorTitle get() = if (isDark) "#F0F0F0" else "#1C1C1E"
    private val colorBody get() = if (isDark) "#C8C8C8" else "#3A3A3C"
    private val colorAccent get() = if (isDark) "#E5A93C" else "#D49B28"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.init(this)
        setContentView(R.layout.activity_license)

        isDark = computeIsDark()

        val rootLayout = findViewById<LinearLayout>(R.id.licenseRoot)
        val titleBar = findViewById<LinearLayout>(R.id.licenseTitleBar)
        val textTitle = findViewById<TextView>(R.id.textLicenseTitle)
        val btnBack = findViewById<TextView>(R.id.btnLicenseBack)
        val textBody = findViewById<TextView>(R.id.textLicenseBody)

        rootLayout.setBackgroundColor(Color.parseColor(colorBg))
        titleBar.setBackgroundColor(Color.parseColor(colorPanel))
        textTitle.setTextColor(Color.parseColor(colorTitle))
        btnBack.setTextColor(Color.parseColor(colorAccent))
        btnBack.setOnClickListener { finish() }

        textBody.setTextColor(Color.parseColor(colorBody))
        textBody.text = licenseText()
    }

    private fun computeIsDark(): Boolean {
        return when (appPrefs.uiThemeMode) {
            "light" -> false
            "auto" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    private fun licenseText(): String {
        return """
            このアプリは、以下のサードパーティ製ライブラリを使用しています。

            ■ FFmpeg 7.1.1
            GNU Lesser General Public License version 2.1 or later (LGPL v2.1+)
            https://ffmpeg.org/

            Local Music のデコードに FFmpeg（libavcodec / libavformat / libavutil / libswresample）を使用しています。

            "Most files in FFmpeg are under the GNU Lesser General Public License version 2.1 or later (LGPL v2.1+). Read the file COPYING.LGPLv2.1 for details. Some other files have MIT/X11/BSD-style licenses. In combination the LGPL v2.1+ applies to FFmpeg."

            このアプリに含まれる FFmpeg は LGPL 構成でビルドされており、GPL 部品は含んでいません。LGPL ライセンス全文は、FFmpeg の配布物に含まれる COPYING.LGPLv2.1 を参照してください。


            ■ GeckoView (Mozilla)
            Mozilla Public License 2.0
            https://mozilla.org/MPL/2.0/


            ■ AndroidX (AppCompat / Core / Media / DocumentFile)
            Apache License 2.0


            ■ Material Components for Android (Google)
            Apache License 2.0


            ■ Kotlin スタンダードライブラリ (JetBrains)
            Apache License 2.0
        """.trimIndent()
    }
}
