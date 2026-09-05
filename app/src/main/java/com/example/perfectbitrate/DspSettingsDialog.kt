package com.example.perfectbitrate

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceInfo
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
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

class DspSettingsDialog(
    private val activity: Activity,
    private val isDarkTheme: Boolean,
    private val topPanelHeight: Int,
    private val activeOutputDevice: AudioDeviceInfo?,
    private val baseSampleRate: Int,
    private val isVolumeLocked: Boolean,
    private val onVolumeLockChanged: (Boolean) -> Unit,
    private val onBitModeChanged: (String) -> Unit,
    private val onUpsampleFactorChanged: (Int) -> Unit,
    private val onDirectSourceChanged: (Boolean) -> Unit,
    private val onPlayerCommand: (String) -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onDismiss: () -> Unit
) : PlayerDialogController {

    private var walkmanEqView: WalkmanEqView? = null
    private var textTrackTitle: TextView? = null
    private var textTrackArtist: TextView? = null
    private var textCurrentTime: TextView? = null
    private var textTotalTime: TextView? = null
    private var imageArtwork: ImageView? = null
    private var seekBar: SeekBar? = null
    private var btnPlayPause: ImageButton? = null

    private var layoutSectionPerfMode: View? = null
    private var spinnerPerfMode: Spinner? = null

    private var isUserSeeking = false
    private var currentDuration = 0L

    private val bitOptions = arrayOf("16-bit (Std)", "24-bit (Hi-Res)", "32-bit (Int32)")
    private val bitModeValues = arrayOf("16bit", "24bit", "32bit")

    private val upsampleOptions = arrayOf("1x Direct (Bypass)", "2x Hi-Res (96k/88k)", "4x Ultra (192k/176k)", "8x Master (384k/352k)")
    private val upsampleFactorValues = arrayOf(1, 2, 4, 8)

    private val ditherOptions = arrayOf("TPDF (Studio Standard)", "High-Pass Shaped (Clear)", "Psychoacoustic (Walkman SBM)", "None (Direct Bypass)")
    private val ditherModeValues = arrayOf(1, 2, 3, 0)

    private val dcPhaseOptions = arrayOf(
        "OFF (DC Flat Bypass)",
        "Type A - Standard (Default 4Hz)",
        "Type A - Low (2Hz)",
        "Type A - High (8Hz)",
        "Type B - Standard (4Hz Enhanced)",
        "Type B - Low (2Hz Enhanced)",
        "Type B - High (8Hz Enhanced)"
    )
    private val dcPhaseTypeValues = arrayOf(0, 2, 1, 3, 5, 4, 6)

    private val perfModeOptions = arrayOf("Eco (省電力)", "普通 (標準)", "超高音質 (フルスペック)")

    fun show() {
        val bottomSheetDialog = BottomSheetDialog(activity, R.style.CustomBottomSheetDialogTheme).apply {
            window?.setDimAmount(0f)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_dsp_settings, null)
        FreqPresetManager.hookDspSettingsDialog(view)
        bottomSheetDialog.setContentView(view)

        val btnClose = view.findViewById<ImageButton>(R.id.btnDspDialogClose)
        val switchDirectSource = view.findViewById<SwitchCompat>(R.id.dialogSwitchDirectSource)
        val switchEqEnable = view.findViewById<SwitchCompat>(R.id.switchEqEnable)
        val switchSpectrumEnable = view.findViewById<SwitchCompat>(R.id.switchSpectrumEnable)
        val switchCascadeFir = view.findViewById<SwitchCompat>(R.id.dialogSwitchCascadeFir)
        val switchVolLock = view.findViewById<SwitchCompat>(R.id.dialogSwitchVolLock)

        seekBar = view.findViewById(R.id.dialogSeekBar)
        btnPlayPause = view.findViewById(R.id.dialogBtnPlayPause)
        val btnPrev = view.findViewById<ImageButton>(R.id.dialogBtnPrevTrack)
        val btnNext = view.findViewById<ImageButton>(R.id.dialogBtnNextTrack)

        val btnEqPlus = view.findViewById<ImageButton>(R.id.btnEqPlus)
        val btnEqMinus = view.findViewById<ImageButton>(R.id.btnEqMinus)
        val btnEqFlat = view.findViewById<Button>(R.id.btnEqFlat)
        val btnEqEdit = view.findViewById<Button>(R.id.btnEqEdit)

        walkmanEqView = view.findViewById(R.id.walkmanEqView)
        val textEqBandFreq = view.findViewById<TextView>(R.id.textEqBandFreq)
        val textEqGainValue = view.findViewById<TextView>(R.id.textEqGainValue)
        val layoutEqAdjustControls = view.findViewById<View>(R.id.layoutEqAdjustControls)

        val layoutSectionEq = view.findViewById<View>(R.id.layoutSectionEq)
        val layoutSectionDither = view.findViewById<View>(R.id.layoutSectionDither)
        val layoutSectionDcPhase = view.findViewById<View>(R.id.layoutSectionDcPhase)
        val layoutSectionDsee = view.findViewById<View>(R.id.layoutSectionDsee)
        val layoutSectionUpsample = view.findViewById<View>(R.id.layoutSectionUpsample)
        val layoutSectionCascadeFir = view.findViewById<View>(R.id.layoutSectionCascadeFir)

        val spinnerBitDepth = view.findViewById<Spinner>(R.id.dialogSpinnerBitDepth)
        val spinnerDither = view.findViewById<Spinner>(R.id.dialogSpinnerDither)
        val spinnerDcPhase = view.findViewById<Spinner>(R.id.dialogSpinnerDcPhase)
        spinnerPerfMode = view.findViewById(R.id.dialogSpinnerPerfMode)
        layoutSectionPerfMode = view.findViewById(R.id.layoutSectionPerfMode)
        val spinnerDsee = view.findViewById<Spinner>(R.id.dialogSpinnerDsee)
        val spinnerUpsample = view.findViewById<Spinner>(R.id.dialogSpinnerUpsample)

        imageArtwork = view.findViewById(R.id.dialogImageArtwork)
        textTrackTitle = view.findViewById(R.id.dialogTextTrackTitle)
        textTrackArtist = view.findViewById(R.id.dialogTextTrackArtist)
        textCurrentTime = view.findViewById(R.id.dialogTextCurrentTime)
        textTotalTime = view.findViewById(R.id.dialogTextTotalTime)

        val appPrefs = AppPreferences.get()

        if (!isDarkTheme) {
            applyLightModeStyle(
                view, btnClose, switchDirectSource, switchEqEnable, switchSpectrumEnable,
                switchCascadeFir, switchVolLock, btnEqEdit, btnEqFlat, btnEqPlus, btnEqMinus,
                textEqBandFreq, textEqGainValue, spinnerBitDepth, spinnerDither, spinnerDcPhase,
                spinnerDsee, spinnerUpsample, spinnerPerfMode!!, btnPrev, btnNext, btnPlayPause!!
            )
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

        walkmanEqView?.isLightMode = !isDarkTheme
        walkmanEqView?.isSpectrumEnabled = appPrefs.isSpectrumEnabled
        switchSpectrumEnable.isChecked = appPrefs.isSpectrumEnabled
        switchSpectrumEnable.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.isSpectrumEnabled = isChecked
            walkmanEqView?.isSpectrumEnabled = isChecked
        }

        // 各セクションの活性・グレーアウト更新
        fun updateDspSectionsState(isDirect: Boolean, factor: Int) {
            val dspAlpha = if (isDirect) 0.3f else 1.0f
            val dspEnabled = !isDirect

            layoutSectionEq.alpha = dspAlpha
            layoutSectionDither.alpha = dspAlpha
            layoutSectionDcPhase.alpha = dspAlpha
            layoutSectionUpsample.alpha = dspAlpha

            spinnerDither.isEnabled = dspEnabled
            spinnerDcPhase.isEnabled = dspEnabled
            spinnerUpsample.isEnabled = dspEnabled

            switchEqEnable.isEnabled = dspEnabled
            switchSpectrumEnable.isEnabled = dspEnabled
            btnEqEdit.isEnabled = dspEnabled
            btnEqFlat.isEnabled = dspEnabled
            btnEqPlus.isEnabled = dspEnabled
            btnEqMinus.isEnabled = dspEnabled
            walkmanEqView?.isEnabled = dspEnabled

            val isDseeActive = dspEnabled && factor >= 2
            layoutSectionDsee.alpha = if (isDseeActive) 1.0f else 0.3f
            spinnerDsee.isEnabled = isDseeActive

            val isUpsampleActive = dspEnabled && factor >= 2
            layoutSectionCascadeFir.alpha = if (isUpsampleActive) 1.0f else 0.3f
            switchCascadeFir.isEnabled = isUpsampleActive

            updatePerfModeState(isDseeActive)
        }

        // DIRECT SOURCE
        switchDirectSource.isChecked = appPrefs.isDirectSource
        updateDspSectionsState(appPrefs.isDirectSource, if (appPrefs.isDirectSource) 1 else appPrefs.selectedUpsampleFactor)
        switchDirectSource.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.isDirectSource = isChecked
            NativeAudioEngine.nativeSetDirectSource(isChecked)
            val effectiveFactor = if (isChecked) 1 else appPrefs.selectedUpsampleFactor
            updateDspSectionsState(isChecked, effectiveFactor)
            onDirectSourceChanged(isChecked)
        }

        // CASCADE FIR
        switchCascadeFir.isChecked = appPrefs.isCascadeFir
        switchCascadeFir.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.isCascadeFir = isChecked
            NativeAudioEngine.nativeSetCascadeFir(isChecked)
        }

        // 0dB VOLUME LOCK
        val isUsb = isUsbDevice(activeOutputDevice)
        switchVolLock.isEnabled = isUsb
        switchVolLock.alpha = if (isUsb) 1.0f else 0.35f
        switchVolLock.isChecked = isVolumeLocked
        switchVolLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUsbDevice(activeOutputDevice)) {
                switchVolLock.isChecked = false
                return@setOnCheckedChangeListener
            }
            onVolumeLockChanged(isChecked)
        }

        // EQ 設定
        fun setEditMode(enabled: Boolean) {
            if (appPrefs.isDirectSource) return
            walkmanEqView?.isEditMode = enabled
            if (enabled) {
                btnEqEdit.text = "完了"
                layoutEqAdjustControls.visibility = View.VISIBLE
                if (!switchEqEnable.isChecked) switchEqEnable.isChecked = true
            } else {
                btnEqEdit.text = "調整"
                layoutEqAdjustControls.visibility = View.GONE
            }
        }

        fun updateEqHeader(bandIdx: Int, gain: Float) {
            textEqBandFreq.text = "${walkmanEqView?.bandLabels?.getOrNull(bandIdx) ?: "1K"} Hz"
            textEqGainValue.text = String.format(java.util.Locale.US, "%+.1f dB", gain)
        }

        for (i in 0..9) walkmanEqView?.gains?.set(i, appPrefs.getEqGain(i))
        walkmanEqView?.isDirectBypass = !appPrefs.isEqEnabled
        switchEqEnable.isChecked = appPrefs.isEqEnabled
        setEditMode(false)
        updateEqHeader(walkmanEqView?.selectedBandIndex ?: 7, walkmanEqView?.gains?.getOrNull(walkmanEqView?.selectedBandIndex ?: 7) ?: 0f)

        walkmanEqView?.onBandSelectedListener = { bandIdx, gain -> updateEqHeader(bandIdx, gain) }
        walkmanEqView?.onGainChangedListener = { bandIdx, gain, allGains ->
            updateEqHeader(bandIdx, gain)
            NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, allGains)
            appPrefs.setAllEqGains(allGains)
        }

        btnEqEdit.setOnClickListener { setEditMode(!(walkmanEqView?.isEditMode ?: false)) }
        switchEqEnable.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.isEqEnabled = isChecked
            walkmanEqView?.isDirectBypass = !isChecked
            NativeAudioEngine.nativeSetEqualizer(isChecked, walkmanEqView?.gains ?: FloatArray(10))
            if (!isChecked) setEditMode(false)
        }

        btnEqPlus.setOnClickListener { walkmanEqView?.stepGain(+0.5f) }
        btnEqMinus.setOnClickListener { walkmanEqView?.stepGain(-0.5f) }
        btnEqFlat.setOnClickListener { walkmanEqView?.resetAllFlat() }

        // スピナー類
        val spinnerLayout = if (isDarkTheme) R.layout.item_spinner_dap else R.layout.item_spinner_dap_light

        // Bit Depth
        val bitAdapter = ArrayAdapter(activity, spinnerLayout, bitOptions).apply { setDropDownViewResource(spinnerLayout) }
        spinnerBitDepth.adapter = bitAdapter
        spinnerBitDepth.setSelection(bitModeValues.indexOf(appPrefs.selectedBitMode).coerceAtLeast(0))
        spinnerBitDepth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedMode = bitModeValues[position]
                if (selectedMode != appPrefs.selectedBitMode) {
                    appPrefs.selectedBitMode = selectedMode
                    onBitModeChanged(selectedMode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Dither
        val ditherAdapter = ArrayAdapter(activity, spinnerLayout, ditherOptions).apply { setDropDownViewResource(spinnerLayout) }
        spinnerDither.adapter = ditherAdapter
        spinnerDither.setSelection(ditherModeValues.indexOf(appPrefs.selectedDitherMode).coerceAtLeast(0))
        spinnerDither.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedMode = ditherModeValues[position]
                if (selectedMode != appPrefs.selectedDitherMode) {
                    appPrefs.selectedDitherMode = selectedMode
                    NativeAudioEngine.nativeSetDitherMode(selectedMode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // DC Phase
        val dcPhaseAdapter = ArrayAdapter(activity, spinnerLayout, dcPhaseOptions).apply { setDropDownViewResource(spinnerLayout) }
        spinnerDcPhase.adapter = dcPhaseAdapter
        spinnerDcPhase.setSelection(dcPhaseTypeValues.indexOf(appPrefs.selectedDcPhaseType).coerceAtLeast(0))
        spinnerDcPhase.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedType = dcPhaseTypeValues[position]
                if (selectedType != appPrefs.selectedDcPhaseType) {
                    appPrefs.selectedDcPhaseType = selectedType
                    NativeAudioEngine.nativeSetDcPhaseType(selectedType)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Performance Mode
        val perfAdapter = ArrayAdapter(activity, spinnerLayout, perfModeOptions).apply { setDropDownViewResource(spinnerLayout) }
        spinnerPerfMode?.adapter = perfAdapter
        spinnerPerfMode?.setSelection(appPrefs.selectedPerfMode.coerceIn(0, 2))
        spinnerPerfMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (appPrefs.selectedPerfMode != position) {
                    appPrefs.selectedPerfMode = position
                    NativeAudioEngine.nativeSetPerformanceMode(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Upsample
        val upsampleAdapter = ArrayAdapter(activity, spinnerLayout, upsampleOptions).apply { setDropDownViewResource(spinnerLayout) }
        spinnerUpsample.adapter = upsampleAdapter
        spinnerUpsample.setSelection(upsampleFactorValues.indexOf(appPrefs.selectedUpsampleFactor).coerceAtLeast(0))
        spinnerUpsample.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedFactor = upsampleFactorValues[position]
                if (selectedFactor != appPrefs.selectedUpsampleFactor) {
                    appPrefs.selectedUpsampleFactor = selectedFactor
                    onUpsampleFactorChanged(selectedFactor)
                    updateDspSectionsState(appPrefs.isDirectSource, if (appPrefs.isDirectSource) 1 else selectedFactor)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ミニプレイヤー
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
            walkmanEqView = null
            textTrackTitle = null
            textTrackArtist = null
            textCurrentTime = null
            textTotalTime = null
            imageArtwork = null
            seekBar = null
            btnPlayPause = null
            layoutSectionPerfMode = null
            spinnerPerfMode = null
            FreqPresetManager.clearFrontDialogRefs()
            onDismiss()
        }

        bottomSheetDialog.show()
    }

    fun updatePerfModeState(isDseeActive: Boolean) {
        val isPerfActive = isDseeActive && (FreqPresetManager.currentPresetIndex != 0)
        val perfAlpha = if (isPerfActive) 1.0f else 0.35f
        layoutSectionPerfMode?.alpha = perfAlpha
        spinnerPerfMode?.isEnabled = isPerfActive
        spinnerPerfMode?.alpha = perfAlpha
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

    override fun setSpectrumLevels(spectrumBands: FloatArray) {
        walkmanEqView?.setSpectrumLevels(spectrumBands)
    }

    private fun isUsbDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
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
        switchDirectSource: SwitchCompat,
        switchEqEnable: SwitchCompat,
        switchSpectrumEnable: SwitchCompat,
        switchCascadeFir: SwitchCompat,
        switchVolLock: SwitchCompat,
        btnEqEdit: Button,
        btnEqFlat: Button,
        btnEqPlus: ImageButton,
        btnEqMinus: ImageButton,
        textEqBandFreq: TextView,
        textEqGainValue: TextView,
        spinnerBitDepth: Spinner,
        spinnerDither: Spinner,
        spinnerDcPhase: Spinner,
        spinnerDsee: Spinner,
        spinnerUpsample: Spinner,
        spinnerPerfMode: Spinner,
        btnPrev: ImageButton,
        btnNext: ImageButton,
        btnPlayPause: ImageButton
    ) {
        view.setBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        view.findViewById<View>(R.id.layoutSectionDirectSource)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
        view.findViewById<View>(R.id.layoutSectionEq)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
        view.findViewById<TextView>(R.id.textEqTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textEqSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textEqSwLabel)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textBitDepthTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textBitDepthSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textDitherTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textDitherSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textDcPhaseTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textDcPhaseSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textPerfModeTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textPerfModeSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<View>(R.id.dividerDspPerf)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
        spinnerPerfMode.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerPerfMode.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        view.findViewById<TextView>(R.id.textDseeTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textDseeSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textUpsampleTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textUpsampleSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textCascadeFirTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textCascadeFirSub)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.textVolLockTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.textVolLockSub)?.setTextColor(Color.parseColor("#636366"))

        view.findViewById<View>(R.id.dividerDsp1)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
        view.findViewById<View>(R.id.dividerDsp2)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
        view.findViewById<View>(R.id.dividerDsp3)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
        val dsp4Id = activity.resources.getIdentifier("dividerDsp4", "id", activity.packageName)
        if (dsp4Id != 0) view.findViewById<View>(dsp4Id)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
        view.findViewById<View>(R.id.dividerDsp5)?.setBackgroundColor(Color.parseColor("#E0E0E5"))

        val swTrackLight = ContextCompat.getDrawable(activity, R.drawable.switch_track_walkman_outline_light)
        val swThumbLight = ContextCompat.getDrawable(activity, R.drawable.switch_thumb_light)
        listOf(switchDirectSource, switchEqEnable, switchSpectrumEnable, switchCascadeFir, switchVolLock).forEach { sw ->
            sw.trackDrawable = swTrackLight
            sw.thumbDrawable = swThumbLight
        }

        btnClose.setBackgroundResource(R.drawable.bg_btn_icon_light)
        btnClose.setColorFilter(Color.parseColor("#1C1C1E"))

        btnEqEdit.setBackgroundResource(R.drawable.bg_btn_dap_outline_light)
        btnEqFlat.setBackgroundResource(R.drawable.bg_btn_dap_outline_light)
        btnEqEdit.setTextColor(Color.parseColor("#1C1C1E"))
        btnEqFlat.setTextColor(Color.parseColor("#636366"))
        btnEqPlus.setBackgroundResource(R.drawable.bg_btn_circle_light)
        btnEqMinus.setBackgroundResource(R.drawable.bg_btn_circle_light)
        btnEqPlus.setColorFilter(Color.parseColor("#1C1C1E"))
        btnEqMinus.setColorFilter(Color.parseColor("#1C1C1E"))

        textEqBandFreq.setTextColor(Color.parseColor("#1C1C1E"))
        textEqGainValue.setTextColor(Color.parseColor("#1C1C1E"))
        spinnerBitDepth.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerDither.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerDcPhase.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerDsee.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerUpsample.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerBitDepth.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        spinnerDither.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        spinnerDcPhase.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        spinnerDsee.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        spinnerUpsample.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)

        seekBar?.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#D1D1D6"))
        seekBar?.progressTintList = ColorStateList.valueOf(Color.parseColor("#D49B28"))
        seekBar?.thumbTintList = ColorStateList.valueOf(Color.parseColor("#1C1C1E"))

        btnPrev.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
        btnNext.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
        btnPlayPause.setBackgroundResource(R.drawable.bg_btn_walkman_circle_large_light)
        btnPrev.setColorFilter(Color.parseColor("#1C1C1E"))
        btnNext.setColorFilter(Color.parseColor("#1C1C1E"))
        btnPlayPause.setColorFilter(Color.parseColor("#1C1C1E"))

        view.findViewById<View>(R.id.dspDialogPlayerControl)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
        view.findViewById<View>(R.id.dspPlayerDivider)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
        view.findViewById<TextView>(R.id.dialogTextTrackTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
        view.findViewById<TextView>(R.id.dialogTextTrackArtist)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.dialogTextCurrentTime)?.setTextColor(Color.parseColor("#636366"))
        view.findViewById<TextView>(R.id.dialogTextTotalTime)?.setTextColor(Color.parseColor("#636366"))
    }
}