package com.example.perfectbitrate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class UiSettingsDialog(
    private val activity: Activity,
    private val isDarkTheme: Boolean,
    private val topPanelHeight: Int,
    private val onThemeChanged: (String) -> Unit,
    private val onAdBlockChanged: (Boolean) -> Unit,
    private val onPlayerCommand: (String) -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onDismiss: () -> Unit
) : PlayerDialogController {

    private var textTrackTitle: TextView? = null
    private var textTrackArtist: TextView? = null
    private var textCurrentTime: TextView? = null
    private var textTotalTime: TextView? = null
    private var imageArtwork: ImageView? = null
    private var seekBar: SeekBar? = null
    private var btnPlayPause: ImageButton? = null

    private var isUserSeeking = false
    private var currentDuration = 0L

    fun show() {
        val bottomSheetDialog = BottomSheetDialog(activity, R.style.CustomBottomSheetDialogTheme).apply {
            window?.setDimAmount(0f)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_ui_settings, null)
        bottomSheetDialog.setContentView(view)

        val btnClose = view.findViewById<ImageButton>(R.id.btnUiDialogClose)
        val switchAdBlock = view.findViewById<SwitchCompat>(R.id.dialogSwitchAdBlock)
        val spinnerTheme = view.findViewById<Spinner>(R.id.dialogSpinnerTheme)
        val btnBatteryIgnore = view.findViewById<Button>(R.id.btnBatteryIgnore)

        seekBar = view.findViewById(R.id.dialogSeekBarUi)
        btnPlayPause = view.findViewById(R.id.dialogBtnPlayPauseUi)
        val btnPrev = view.findViewById<ImageButton>(R.id.dialogBtnPrevTrackUi)
        val btnNext = view.findViewById<ImageButton>(R.id.dialogBtnNextTrackUi)

        imageArtwork = view.findViewById(R.id.dialogImageArtworkUi)
        textTrackTitle = view.findViewById(R.id.dialogTextTrackTitleUi)
        textTrackArtist = view.findViewById(R.id.dialogTextTrackArtistUi)
        textCurrentTime = view.findViewById(R.id.dialogTextCurrentTimeUi)
        textTotalTime = view.findViewById(R.id.dialogTextTotalTimeUi)

        if (!isDarkTheme) {
            applyLightModeStyle(view, btnClose, switchAdBlock, spinnerTheme, btnPrev, btnNext, btnPlayPause!!)
        }

        bottomSheetDialog.setOnShowListener {
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                val screenH = activity.resources.displayMetrics.heightPixels
                val targetH = screenH - topPanelHeight
                if (targetH > 0) {
                    val lp = bottomSheet.layoutParams
                    lp.height = targetH
                    bottomSheet.layoutParams = lp
                    behavior.peekHeight = targetH
                }
            }
        }

        val themeOptions = arrayOf("Dark (ダーク)", "Light (ライト)", "Auto (端末の設定に連動)")
        val themeValues = arrayOf("dark", "light", "auto")
        val spinnerLayout = if (isDarkTheme) R.layout.item_spinner_dap else R.layout.item_spinner_dap_light
        val themeAdapter = ArrayAdapter(activity, spinnerLayout, themeOptions).apply {
            setDropDownViewResource(spinnerLayout)
        }
        spinnerTheme.adapter = themeAdapter
        val appPrefs = AppPreferences.get()
        spinnerTheme.setSelection(themeValues.indexOf(appPrefs.uiThemeMode).coerceAtLeast(0))

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selected = themeValues[position]
                if (selected != appPrefs.uiThemeMode) {
                    appPrefs.uiThemeMode = selected
                    onThemeChanged(selected)
                    bottomSheetDialog.dismiss()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnBatteryIgnore.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        switchAdBlock.isChecked = appPrefs.isAdBlockEnabled
        switchAdBlock.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.isAdBlockEnabled = isChecked
            onAdBlockChanged(isChecked)
        }

        btnPlayPause?.setOnClickListener { onPlayerCommand("play_pause") }
        btnPrev.setOnClickListener { onPlayerCommand("prev") }
        btnNext.setOnClickListener { onPlayerCommand("next") }

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentDuration > 0) {
                    val seekMs = (progress.toLong() * currentDuration) / 1000L
                    textCurrentTime?.text = formatTime(seekMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                if (currentDuration > 0 && sb != null) {
                    val seekMs = (sb.progress.toLong() * currentDuration) / 1000L
                    onSeekTo(seekMs)
                }
            }
        })

        btnClose.setOnClickListener { bottomSheetDialog.dismiss() }

        bottomSheetDialog.setOnDismissListener {
            textTrackTitle = null
            textTrackArtist = null
            textCurrentTime = null
            textTotalTime = null
            imageArtwork = null
            seekBar = null
            btnPlayPause = null
            onDismiss()
        }

        bottomSheetDialog.show()
    }

    override fun updatePlayerState(
        title: String,
        artist: String,
        artwork: Bitmap?,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long
    ) {
        currentDuration = durationMs
        textTrackTitle?.text = title
        textTrackArtist?.text = artist

        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (artwork != null) {
            imageArtwork?.setImageBitmap(artwork)
        }

        if (!isUserSeeking && durationMs > 0) {
            val progress = ((currentPositionMs.toDouble() / durationMs.toDouble()) * 1000).toInt().coerceIn(0, 1000)
            seekBar?.progress = progress
            textCurrentTime?.text = formatTime(currentPositionMs)
            textTotalTime?.text = formatTime(durationMs)
        } else if (durationMs <= 0L) {
            seekBar?.progress = 0
            textCurrentTime?.text = "0:00"
            textTotalTime?.text = "0:00"
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            val pkg = activity.packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$pkg")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {}
                }
            } else {
                Toast.makeText(activity, "バッテリー最適化は既に「無制限」に設定されています", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun applyLightModeStyle(
        view: View,
        btnClose: ImageButton,
        switchAdBlock: SwitchCompat,
        spinnerTheme: Spinner,
        btnPrev: ImageButton,
        btnNext: ImageButton,
        btnPlayPause: ImageButton
    ) {
        view.setBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        view.findViewById<View>(R.id.layoutSectionTheme)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
        view.findViewById<View>(R.id.layoutSectionBattery)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
        view.findViewById<TextView>(R.id.textBatteryTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textBatterySub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textAdBlockTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textAdBlockSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<View>(R.id.dividerUi1)?.setBackgroundColor(Color.parseColor("#E0E0E5"))

        val swTrackLight = ContextCompat.getDrawable(activity, R.drawable.switch_track_walkman_outline_light)
        val swThumbLight = ContextCompat.getDrawable(activity, R.drawable.switch_thumb_light)
        switchAdBlock.trackDrawable = swTrackLight
        switchAdBlock.thumbDrawable = swThumbLight

        btnClose.setBackgroundResource(R.drawable.bg_btn_icon_light)
        btnClose.setColorFilter(Color.parseColor("#1C1C1E"))

        spinnerTheme.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerTheme.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)

        seekBar?.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#D1D1D6"))
        seekBar?.progressTintList = ColorStateList.valueOf(Color.parseColor("#D49B28"))
        seekBar?.thumbTintList = ColorStateList.valueOf(Color.parseColor("#1C1C1E"))

        btnPrev.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
        btnNext.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
        btnPlayPause.setBackgroundResource(R.drawable.bg_btn_walkman_circle_large_light)
        btnPrev.setColorFilter(Color.parseColor("#1C1C1E"))
        btnNext.setColorFilter(Color.parseColor("#1C1C1E"))
        btnPlayPause.setColorFilter(Color.parseColor("#1C1C1E"))

        view.findViewById<View>(R.id.uiDialogPlayerControl)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
        view.findViewById<View>(R.id.uiPlayerDivider)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
        view.findViewById<TextView>(R.id.dialogTextTrackTitleUi)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.dialogTextTrackArtistUi)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.dialogTextCurrentTimeUi)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.dialogTextTotalTimeUi)?.setTextColor(Color.parseColor("#636366"))
    }
}