package com.example.perfectbitrate

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    // =========================================================================
    // ★ [完全リンク] 表・裏スピナー常時同期 ＆ パラメータ自動変化マネージャー
    // =========================================================================
    data class FreqPresetDef(
        val id: Int,                 // 0: OFF, 1: Auto AI, 2: 男性ボーカル, 3: 女性ボーカル, 4: パーカッション, 5: ストリングス
        val name: String,
        val firType: Int,           // 0: Linear Sharp, 1: Linear Slow, 2: Min Sharp, 3: Min Slow
        val transientMode: Int,     // 0: OFF, 1: Natural, 2: Punch, 3: Acoustic
        val lpcAlgo: Int,           // 0: DSEE AI, 1: K2 LPC, 2: Adaptive Exciter
        val gain: Float,
        val extractFreq: Float,
        val useQmf: Boolean,
        val useGroupDelay: Boolean,
        val useLattice: Boolean,
        val useLrDither: Boolean,
        val useMsSpatial: Boolean,
        val useDynamicSbr: Boolean
    )

    private val PRESET_NAMES = listOf("OFF", "Auto AI", "男性ボーカル", "女性ボーカル", "パーカッション", "ストリングス")

    private val PRESET_DEFS = listOf(
        // 0: OFF (全DSP停止・全スイッチ完全消灯)
        FreqPresetDef(0, "OFF", 0, 0, 0, 0.0f, 10000f, false, false, false, false, false, false),
        // 1: Auto AI (全DSPフル点灯・全スイッチON)
        FreqPresetDef(1, "Auto AI", 2, 1, 0, 0.18f, 10500f, true, true, true, true, true, true),
        // 2: 男性ボーカル (太い基音と明瞭感。QMF・群遅延・残響超解像ON、格子型とSBRはOFF)
        FreqPresetDef(2, "男性ボーカル", 2, 1, 0, 0.12f, 10000f, true, true, false, true, true, false),
        // 3: 女性ボーカル (サ行抑制・定位・残響のみON、格子型とSBRはOFFで声質維持)
        FreqPresetDef(3, "女性ボーカル", 2, 1, 0, 0.14f, 11500f, true, true, false, true, true, false),
        // 4: パーカッション (パンチ最大化。アタックを削るQMF・広がるM/S・濁るSBRは全てOFF)
        FreqPresetDef(4, "パーカッション", 2, 2, 2, 0.22f, 9500f, false, true, true, true, false, false),
        // 5: ストリングス (弦・打弦、自然倍音K2、撥弦過渡Lattice。空間拡散OFFで楽器の芯を維持)
        FreqPresetDef(5, "ストリングス", 2, 3, 1, 0.18f, 10500f, true, true, true, true, false, true)
    )

    private var currentPresetIndex = 0 // 0: OFF, 1: Auto AI...
    private var isPresetSyncing = false
    private var frontSpinnerWeakRef: java.lang.ref.WeakReference<android.widget.Spinner>? = null
    private var backSpinnerWeakRef: java.lang.ref.WeakReference<android.widget.Spinner>? = null
    private var backDialogViewWeakRef: java.lang.ref.WeakReference<android.view.View>? = null

    /**
     * Native C++ DSP エンジンへ即座に反映
     */
    private fun applyPresetToNativeEngine(p: FreqPresetDef) {
        if (p.id == 0) {
            NativeAudioEngine.nativeSetFreqMode(0)
            NativeAudioEngine.nativeSetTransientMode(0)
            NativeAudioEngine.nativeSetMsSpatial(false)
            NativeAudioEngine.nativeSetDynamicSbr(false)
            NativeAudioEngine.nativeSetTransientCustomParams(false, false)
            NativeAudioEngine.nativeSetFreqCustomParams(0.0f, 10000.0f)
        } else {
            NativeAudioEngine.nativeSetFirFilterType(p.firType)
            NativeAudioEngine.nativeSetTransientMode(p.transientMode)
            NativeAudioEngine.nativeSetTransientCustomParams(p.useGroupDelay, p.useLattice)
            NativeAudioEngine.nativeSetFreqMode(p.lpcAlgo)
            NativeAudioEngine.nativeSetFreqCustomParams(p.gain, p.extractFreq)
            NativeAudioEngine.nativeSetLrIndependentDither(p.useLrDither)
            NativeAudioEngine.nativeSetMsSpatial(p.useMsSpatial)
            NativeAudioEngine.nativeSetDynamicSbr(p.useDynamicSbr)
        }
    }

    /**
     * 裏設定ダイアログの全UI（最上部スピナー・下の各スピナー・スイッチ6種）を強制更新
     */
    private fun updateBackTunerUI(dialogView: android.view.View?, p: FreqPresetDef) {
        if (dialogView == null) return
        dialogView.post {
            try {
                // 1. 最上部 TARGET PRESET スピナー
                val targetSp = dialogView.findViewById<android.view.View>(R.id.spinnerTargetPreset) as? android.widget.Spinner
                if (targetSp != null && targetSp.selectedItemPosition != p.id) {
                    targetSp.setSelection(p.id)
                }

                // 2. 各スピナー (FIR, Transient, LPC)
                (dialogView.findViewById<android.view.View>(R.id.devSpinnerFir) as? android.widget.Spinner)?.setSelection(p.firType)
                (dialogView.findViewById<android.view.View>(R.id.devSpinnerTransient) as? android.widget.Spinner)?.setSelection(p.transientMode)
                (dialogView.findViewById<android.view.View>(R.id.devSpinnerLpcAlgo) as? android.widget.Spinner)?.setSelection(p.lpcAlgo)

                // Gain
                (dialogView.findViewById<android.view.View>(R.id.devSpinnerGain) as? android.widget.Spinner)?.let { sp ->
                    val ad = sp.adapter ?: return@let
                    var best = 0
                    var minD = Float.MAX_VALUE
                    for (i in 0 until ad.count) {
                        val num = Regex("""\d+(\.\d+)?""").find(ad.getItem(i).toString())?.value?.toFloatOrNull()
                        if (num != null && kotlin.math.abs(num - p.gain) < minD) {
                            minD = kotlin.math.abs(num - p.gain)
                            best = i
                        }
                    }
                    sp.setSelection(best)
                }

                // Freq
                (dialogView.findViewById<android.view.View>(R.id.devSpinnerExtractFreq) as? android.widget.Spinner)?.let { sp ->
                    val ad = sp.adapter ?: return@let
                    var best = 0
                    var minD = Float.MAX_VALUE
                    for (i in 0 until ad.count) {
                        val cleanStr = ad.getItem(i).toString().replace(",", "")
                        val num = Regex("""\d+""").find(cleanStr)?.value?.toFloatOrNull()
                        if (num != null && kotlin.math.abs(num - p.extractFreq) < minD) {
                            minD = kotlin.math.abs(num - p.extractFreq)
                            best = i
                        }
                    }
                    sp.setSelection(best)
                }

                // 3. ★ スイッチ6種 (OFFなら全てfalse完全消灯！各プリセットならメリハリ点灯！)
                (dialogView.findViewById<android.view.View>(R.id.devSwitchQmf) as? androidx.appcompat.widget.SwitchCompat)?.isChecked = p.useQmf
                (dialogView.findViewById<android.view.View>(R.id.devSwitchGroupDelay) as? androidx.appcompat.widget.SwitchCompat)?.isChecked = p.useGroupDelay
                (dialogView.findViewById<android.view.View>(R.id.devSwitchLattice) as? androidx.appcompat.widget.SwitchCompat)?.isChecked = p.useLattice
                (dialogView.findViewById<android.view.View>(R.id.devSwitchLrDither) as? androidx.appcompat.widget.SwitchCompat)?.isChecked = p.useLrDither
                (dialogView.findViewById<android.view.View>(R.id.devSwitchMsSpatial) as? androidx.appcompat.widget.SwitchCompat)?.isChecked = p.useMsSpatial
                (dialogView.findViewById<android.view.View>(R.id.devSwitchDynamicSbr) as? androidx.appcompat.widget.SwitchCompat)?.isChecked = p.useDynamicSbr
            
                  // ★ [完全修正] DSEE/FREQ 親行(LinearLayout)およびスピナーのグレーアウト完全連動
                  val isDseeActive = (p.id != 0)
                  val dseeAlpha = if (isDseeActive) 1.0f else 0.35f

                  val lpcView = dialogView.findViewById<android.view.View>(R.id.devSpinnerLpcAlgo)
                  val gainView = dialogView.findViewById<android.view.View>(R.id.devSpinnerGain)
                  val freqView = dialogView.findViewById<android.view.View>(R.id.devSpinnerExtractFreq)

                  (lpcView?.parent as? android.view.View)?.let {
                      it.isEnabled = isDseeActive
                      it.alpha = dseeAlpha
                  }
                  (gainView?.parent as? android.view.View)?.let {
                      it.isEnabled = isDseeActive
                      it.alpha = dseeAlpha
                  }
                  lpcView?.let {
                      it.isEnabled = isDseeActive
                      it.alpha = dseeAlpha
                  }
                  gainView?.let {
                      it.isEnabled = isDseeActive
                      it.alpha = dseeAlpha
                  }
                  freqView?.let {
                      it.isEnabled = isDseeActive
                      it.alpha = dseeAlpha
                  }
              } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 表または裏のスピナーが変更されたときの共通同期ハンドラ
     */
    private fun onUnifiedPresetSelected(position: Int) {
        if (isPresetSyncing) return
        isPresetSyncing = true
        try {
            val safePos = position.coerceIn(0, PRESET_DEFS.size - 1)
            currentPresetIndex = safePos
            val preset = PRESET_DEFS[safePos]

            // 1. Native DSP へ反映
            applyPresetToNativeEngine(preset)

            // 2. 表のスピナーの選択位置を同期
            frontSpinnerWeakRef?.get()?.let { sp ->
                if (sp.selectedItemPosition != safePos) {
                    sp.setSelection(safePos)
                }
            }

            // 3. 裏のスピナーの選択位置を同期
            backSpinnerWeakRef?.get()?.let { sp ->
                if (sp.selectedItemPosition != safePos) {
                    sp.setSelection(safePos)
                }
            }

            // 4. 裏ダイアログの全UI（下の各パラメータ＆スイッチ6種）を自動変化！
            updateBackTunerUI(backDialogViewWeakRef?.get(), preset)
        } finally {
            isPresetSyncing = false
        }
    }

    /**
     * 表設定ダイアログ (dialog_dsp_settings) をフック
     */
    private fun hookDspSettingsDialog(dialogView: android.view.View?) {
        if (dialogView == null) return
        dialogView.post {
            val frontSp = dialogView.findViewById<android.view.View>(R.id.dialogSpinnerDsee) as? android.widget.Spinner ?: return@post
            frontSpinnerWeakRef = java.lang.ref.WeakReference(frontSp)

            // 表スピナーのアダプターを完全共通化
            val ad = android.widget.ArrayAdapter(dialogView.context, R.layout.item_spinner_dap, PRESET_NAMES)
            ad.setDropDownViewResource(R.layout.item_spinner_dap)
            frontSp.adapter = ad
            frontSp.setSelection(currentPresetIndex)

            frontSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    onUnifiedPresetSelected(pos)
                }
                override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
            }
        }
    }

    /**
     * 裏設定ダイアログ (dialog_dev_presets) をフック
     */
    private fun hookDevPresetsDialog(dialogView: android.view.View?) {
        if (dialogView == null) return
        dialogView.post {
            val backSp = dialogView.findViewById<android.view.View>(R.id.spinnerTargetPreset) as? android.widget.Spinner ?: return@post
            backSpinnerWeakRef = java.lang.ref.WeakReference(backSp)
            backDialogViewWeakRef = java.lang.ref.WeakReference(dialogView)

            // ★ 裏スピナーにも正式に「OFF」を含む完全共通アダプターをセット！
            val ad = android.widget.ArrayAdapter(dialogView.context, R.layout.item_spinner_dap, PRESET_NAMES)
            ad.setDropDownViewResource(R.layout.item_spinner_dap)
            backSp.adapter = ad
            backSp.setSelection(currentPresetIndex)

            backSp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    onUnifiedPresetSelected(pos)
                }
                override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
            }

            // 開いた瞬間に、現在の設定値（OFFなら全消灯、Auto AIなら全点灯）で下の全パラメータを強制更新！
            updateBackTunerUI(dialogView, PRESET_DEFS[currentPresetIndex])
        }
    }


    private lateinit var mainRootLayout: View
    private lateinit var badgeDirect: TextView
    private lateinit var textDacName: TextView
    private lateinit var textRateBits: TextView
    private lateinit var textCodec: TextView
    private lateinit var textTransfer: TextView
    private lateinit var textPeak: TextView
    private lateinit var textBitDepth: TextView
    private var walkmanLevelMeter: WalkmanLevelMeterView? = null
    private var topInfoPanel: View? = null
    private var panelDividerLine: View? = null

    private lateinit var btnReload: ImageButton
    private lateinit var btnDspSettings: ImageButton
    private lateinit var btnUiSettings: ImageButton

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private var geckoRuntime: GeckoRuntime? = null
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences
    private var appWakeLock: PowerManager.WakeLock? = null

    private var playbackService: BitPerfectPlaybackService? = null
    private var isServiceBound = false
    private var activeWebExtensionPort: WebExtension.Port? = null

    private var baseSampleRate = 48000
    private var upsampleFactor = 1
    private var pcmPacketCount = 0L
    private var outputDeviceName = "内蔵スピーカー"
    private var activeOutputDevice: AudioDeviceInfo? = null
    private var currentCodec = "OPUS 160kbps (48k)"
    private var currentBtCodecName = ""

    private var currentTitle = "YouTube Music"
    private var currentArtist = ""
    private var currentDuration = 0L
    private var currentPosition = 0L
    private var currentArtworkBitmap: Bitmap? = null
    private val imageExecutor = Executors.newSingleThreadExecutor()

    private var activeDialogArtworkImage: ImageView? = null
    private var activeDialogSeekBar: SeekBar? = null
    private var activeDialogCurrentTime: TextView? = null
    private var activeDialogTotalTime: TextView? = null
    private var activeDialogPlayPauseBtn: ImageButton? = null
    private var activeDialogTrackTitle: TextView? = null
    private var activeDialogTrackArtist: TextView? = null
    private var activeDialogEqView: WalkmanEqView? = null
    private var isUserSeeking = false

    private var bluetoothA2dp: BluetoothA2dp? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var isDirectSource = false
    private var isCascadeFir = true
    private var isLrIndependentDither = true
    private var isSpectrumOn = true

    private var currentThemeMode = "dark"
    private val themeOptions = arrayOf("Dark (ダーク)", "Light (ライト)", "Auto (端末の設定に連動)")
    private val themeValues = arrayOf("dark", "light", "auto")

    private var currentBitMode = "16bit"
    private val bitOptions = arrayOf("16-bit (Std)", "24-bit (Hi-Res)", "32-bit (Int32)")
    private val bitModeValues = arrayOf("16bit", "24bit", "32bit")

    private val upsampleOptions = arrayOf("1x Direct (Bypass)", "2x Hi-Res (96k/88k)", "4x Ultra (192k/176k)", "8x Master (384k/352k)")
    private val upsampleFactorValues = arrayOf(1, 2, 4, 8)

    private var currentDitherMode = 1
    private val ditherOptions = arrayOf("TPDF (Studio Standard)", "High-Pass Shaped (Clear)", "Psychoacoustic (Walkman SBM)", "None (Direct Bypass)")
    private val ditherModeValues = arrayOf(1, 2, 3, 0)

    private var currentDcPhaseType = 2
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

    private var currentDseeMode = 1
    private val dseeOptions = arrayOf(
        "OFF",
        "Auto AI",
        "男性ボーカル",
        "女性ボーカル",
        "パーカッション",
        "ストリングス"
    )
    private val dseeModeValues = arrayOf(0, 1, 2, 3, 4, 5)

    data class PresetProfile(
        var firType: Int,
        var transientMode: Int,
        var lpcAlgo: Int,
        var gain: Float,
        var extractFreq: Float,
        var useQmf: Boolean = false,
        var useGroupDelay: Boolean = false,
        var useLattice: Boolean = false
    )

    private val presetProfiles = mutableMapOf<Int, PresetProfile>()

    private fun initDefaultProfiles() {
        presetProfiles[1] = PresetProfile(2, 3, 1, 0.16f, 10500.0f, useQmf = true, useGroupDelay = true, useLattice = true)
        presetProfiles[2] = PresetProfile(3, 1, 2, 0.11f, 9500.0f, useQmf = true, useGroupDelay = false, useLattice = false)
        presetProfiles[3] = PresetProfile(2, 1, 1, 0.14f, 11000.0f, useQmf = true, useGroupDelay = false, useLattice = true)
        presetProfiles[4] = PresetProfile(2, 2, 3, 0.18f, 11500.0f, useQmf = true, useGroupDelay = true, useLattice = true)
        presetProfiles[5] = PresetProfile(1, 3, 2, 0.13f, 10000.0f, useQmf = true, useGroupDelay = false, useLattice = false)

        for (id in 1..5) {
            val p = presetProfiles[id]!!
            p.firType = prefs.getInt("prof_${id}_fir", p.firType)
            p.transientMode = prefs.getInt("prof_${id}_trans", p.transientMode)
            p.lpcAlgo = prefs.getInt("prof_${id}_lpc", p.lpcAlgo)
            p.gain = prefs.getFloat("prof_${id}_gain", p.gain)
            p.extractFreq = prefs.getFloat("prof_${id}_freq", p.extractFreq)
            p.useQmf = prefs.getBoolean("prof_${id}_qmf", p.useQmf)
            p.useGroupDelay = prefs.getBoolean("prof_${id}_gd", p.useGroupDelay)
            p.useLattice = prefs.getBoolean("prof_${id}_lat", p.useLattice)
        }
    }

    private fun applyPresetToDsp(presetId: Int) {
        if (presetId == 0) {
            NativeAudioEngine.nativeSetDseeMode(0)
            NativeAudioEngine.nativeSetTransientMode(0)
            return
        }
        val p = presetProfiles[presetId] ?: presetProfiles[1]!!
        NativeAudioEngine.nativeSetFirFilterType(p.firType)
        NativeAudioEngine.nativeSetTransientMode(p.transientMode)
        NativeAudioEngine.nativeSetTransientCustomParams(p.useGroupDelay, p.useLattice)
        NativeAudioEngine.nativeSetDseeMode(presetId)
        NativeAudioEngine.nativeSetDseeCustomParams(p.lpcAlgo, p.gain, p.extractFreq, p.useQmf)
    }

    private var isEqEnabled = false
    private val eqGains = FloatArray(10) { 0.0f }

    private var peakDbL = -60f
    private var peakDbR = -60f
    private var bitActivityMask = 0
    private var lastBitResetTime = 0L
    private var lastPcmTime = 0L
    private var isAdBlockOn = true
    private var isVolLockOn = false
    private var isPlayingState = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastPcmTime > 400L || !isPlayingState) {
                peakDbL = -60f
                peakDbR = -60f
                bitActivityMask = 0
                walkmanLevelMeter?.setLevels(-60f, -60f)
            }
            updateStatus()
            updateDialogPlayerUi()
            uiHandler.postDelayed(this, 30)
        }
    }

    private val deviceDetectRunnable = Runnable {
        detectAudioOutputDevice()
        playbackService?.setOutputDevice(activeOutputDevice)
        fetchBluetoothCodec()
        try {
            activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "resume_audio") })
        } catch (e: Exception) {}
    }

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                fetchBluetoothCodec()
                updateStatus()
            }
        }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            fetchBluetoothCodec()
            detectAudioOutputDevice()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BitPerfectPlaybackService.LocalBinder
            playbackService = binder.getService()
            isServiceBound = true
            detectAudioOutputDevice()
            playbackService?.isVolumeLocked = isVolLockOn
            playbackService?.currentBitMode = currentBitMode
            playbackService?.upsampleFactor = if (isDirectSource) 1 else upsampleFactor
            playbackService?.setOutputDevice(activeOutputDevice)

            NativeAudioEngine.nativeSetDirectSource(isDirectSource)
            NativeAudioEngine.nativeSetCascadeFir(isCascadeFir)
            NativeAudioEngine.nativeSetDitherMode(currentDitherMode)
            NativeAudioEngine.nativeSetLrIndependentDither(isLrIndependentDither)
            NativeAudioEngine.nativeSetDcPhaseType(currentDcPhaseType)
            applyPresetToDsp(currentDseeMode)
            NativeAudioEngine.nativeSetEqualizer(isEqEnabled, eqGains)

            playbackService?.onActualBitModeChanged = { actualMode ->
                runOnUiThread {
                    if (actualMode != currentBitMode) {
                        currentBitMode = actualMode
                        prefs.edit { putString("selected_bit_mode", actualMode) }
                        updateStatus()
                    }
                }
            }

            playbackService?.onPeakListener = { dbL, dbR, mask, spectrumBands ->
                lastPcmTime = System.currentTimeMillis()
                peakDbL = dbL
                peakDbR = dbR
                bitActivityMask = bitActivityMask or mask
                walkmanLevelMeter?.setLevels(dbL, dbR)
                activeDialogEqView?.setSpectrumLevels(spectrumBands)
            }

            playbackService?.onDeviceDisconnectedListener = {
                runOnUiThread {
                    isVolLockOn = false
                    prefs.edit { putBoolean("vol_lock_enabled", false) }
                    detectAudioOutputDevice()
                }
            }

            playbackService?.onCommandListener = { cmd ->
                try {
                    val jsonCmd = JSONObject().apply { put("command", cmd) }
                    activeWebExtensionPort?.postMessage(jsonCmd)
                } catch (e: Exception) {}
            }

            playbackService?.onSeekListener = { pos ->
                try {
                    val jsonCmd = JSONObject().apply {
                        put("command", "seek")
                        put("position", pos)
                    }
                    activeWebExtensionPort?.postMessage(jsonCmd)
                } catch (e: Exception) {}
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        appWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PerfectBitRate::MainActivityWakeLock")
        appWakeLock?.acquire()

        mainRootLayout = findViewById(R.id.mainRootLayout)
        topInfoPanel = findViewById(R.id.topInfoPanel)
        panelDividerLine = findViewById(R.id.panelDividerLine)
        badgeDirect = findViewById(R.id.badgeDirect)
        textDacName = findViewById(R.id.textDacName)
        textRateBits = findViewById(R.id.textRateBits)
        textCodec = findViewById(R.id.textCodec)
        textTransfer = findViewById(R.id.textTransfer)
        textPeak = findViewById(R.id.textPeak)
        textBitDepth = findViewById(R.id.textBitDepth)
        walkmanLevelMeter = findViewById(R.id.walkmanLevelMeter)

        btnReload = findViewById(R.id.btnReload)
        btnDspSettings = findViewById(R.id.btnDspSettings)
        btnUiSettings = findViewById(R.id.btnUiSettings)
        geckoView = findViewById(R.id.geckoview)

        prefs = getSharedPreferences("bp_settings", Context.MODE_PRIVATE)
        isDirectSource = prefs.getBoolean("direct_source_enabled", false)
        isCascadeFir = prefs.getBoolean("cascade_fir_enabled", true)
        isLrIndependentDither = prefs.getBoolean("lr_dither_enabled", true)
        isSpectrumOn = prefs.getBoolean("spectrum_enabled", true)
        isAdBlockOn = prefs.getBoolean("ad_block_enabled", true)
        currentThemeMode = prefs.getString("ui_theme_mode", "dark") ?: "dark"
        isVolLockOn = false
        currentBitMode = prefs.getString("selected_bit_mode", "16bit") ?: "16bit"
        upsampleFactor = prefs.getInt("selected_upsample_factor", 1)
        currentDitherMode = prefs.getInt("selected_dither_mode", 1)
        currentDcPhaseType = prefs.getInt("selected_dc_phase_type", 2)
        currentDseeMode = prefs.getInt("selected_dsee_mode", 1)

        initDefaultProfiles()

        isEqEnabled = prefs.getBoolean("eq_enabled", false)
        for (i in 0..9) {
            eqGains[i] = prefs.getFloat("eq_gain_$i", 0.0f)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        checkAndRequestPermissions()
        setupBluetoothTracker()

        val serviceIntent = Intent(this, BitPerfectPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        registerAudioDeviceCallback()
        setupGeckoView()

        applyThemeUi(currentThemeMode)

        btnReload.setOnClickListener {
            reloadDirectStream()
        }

        btnDspSettings.setOnClickListener {
            showDspSettingsDialog()
        }

        btnDspSettings.setOnLongClickListener {
            Toast.makeText(this, "DEVELOPER PRESET TUNER", Toast.LENGTH_SHORT).show()
            showDevPresetsDialog()
            true
        }

        btnUiSettings.setOnClickListener {
            showUiSettingsDialog()
        }

        uiHandler.post(uiUpdateRunnable)
    }

    private fun isDarkThemeActive(): Boolean {
        return when (currentThemeMode) {
            "light" -> false
            "auto" -> {
                val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }
            else -> true
        }
    }

    // ★ YouTube Music Web へ White モード CSS インジェクション指示を送信
    private fun sendWebThemeSetting(theme: String) {
        try {
            activeWebExtensionPort?.postMessage(JSONObject().apply {
                put("command", "setWebTheme")
                put("theme", theme)
            })
        } catch (e: Exception) {}
    }

    private fun applyThemeUi(themeMode: String) {
        currentThemeMode = themeMode
        val isDark = isDarkThemeActive()

        walkmanLevelMeter?.isLightMode = !isDark

        // ★ ブラウザ側の YouTube Music 画面もライト／ダークを完全同期
        sendWebThemeSetting(if (isDark) "dark" else "light")

        if (isDark) {
            mainRootLayout.setBackgroundColor(Color.parseColor("#000000"))
            topInfoPanel?.setBackgroundColor(Color.parseColor("#0D0D0D"))
            panelDividerLine?.setBackgroundColor(Color.parseColor("#1C1C1C"))
            textDacName.setTextColor(Color.parseColor("#F0F0F0"))
            textRateBits.setTextColor(Color.WHITE)
            textCodec.setTextColor(Color.parseColor("#A0A0A0"))
            textTransfer.setTextColor(Color.parseColor("#666666"))
            textPeak.setTextColor(Color.parseColor("#B0B0B0"))

            btnReload.setBackgroundResource(R.drawable.bg_btn_icon)
            btnDspSettings.setBackgroundResource(R.drawable.bg_btn_icon)
            btnUiSettings.setBackgroundResource(R.drawable.bg_btn_icon)

            btnReload.setColorFilter(Color.parseColor("#CCCCCC"))
            btnDspSettings.setColorFilter(Color.parseColor("#E5A93C"))
            btnUiSettings.setColorFilter(Color.parseColor("#E5A93C"))
        } else {
            mainRootLayout.setBackgroundColor(Color.parseColor("#FFFFFF"))
            topInfoPanel?.setBackgroundColor(Color.parseColor("#F5F5F7"))
            panelDividerLine?.setBackgroundColor(Color.parseColor("#E0E0E5"))
            textDacName.setTextColor(Color.parseColor("#1C1C1E"))
            textRateBits.setTextColor(Color.parseColor("#1C1C1E"))
            textCodec.setTextColor(Color.parseColor("#636366"))
            textTransfer.setTextColor(Color.parseColor("#636366"))
            textPeak.setTextColor(Color.parseColor("#48484A"))

            btnReload.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnDspSettings.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnUiSettings.setBackgroundResource(R.drawable.bg_btn_icon_light)

            btnReload.setColorFilter(Color.parseColor("#1C1C1E"))
            btnDspSettings.setColorFilter(Color.parseColor("#D49B28"))
            btnUiSettings.setColorFilter(Color.parseColor("#D49B28"))
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
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

    private fun updateDialogPlayerUi() {
        activeDialogTrackTitle?.text = currentTitle
        activeDialogTrackArtist?.text = currentArtist
        
        activeDialogPlayPauseBtn?.setImageResource(
            if (isPlayingState) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (currentArtworkBitmap != null) {
            activeDialogArtworkImage?.setImageBitmap(currentArtworkBitmap)
        }

        if (!isUserSeeking && currentDuration > 0) {
            val progress = ((currentPosition.toDouble() / currentDuration.toDouble()) * 1000).toInt().coerceIn(0, 1000)
            activeDialogSeekBar?.progress = progress
            activeDialogCurrentTime?.text = formatTime(currentPosition)
            activeDialogTotalTime?.text = formatTime(currentDuration)
        } else if (currentDuration <= 0L) {
            activeDialogSeekBar?.progress = 0
            activeDialogCurrentTime?.text = "0:00"
            activeDialogTotalTime?.text = "0:00"
        }
    }

    // =========================================================================
    // 1. DSP 音質設定専用ダイアログ (0dB SW 移植版)
    // =========================================================================
    private fun showDspSettingsDialog() {
        val dialog = BottomSheetDialog(this, R.style.CustomBottomSheetDialogTheme)
        dialog.window?.setDimAmount(0f)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val view = layoutInflater.inflate(R.layout.dialog_dsp_settings, null)
        FreqPresetManager.hookDspSettingsDialog(view)
        hookDspSettingsDialog(view)
        dialog.setContentView(view)

        val isDark = isDarkThemeActive()

        val btnClose = view.findViewById<ImageButton>(R.id.btnDspDialogClose)
        val switchDirectSource = view.findViewById<SwitchCompat>(R.id.dialogSwitchDirectSource)
        val switchEqEnable = view.findViewById<SwitchCompat>(R.id.switchEqEnable)
        val switchSpectrumEnable = view.findViewById<SwitchCompat>(R.id.switchSpectrumEnable)
        val switchCascadeFir = view.findViewById<SwitchCompat>(R.id.dialogSwitchCascadeFir)
        val switchVolLock = view.findViewById<SwitchCompat>(R.id.dialogSwitchVolLock)

        val seekBar = view.findViewById<SeekBar>(R.id.dialogSeekBar)
        val btnPlayPause = view.findViewById<ImageButton>(R.id.dialogBtnPlayPause)
        val btnPrev = view.findViewById<ImageButton>(R.id.dialogBtnPrevTrack)
        val btnNext = view.findViewById<ImageButton>(R.id.dialogBtnNextTrack)

        val btnEqPlus = view.findViewById<ImageButton>(R.id.btnEqPlus)
        val btnEqMinus = view.findViewById<ImageButton>(R.id.btnEqMinus)
        val btnEqFlat = view.findViewById<Button>(R.id.btnEqFlat)
        val btnEqEdit = view.findViewById<Button>(R.id.btnEqEdit)

        val walkmanEqView = view.findViewById<WalkmanEqView>(R.id.walkmanEqView)
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
        val spinnerDsee = view.findViewById<Spinner>(R.id.dialogSpinnerDsee)
        val spinnerUpsample = view.findViewById<Spinner>(R.id.dialogSpinnerUpsample)

        val imageArtwork = view.findViewById<ImageView>(R.id.dialogImageArtwork)
        val textTrackTitle = view.findViewById<TextView>(R.id.dialogTextTrackTitle)
        val textTrackArtist = view.findViewById<TextView>(R.id.dialogTextTrackArtist)
        val textCurrentTime = view.findViewById<TextView>(R.id.dialogTextCurrentTime)
        val textTotalTime = view.findViewById<TextView>(R.id.dialogTextTotalTime)

        if (!isDark) {
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
            view.findViewById<View>(R.id.dividerDsp4)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
            view.findViewById<View>(R.id.dividerDsp5)?.setBackgroundColor(Color.parseColor("#E0E0E5"))

            val swTrackLight = ContextCompat.getDrawable(this, R.drawable.switch_track_walkman_outline_light)
            val swThumbLight = ContextCompat.getDrawable(this, R.drawable.switch_thumb_light)
            listOf(switchDirectSource, switchEqEnable, switchSpectrumEnable, switchCascadeFir, switchVolLock).forEach { sw ->
                sw?.trackDrawable = swTrackLight
                sw?.thumbDrawable = swThumbLight
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

            seekBar.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#D1D1D6"))
            seekBar.progressTintList = ColorStateList.valueOf(Color.parseColor("#D49B28"))
            seekBar.thumbTintList = ColorStateList.valueOf(Color.parseColor("#1C1C1E"))

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

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                val panelH = topInfoPanel?.height ?: 0
                val screenH = resources.displayMetrics.heightPixels
                val targetH = screenH - panelH
                if (targetH > 0) {
                    val lp = bottomSheet.layoutParams
                    lp.height = targetH
                    bottomSheet.layoutParams = lp
                    behavior.peekHeight = targetH
                }
            }
        }

        activeDialogArtworkImage = imageArtwork
        activeDialogTrackTitle = textTrackTitle
        activeDialogTrackArtist = textTrackArtist
        activeDialogCurrentTime = textCurrentTime
        activeDialogTotalTime = textTotalTime
        activeDialogSeekBar = seekBar
        activeDialogPlayPauseBtn = btnPlayPause
        activeDialogEqView = walkmanEqView

        walkmanEqView.isLightMode = !isDark
        walkmanEqView.isSpectrumEnabled = isSpectrumOn
        switchSpectrumEnable.isChecked = isSpectrumOn
        switchSpectrumEnable.setOnCheckedChangeListener { _, isChecked ->
            isSpectrumOn = isChecked
            prefs.edit { putBoolean("spectrum_enabled", isChecked) }
            walkmanEqView.isSpectrumEnabled = isChecked
        }

        updateDialogPlayerUi()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentDuration > 0) {
                    val seekMs = (progress.toLong() * currentDuration) / 1000L
                    textCurrentTime.text = formatTime(seekMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                if (currentDuration > 0 && sb != null) {
                    val seekMs = (sb.progress.toLong() * currentDuration) / 1000L
                    try {
                        activeWebExtensionPort?.postMessage(JSONObject().apply {
                            put("command", "seek")
                            put("position", seekMs)
                        })
                    } catch (e: Exception) {}
                }
            }
        })

        btnPlayPause.setOnClickListener {
            val cmd = if (isPlayingState) "pause" else "play"
            try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", cmd) }) } catch (e: Exception) {}
        }
        btnPrev.setOnClickListener {
            try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "prev") }) } catch (e: Exception) {}
        }
        btnNext.setOnClickListener {
            try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "next") }) } catch (e: Exception) {}
        }

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
            walkmanEqView.isEnabled = dspEnabled

            val isDseeActive = dspEnabled && factor >= 2
            layoutSectionDsee.alpha = if (isDseeActive) 1.0f else 0.3f
            spinnerDsee.isEnabled = isDseeActive

            val isUpsampleActive = dspEnabled && factor >= 2
            layoutSectionCascadeFir.alpha = if (isUpsampleActive) 1.0f else 0.3f
            switchCascadeFir.isEnabled = isUpsampleActive
        }

        switchDirectSource.isChecked = isDirectSource
        val initialEffectiveFactor = if (isDirectSource) 1 else upsampleFactor
        updateDspSectionsState(isDirectSource, initialEffectiveFactor)

        switchDirectSource.setOnCheckedChangeListener { _, isChecked ->
            isDirectSource = isChecked
            prefs.edit { putBoolean("direct_source_enabled", isChecked) }
            NativeAudioEngine.nativeSetDirectSource(isChecked)

            val effectiveFactor = if (isChecked) 1 else upsampleFactor
            updateDspSectionsState(isChecked, effectiveFactor)
            playbackService?.setUpsampling(effectiveFactor)
            updateStatus()
        }

        switchCascadeFir.isChecked = isCascadeFir
        switchCascadeFir.setOnCheckedChangeListener { _, isChecked ->
            isCascadeFir = isChecked
            prefs.edit { putBoolean("cascade_fir_enabled", isChecked) }
            NativeAudioEngine.nativeSetCascadeFir(isChecked)
        }

        // ★ 移植された 0dB Volume Lock の制御
        val isUsb = isUsbDevice(activeOutputDevice)
        if (isUsb) {
            switchVolLock.isEnabled = true
            switchVolLock.alpha = 1.0f
        } else {
            switchVolLock.isEnabled = false
            switchVolLock.alpha = 0.35f
        }
        switchVolLock.isChecked = isVolLockOn
        switchVolLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUsbDevice(activeOutputDevice)) {
                switchVolLock.isChecked = false
                return@setOnCheckedChangeListener
            }
            isVolLockOn = isChecked
            prefs.edit { putBoolean("vol_lock_enabled", isChecked) }
            playbackService?.isVolumeLocked = isChecked
            if (isChecked) playbackService?.lockSystemVolumeToMax()
            updateStatus()
        }

        fun setEditMode(enabled: Boolean) {
            if (isDirectSource) return
            walkmanEqView.isEditMode = enabled
            if (enabled) {
                btnEqEdit.text = "完了"
                layoutEqAdjustControls.visibility = View.VISIBLE
                if (!switchEqEnable.isChecked) {
                    switchEqEnable.isChecked = true
                }
            } else {
                btnEqEdit.text = "調整"
                layoutEqAdjustControls.visibility = View.GONE
            }
        }

        fun updateEqHeader(bandIdx: Int, gain: Float) {
            textEqBandFreq.text = "${walkmanEqView.bandLabels[bandIdx]} Hz"
            textEqGainValue.text = String.format(java.util.Locale.US, "%+.1f dB", gain)
        }

        System.arraycopy(eqGains, 0, walkmanEqView.gains, 0, 10)
        walkmanEqView.isDirectBypass = !isEqEnabled
        switchEqEnable.isChecked = isEqEnabled
        setEditMode(false)
        updateEqHeader(walkmanEqView.selectedBandIndex, walkmanEqView.gains[walkmanEqView.selectedBandIndex])

        walkmanEqView.onBandSelectedListener = { bandIdx, gain -> updateEqHeader(bandIdx, gain) }
        walkmanEqView.onGainChangedListener = { bandIdx, gain, allGains ->
            updateEqHeader(bandIdx, gain)
            System.arraycopy(allGains, 0, eqGains, 0, 10)
            NativeAudioEngine.nativeSetEqualizer(isEqEnabled, eqGains)
            prefs.edit { for (i in 0..9) putFloat("eq_gain_$i", eqGains[i]) }
        }

        btnEqEdit.setOnClickListener { setEditMode(!walkmanEqView.isEditMode) }
        switchEqEnable.setOnCheckedChangeListener { _, isChecked ->
            isEqEnabled = isChecked
            walkmanEqView.isDirectBypass = !isChecked
            prefs.edit { putBoolean("eq_enabled", isChecked) }
            NativeAudioEngine.nativeSetEqualizer(isChecked, eqGains)
            if (!isChecked) setEditMode(false)
        }

        btnEqPlus.setOnClickListener { walkmanEqView.stepGain(+0.5f) }
        btnEqMinus.setOnClickListener { walkmanEqView.stepGain(-0.5f) }
        btnEqFlat.setOnClickListener { walkmanEqView.resetAllFlat() }

        val spinnerLayout = if (isDark) R.layout.item_spinner_dap else R.layout.item_spinner_dap_light
        val bitAdapter = ArrayAdapter(this, spinnerLayout, bitOptions)
        bitAdapter.setDropDownViewResource(spinnerLayout)
        spinnerBitDepth.adapter = bitAdapter
        spinnerBitDepth.setSelection(bitModeValues.indexOf(currentBitMode).coerceAtLeast(0))
        spinnerBitDepth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedMode = bitModeValues[position]
                if (selectedMode != currentBitMode) {
                    currentBitMode = selectedMode
                    prefs.edit { putString("selected_bit_mode", selectedMode) }
                    playbackService?.currentBitMode = selectedMode
                    playbackService?.initAudioTrack(selectedMode, baseSampleRate, if (isDirectSource) 1 else upsampleFactor, activeOutputDevice)
                    bitActivityMask = 0
                    peakDbL = -60f
                    peakDbR = -60f
                    walkmanLevelMeter?.reset()
                    updateStatus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val ditherAdapter = ArrayAdapter(this, spinnerLayout, ditherOptions)
        ditherAdapter.setDropDownViewResource(spinnerLayout)
        spinnerDither.adapter = ditherAdapter
        spinnerDither.setSelection(ditherModeValues.indexOf(currentDitherMode).coerceAtLeast(0))
        spinnerDither.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedMode = ditherModeValues[position]
                if (selectedMode != currentDitherMode) {
                    currentDitherMode = selectedMode
                    prefs.edit { putInt("selected_dither_mode", selectedMode) }
                    NativeAudioEngine.nativeSetDitherMode(selectedMode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dcPhaseAdapter = ArrayAdapter(this, spinnerLayout, dcPhaseOptions)
        dcPhaseAdapter.setDropDownViewResource(spinnerLayout)
        spinnerDcPhase.adapter = dcPhaseAdapter
        spinnerDcPhase.setSelection(dcPhaseTypeValues.indexOf(currentDcPhaseType).coerceAtLeast(0))
        spinnerDcPhase.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedType = dcPhaseTypeValues[position]
                if (selectedType != currentDcPhaseType) {
                    currentDcPhaseType = selectedType
                    prefs.edit { putInt("selected_dc_phase_type", selectedType) }
                    NativeAudioEngine.nativeSetDcPhaseType(selectedType)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dseeAdapter = ArrayAdapter(this, spinnerLayout, dseeOptions)
        dseeAdapter.setDropDownViewResource(spinnerLayout)
        spinnerDsee.adapter = dseeAdapter
        spinnerDsee.setSelection(dseeModeValues.indexOf(currentDseeMode).coerceAtLeast(0))
        spinnerDsee.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedMode = dseeModeValues[position]
                if (selectedMode != currentDseeMode) {
                    currentDseeMode = selectedMode
                    prefs.edit { putInt("selected_dsee_mode", selectedMode) }
                    applyPresetToDsp(selectedMode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val upsampleAdapter = ArrayAdapter(this, spinnerLayout, upsampleOptions)
        upsampleAdapter.setDropDownViewResource(spinnerLayout)
        spinnerUpsample.adapter = upsampleAdapter
        spinnerUpsample.setSelection(upsampleFactorValues.indexOf(upsampleFactor).coerceAtLeast(0))
        spinnerUpsample.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selectedFactor = upsampleFactorValues[position]
                if (selectedFactor != upsampleFactor) {
                    upsampleFactor = selectedFactor
                    prefs.edit { putInt("selected_upsample_factor", selectedFactor) }
                    if (!isDirectSource) {
                        playbackService?.setUpsampling(selectedFactor)
                    }
                    updateDspSectionsState(isDirectSource, if (isDirectSource) 1 else selectedFactor)
                    updateStatus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        dialog.setOnDismissListener {
            activeDialogArtworkImage = null
            activeDialogTrackTitle = null
            activeDialogTrackArtist = null
            activeDialogCurrentTime = null
            activeDialogTotalTime = null
            activeDialogSeekBar = null
            activeDialogPlayPauseBtn = null
            activeDialogEqView = null
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // =========================================================================
    // 2. UI・外観・システム設定専用ダイアログ
    // =========================================================================
    private fun showUiSettingsDialog() {
        val dialog = BottomSheetDialog(this, R.style.CustomBottomSheetDialogTheme)
        dialog.window?.setDimAmount(0f)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val view = layoutInflater.inflate(R.layout.dialog_ui_settings, null)
        dialog.setContentView(view)

        val isDark = isDarkThemeActive()

        val btnClose = view.findViewById<ImageButton>(R.id.btnUiDialogClose)
        val switchAdBlock = view.findViewById<SwitchCompat>(R.id.dialogSwitchAdBlock)
        val spinnerTheme = view.findViewById<Spinner>(R.id.dialogSpinnerTheme)
        val btnBatteryIgnore = view.findViewById<Button>(R.id.btnBatteryIgnore)

        val seekBar = view.findViewById<SeekBar>(R.id.dialogSeekBarUi)
        val btnPlayPause = view.findViewById<ImageButton>(R.id.dialogBtnPlayPauseUi)
        val btnPrev = view.findViewById<ImageButton>(R.id.dialogBtnPrevTrackUi)
        val btnNext = view.findViewById<ImageButton>(R.id.dialogBtnNextTrackUi)

        val imageArtwork = view.findViewById<ImageView>(R.id.dialogImageArtworkUi)
        val textTrackTitle = view.findViewById<TextView>(R.id.dialogTextTrackTitleUi)
        val textTrackArtist = view.findViewById<TextView>(R.id.dialogTextTrackArtistUi)
        val textCurrentTime = view.findViewById<TextView>(R.id.dialogTextCurrentTimeUi)
        val textTotalTime = view.findViewById<TextView>(R.id.dialogTextTotalTimeUi)

        if (!isDark) {
            view.setBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
            view.findViewById<View>(R.id.layoutSectionTheme)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
            view.findViewById<View>(R.id.layoutSectionBattery)?.setBackgroundColor(Color.parseColor("#F5F5F7"))
            view.findViewById<TextView>(R.id.textBatteryTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
            view.findViewById<TextView>(R.id.textBatterySub)?.setTextColor(Color.parseColor("#636366"))
            view.findViewById<TextView>(R.id.textAdBlockTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
            view.findViewById<TextView>(R.id.textAdBlockSub)?.setTextColor(Color.parseColor("#636366"))

            view.findViewById<View>(R.id.dividerUi1)?.setBackgroundColor(Color.parseColor("#E0E0E5"))

            val swTrackLight = ContextCompat.getDrawable(this, R.drawable.switch_track_walkman_outline_light)
            val swThumbLight = ContextCompat.getDrawable(this, R.drawable.switch_thumb_light)
            switchAdBlock?.trackDrawable = swTrackLight
            switchAdBlock?.thumbDrawable = swThumbLight

            btnClose.setBackgroundResource(R.drawable.bg_btn_icon_light)
            btnClose.setColorFilter(Color.parseColor("#1C1C1E"))

            spinnerTheme.setBackgroundResource(R.drawable.bg_spinner_dap_light)
            spinnerTheme.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)

            seekBar.progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#D1D1D6"))
            seekBar.progressTintList = ColorStateList.valueOf(Color.parseColor("#D49B28"))
            seekBar.thumbTintList = ColorStateList.valueOf(Color.parseColor("#1C1C1E"))

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

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                val panelH = topInfoPanel?.height ?: 0
                val screenH = resources.displayMetrics.heightPixels
                val targetH = screenH - panelH
                if (targetH > 0) {
                    val lp = bottomSheet.layoutParams
                    lp.height = targetH
                    bottomSheet.layoutParams = lp
                    behavior.peekHeight = targetH
                }
            }
        }

        val spinnerLayout = if (isDark) R.layout.item_spinner_dap else R.layout.item_spinner_dap_light
        val themeAdapter = ArrayAdapter(this, spinnerLayout, themeOptions)
        themeAdapter.setDropDownViewResource(spinnerLayout)
        spinnerTheme.adapter = themeAdapter
        spinnerTheme.setSelection(themeValues.indexOf(currentThemeMode).coerceAtLeast(0))

        spinnerTheme.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val selected = themeValues[position]
                if (selected != currentThemeMode) {
                    currentThemeMode = selected
                    prefs.edit { putString("ui_theme_mode", selected) }
                    applyThemeUi(selected)
                    dialog.dismiss()
                    showUiSettingsDialog()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnBatteryIgnore.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        switchAdBlock.isChecked = isAdBlockOn
        switchAdBlock.setOnCheckedChangeListener { _, isChecked ->
            isAdBlockOn = isChecked
            prefs.edit { putBoolean("ad_block_enabled", isChecked) }
            sendAdBlockSetting(isChecked)
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        activeDialogArtworkImage = imageArtwork
        activeDialogTrackTitle = textTrackTitle
        activeDialogTrackArtist = textTrackArtist
        activeDialogCurrentTime = textCurrentTime
        activeDialogTotalTime = textTotalTime
        activeDialogSeekBar = seekBar
        activeDialogPlayPauseBtn = btnPlayPause

        updateDialogPlayerUi()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentDuration > 0) {
                    val seekMs = (progress.toLong() * currentDuration) / 1000L
                    textCurrentTime.text = formatTime(seekMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                if (currentDuration > 0 && sb != null) {
                    val seekMs = (sb.progress.toLong() * currentDuration) / 1000L
                    try {
                        activeWebExtensionPort?.postMessage(JSONObject().apply {
                            put("command", "seek")
                            put("position", seekMs)
                        })
                    } catch (e: Exception) {}
                }
            }
        })

        btnPlayPause.setOnClickListener {
            val cmd = if (isPlayingState) "pause" else "play"
            try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", cmd) }) } catch (e: Exception) {}
        }
        btnPrev.setOnClickListener {
            try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "prev") }) } catch (e: Exception) {}
        }
        btnNext.setOnClickListener {
            try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "next") }) } catch (e: Exception) {}
        }

        dialog.setOnDismissListener {
            activeDialogArtworkImage = null
            activeDialogTrackTitle = null
            activeDialogTrackArtist = null
            activeDialogCurrentTime = null
            activeDialogTotalTime = null
            activeDialogSeekBar = null
            activeDialogPlayPauseBtn = null
        }

        dialog.show()
    }

    private fun showDevPresetsDialog() {
        val dialog = BottomSheetDialog(this, R.style.CustomBottomSheetDialogTheme)
        dialog.window?.setDimAmount(0f)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val view = layoutInflater.inflate(R.layout.dialog_dev_presets, null)
        FreqPresetManager.hookDevPresetsDialog(view)
        hookDevPresetsDialog(view)
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }

        val spinnerTargetPreset = view.findViewById<Spinner>(R.id.spinnerTargetPreset)
        val devSpinnerFir = view.findViewById<Spinner>(R.id.devSpinnerFir)
        val devSpinnerTransient = view.findViewById<Spinner>(R.id.devSpinnerTransient)
        val devSpinnerLpcAlgo = view.findViewById<Spinner>(R.id.devSpinnerLpcAlgo)
        val devSpinnerGain = view.findViewById<Spinner>(R.id.devSpinnerGain)
        val devSpinnerExtractFreq = view.findViewById<Spinner>(R.id.devSpinnerExtractFreq)
        val devSwitchQmf = view.findViewById<SwitchCompat>(R.id.devSwitchQmf)
        val devSwitchGroupDelay = view.findViewById<SwitchCompat>(R.id.devSwitchGroupDelay)
        val devSwitchLattice = view.findViewById<SwitchCompat>(R.id.devSwitchLattice)
        val devSwitchLrDither = view.findViewById<SwitchCompat>(R.id.devSwitchLrDither)
        val btnDevCopyConfig = view.findViewById<Button>(R.id.btnDevCopyConfig)
        val btnDevResetDefault = view.findViewById<Button>(R.id.btnDevResetDefault)
        val btnDevClose = view.findViewById<Button>(R.id.btnDevClose)

        val targetNames = arrayOf("Auto AI", "男性ボーカル", "女性ボーカル", "パーカッション", "ストリングス")
        val targetIds = arrayOf(1, 2, 3, 4, 5)

        val firOptions = arrayOf("Linear Phase Sharp", "Linear Phase Slow", "Minimum Phase Sharp", "Minimum Phase Slow")
        val transientOptions = arrayOf("OFF", "Natural (CD)", "Punch (打楽器)", "Acoustic (弦・打弦)")
        val lpcOptions = arrayOf("DSEE HX AI (適応)", "K2 LPC Natural (物理)", "Adaptive Exciter (輪郭)")
        val gainOptions = arrayOf("控えめ (0.08)", "標準 (0.12)", "豊か (0.16)", "強力 (0.20)")
        val gainValues = floatArrayOf(0.08f, 0.12f, 0.16f, 0.20f)
        val freqOptions = arrayOf("8,000 Hz", "9,500 Hz", "10,000 Hz", "10,500 Hz", "11,000 Hz", "11,500 Hz")
        val freqValues = floatArrayOf(8000.0f, 9500.0f, 10000.0f, 10500.0f, 11000.0f, 11500.0f)

        fun setupAdapter(sp: Spinner, items: Array<String>) {
            val ad = ArrayAdapter(this, R.layout.item_spinner_dap, items)
            ad.setDropDownViewResource(R.layout.item_spinner_dap)
            sp.adapter = ad
        }

        setupAdapter(spinnerTargetPreset, targetNames)
        setupAdapter(devSpinnerFir, firOptions)
        setupAdapter(devSpinnerTransient, transientOptions)
        setupAdapter(devSpinnerLpcAlgo, lpcOptions)
        setupAdapter(devSpinnerGain, gainOptions)
        setupAdapter(devSpinnerExtractFreq, freqOptions)

        var currentEditTargetId = currentDseeMode.let { if (it in 1..5) it else 1 }
        spinnerTargetPreset.setSelection(targetIds.indexOf(currentEditTargetId).coerceAtLeast(0))

        devSwitchLrDither.isChecked = isLrIndependentDither
        devSwitchLrDither.setOnCheckedChangeListener { _, isChecked ->
            isLrIndependentDither = isChecked
            prefs.edit { putBoolean("lr_dither_enabled", isChecked) }
            NativeAudioEngine.nativeSetLrIndependentDither(isChecked)
        }

        fun updateUiForProfile(targetId: Int) {
              (devSpinnerLpcAlgo?.parent as? android.view.View)?.let { it.isEnabled = true; it.alpha = 1.0f }
              (devSpinnerGain?.parent as? android.view.View)?.let { it.isEnabled = true; it.alpha = 1.0f }
              devSpinnerLpcAlgo?.let { it.isEnabled = true; it.alpha = 1.0f }
              devSpinnerGain?.let { it.isEnabled = true; it.alpha = 1.0f }
              devSpinnerExtractFreq?.let { it.isEnabled = true; it.alpha = 1.0f }
            val p = presetProfiles[targetId] ?: return
            devSpinnerFir.setSelection(p.firType.coerceIn(0, 3))
            devSpinnerTransient.setSelection(p.transientMode.coerceIn(0, 3))
            devSpinnerLpcAlgo.setSelection((p.lpcAlgo - 1).coerceIn(0, 2))

            var closestGainIdx = 2
            var minGDiff = Float.MAX_VALUE
            for (i in gainValues.indices) {
                val d = Math.abs(gainValues[i] - p.gain)
                if (d < minGDiff) { minGDiff = d; closestGainIdx = i }
            }
            devSpinnerGain.setSelection(closestGainIdx)

            var closestFIdx = 3
            var minFDiff = Float.MAX_VALUE
            for (i in freqValues.indices) {
                val d = Math.abs(freqValues[i] - p.extractFreq)
                if (d < minFDiff) { minFDiff = d; closestFIdx = i }
            }
            devSpinnerExtractFreq.setSelection(closestFIdx)

            devSwitchQmf.isChecked = p.useQmf
            devSwitchGroupDelay.isChecked = p.useGroupDelay
            devSwitchLattice.isChecked = p.useLattice
        }

        updateUiForProfile(currentEditTargetId)

        spinnerTargetPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentEditTargetId = targetIds[position]
                updateUiForProfile(currentEditTargetId)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        fun saveCurrentEditProfile() {
            val p = presetProfiles[currentEditTargetId] ?: return
            p.firType = devSpinnerFir.selectedItemPosition
            p.transientMode = devSpinnerTransient.selectedItemPosition
            p.lpcAlgo = devSpinnerLpcAlgo.selectedItemPosition + 1
            p.gain = gainValues[devSpinnerGain.selectedItemPosition]
            p.extractFreq = freqValues[devSpinnerExtractFreq.selectedItemPosition]
            p.useQmf = devSwitchQmf.isChecked
            p.useGroupDelay = devSwitchGroupDelay.isChecked
            p.useLattice = devSwitchLattice.isChecked

            prefs.edit {
                putInt("prof_${currentEditTargetId}_fir", p.firType)
                putInt("prof_${currentEditTargetId}_trans", p.transientMode)
                putInt("prof_${currentEditTargetId}_lpc", p.lpcAlgo)
                putFloat("prof_${currentEditTargetId}_gain", p.gain)
                putFloat("prof_${currentEditTargetId}_freq", p.extractFreq)
                putBoolean("prof_${currentEditTargetId}_qmf", p.useQmf)
                putBoolean("prof_${currentEditTargetId}_gd", p.useGroupDelay)
                putBoolean("prof_${currentEditTargetId}_lat", p.useLattice)
            }

            if (currentDseeMode == currentEditTargetId) {
                applyPresetToDsp(currentDseeMode)
            }
        }

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                saveCurrentEditProfile()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        devSpinnerFir.onItemSelectedListener = listener
        devSpinnerTransient.onItemSelectedListener = listener
        devSpinnerLpcAlgo.onItemSelectedListener = listener
        devSpinnerGain.onItemSelectedListener = listener
        devSpinnerExtractFreq.onItemSelectedListener = listener

        devSwitchQmf.setOnCheckedChangeListener { _, _ -> saveCurrentEditProfile() }
        devSwitchGroupDelay.setOnCheckedChangeListener { _, _ -> saveCurrentEditProfile() }
        devSwitchLattice.setOnCheckedChangeListener { _, _ -> saveCurrentEditProfile() }

        btnDevCopyConfig.setOnClickListener {
            val sb = StringBuilder()
            sb.append("// ========================================================\n")
            sb.append("// ★ 製品版(APK)固定用 最適化プリセット設定コード\n")
            sb.append("// ========================================================\n")
            for (id in 1..5) {
                val p = presetProfiles[id]!!
                val name = targetNames[id - 1]
                sb.append("presetProfiles[$id] = PresetProfile(${p.firType}, ${p.transientMode}, ${p.lpcAlgo}, ${p.gain}f, ${p.extractFreq}f, ${p.useQmf}, ${p.useGroupDelay}, ${p.useLattice}) // [$id] $name\n")
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("PresetConfig", sb.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "最適化設定コードをクリップボードにコピーしました！", Toast.LENGTH_LONG).show()
        }

        btnDevResetDefault.setOnClickListener {
            initDefaultProfiles()
            updateUiForProfile(currentEditTargetId)
            saveCurrentEditProfile()
            Toast.makeText(this, "初期値（最適初期値）にリセットしました", Toast.LENGTH_SHORT).show()
        }

        btnDevClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupBluetoothTracker() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = btManager?.adapter
            bluetoothAdapter?.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.A2DP) {
                        bluetoothA2dp = proxy as? BluetoothA2dp
                        fetchBluetoothCodec()
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) {
                        bluetoothA2dp = null
                    }
                }
            }, BluetoothProfile.A2DP)

            val filter = IntentFilter().apply {
                addAction("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED")
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e("BitPerfect", "Bluetooth tracker setup error", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchBluetoothCodec() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }
        val a2dp = bluetoothA2dp ?: return
        try {
            val devices = a2dp.connectedDevices
            if (devices.isNullOrEmpty()) {
                currentBtCodecName = ""
                return
            }
            val activeDev = devices[0]
            val getCodecStatusMethod = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
            val codecStatus = getCodecStatusMethod.invoke(a2dp, activeDev)
            if (codecStatus != null) {
                val getCodecConfigMethod = codecStatus.javaClass.getMethod("getCodecConfig")
                val codecConfig = getCodecConfigMethod.invoke(codecStatus)
                if (codecConfig != null) {
                    currentBtCodecName = parseComprehensiveCodec(codecConfig, codecStatus)
                }
            }
        } catch (e: SecurityException) {
            Log.w("BitPerfect", "Bluetooth permission not granted")
        } catch (e: Exception) {
            currentBtCodecName = "BT HD Audio"
        }
    }

    private fun parseComprehensiveCodec(codecConfig: Any, codecStatus: Any): String {
        try {
            try {
                val getNameMethod = codecConfig.javaClass.getMethod("getCodecName")
                val nameObj = getNameMethod.invoke(codecConfig)
                if (nameObj is String && nameObj.isNotEmpty() && !nameObj.contains("INVALID", true) && !nameObj.contains("UNKNOWN", true)) {
                    return formatCodecName(nameObj)
                }
            } catch (e: Exception) {}

            val fullStr = "${codecConfig} ${codecStatus}".lowercase()
            when {
                fullStr.contains("lossless") -> return "aptX Lossless"
                fullStr.contains("adaptive") || fullStr.contains("aptx-ad") || fullStr.contains("aptx_ad") -> return "aptX Adaptive"
                fullStr.contains("twsp") || fullStr.contains("tws+") || fullStr.contains("aptx-tws") -> return "aptX TWS+"
                fullStr.contains("lhdc-v5") || fullStr.contains("lhdc v5") -> return "LHDC V5"
                fullStr.contains("lhdc") -> return "LHDC"
                fullStr.contains("llac") -> return "LLAC"
                fullStr.contains("seamless") || fullStr.contains("ssc-uhq") -> return "Samsung Seamless"
                fullStr.contains("scalable") || fullStr.contains("ssc") -> return "Samsung Scalable"
                fullStr.contains("l2hc") -> return "L2HC"
                fullStr.contains("lc3") -> return "LC3 (LE Audio)"
                fullStr.contains("ldac") -> return "LDAC"
                fullStr.contains("aptx hd") || fullStr.contains("aptx-hd") || fullStr.contains("aptx_hd") -> return "aptX HD"
                fullStr.contains("aptx") -> return "aptX"
                fullStr.contains("opus") -> return "Opus"
                fullStr.contains("aac") -> return "AAC"
                fullStr.contains("sbc") -> return "SBC"
            }

            val getTypeMethod = codecConfig.javaClass.getMethod("getCodecType")
            val type = getTypeMethod.invoke(codecConfig) as Int
            return when (type) {
                0 -> "SBC"
                1 -> "AAC"
                2 -> "aptX"
                3 -> "aptX HD"
                4 -> "LDAC"
                5 -> "LC3 / aptX Adaptive"
                6 -> "Opus"
                7 -> "aptX Adaptive"
                8 -> "aptX TWS+"
                else -> "BT Codec ($type)"
            }
        } catch (e: Exception) {
            return "Bluetooth Audio"
        }
    }

    private fun formatCodecName(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("adaptive") -> "aptX Adaptive"
            lower.contains("lossless") -> "aptX Lossless"
            lower.contains("twsp") || lower.contains("tws") -> "aptX TWS+"
            lower.contains("hd") && lower.contains("aptx") -> "aptX HD"
            lower.contains("aptx") -> "aptX"
            lower.contains("ldac") -> "LDAC"
            lower.contains("lhdc") -> "LHDC"
            lower.contains("llac") -> "LLAC"
            lower.contains("seamless") -> "Samsung Seamless"
            lower.contains("scalable") || lower.contains("ssc") -> "Samsung Scalable"
            lower.contains("l2hc") -> "L2HC"
            lower.contains("lc3") -> "LC3"
            lower.contains("opus") -> "Opus"
            lower.contains("aac") -> "AAC"
            lower.contains("sbc") -> "SBC"
            else -> raw
        }
    }

    private fun reloadDirectStream() {
        runOnUiThread {
            pcmPacketCount = 0L
            bitActivityMask = 0
            peakDbL = -60f
            peakDbR = -60f
            walkmanLevelMeter?.reset()
            playbackService?.resetBuffer()
            playbackService?.initAudioTrack(currentBitMode, baseSampleRate, if (isDirectSource) 1 else upsampleFactor, activeOutputDevice)
            try {
                activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "resume_audio") })
            } catch (e: Exception) {}
            geckoSession.reload()
            updateStatus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolLockOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            val isUsb = isUsbDevice(activeOutputDevice)
            if (isUsb) {
                playbackService?.lockSystemVolumeToMax()
                if (event?.repeatCount == 0) {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        try {
                            activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "next") })
                        } catch (e: Exception) {
                            Log.e("BitPerfect", "Next track error", e)
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        try {
                            activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", "prev") })
                        } catch (e: Exception) {
                            Log.e("BitPerfect", "Prev track error", e)
                        }
                    }
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolLockOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (isUsbDevice(activeOutputDevice)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun sendAdBlockSetting(enabled: Boolean) {
        try {
            val jsonCmd = JSONObject().apply {
                put("command", "setAdBlock")
                put("enabled", enabled)
            }
            activeWebExtensionPort?.postMessage(jsonCmd)
        } catch (e: Exception) {}
    }

    private fun registerAudioDeviceCallback() {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                uiHandler.removeCallbacks(deviceDetectRunnable)
                uiHandler.postDelayed(deviceDetectRunnable, 250)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                uiHandler.removeCallbacks(deviceDetectRunnable)
                uiHandler.postDelayed(deviceDetectRunnable, 100)
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun detectAudioOutputDevice() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var usbDevice: AudioDeviceInfo? = null
        var btDevice: AudioDeviceInfo? = null

        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                usbDevice = device
                break
            } else if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                       (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                           device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                           device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                       )) ||
                       device.type == AudioDeviceInfo.TYPE_HEARING_AID) {
                if (btDevice == null) btDevice = device
            }
        }

        if (usbDevice != null) {
            activeOutputDevice = usbDevice
            outputDeviceName = usbDevice.productName.toString().replace("USB-Audio - ", "")
            playbackService?.restoreVolumeForDevice(usbDevice)
        } else if (btDevice != null) {
            activeOutputDevice = btDevice
            val rawName = btDevice.productName.toString()
            outputDeviceName = if (rawName.isNotEmpty()) rawName else "Bluetooth Audio"
        } else {
            activeOutputDevice = null
            outputDeviceName = "内蔵スピーカー"
            playbackService?.isVolumeLocked = false
            playbackService?.muteVolumeToZero()
        }

        updateStatus()
    }

    private fun safeToJson(msg: Any?): JSONObject? {
        return when (msg) {
            is JSONObject -> msg
            is String -> try { JSONObject(msg) } catch (e: Exception) { null }
            is Map<*, *> -> try { JSONObject(msg) } catch (e: Exception) { null }
            null -> null
            else -> try { JSONObject(msg.toString()) } catch (e: Exception) { null }
        }
    }

    private fun handleIncomingMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "flush" -> {
                playbackService?.resetBuffer()
            }
            "pcm" -> {
                val base64Pcm = msg.optString("pcm", "")
                if (base64Pcm.isNotEmpty()) {
                    val inBitMode = msg.optString("bitMode", "float32")
                    try {
                        val pcmBytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
                        if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                            pcmPacketCount += pcmBytes.size
                            isPlayingState = true
                            lastPcmTime = System.currentTimeMillis()
                            playbackService?.pushPcm(pcmBytes, baseSampleRate, inBitMode)
                        }
                    } catch (e: Exception) {
                        Log.e("BitPerfect", "PCM Base64 decode error", e)
                    }
                }
            }
            "codec" -> {
                currentCodec = msg.optString("codec", currentCodec)
                val rate = msg.optInt("sampleRate", 0)
                if (rate > 0 && rate != baseSampleRate) {
                    playbackService?.resetBuffer()
                    baseSampleRate = rate
                    playbackService?.setUpsampling(if (isDirectSource) 1 else upsampleFactor)
                }
                playbackService?.updateCodec(currentCodec)
                updateStatus()
            }
            "meta" -> {
                val title = msg.optString("title", "YouTube Music")
                val artist = msg.optString("artist", "")
                val artwork = msg.optString("artwork", "")
                currentTitle = title
                currentArtist = artist
                playbackService?.updateMetadata(title, artist, artwork)

                if (artwork.isNotEmpty()) {
                    imageExecutor.execute {
                        try {
                            val stream = URL(artwork).openStream()
                            val bmp = BitmapFactory.decodeStream(stream)
                            runOnUiThread {
                                currentArtworkBitmap = bmp
                                activeDialogArtworkImage?.setImageBitmap(bmp)
                            }
                        } catch (e: Exception) {
                            Log.e("BitPerfect", "Image decode error", e)
                        }
                    }
                }
                updateDialogPlayerUi()
            }
            "progress" -> {
                val current = msg.optLong("current", 0L)
                val duration = msg.optLong("duration", 0L)
                val isPlaying = msg.optBoolean("playing", isPlayingState)
                currentPosition = current
                currentDuration = duration
                isPlayingState = isPlaying
                playbackService?.updateProgress(current, duration, isPlaying)
                updateDialogPlayerUi()
            }
            "state" -> {
                val isPlaying = msg.optBoolean("playing", true)
                isPlayingState = isPlaying
                if (!isPlaying) {
                    peakDbL = -60f
                    peakDbR = -60f
                    bitActivityMask = 0
                    walkmanLevelMeter?.reset()
                }
                playbackService?.updatePlaybackState(isPlaying)
                updateDialogPlayerUi()
            }
        }
    }

    private fun updateStatus() {
        val mb = pcmPacketCount / (1024.0 * 1024.0)

        val dev = activeOutputDevice
        val isUsb = isUsbDevice(dev)

        val activeFactor = if (isDirectSource) 1 else upsampleFactor
        val dspTag = if (isDirectSource) " [DIRECT]" else (if (activeFactor > 1) " [DSP ${activeFactor}x]" else "")

        if (dev != null) {
            if (isUsb) {
                badgeDirect.text = if (isDirectSource) "DIRECT SOURCE" else "DIRECT STREAM$dspTag"
                badgeDirect.setBackgroundResource(R.drawable.bg_badge_direct)
                badgeDirect.setTextColor(Color.BLACK)
                textCodec.text = currentCodec.uppercase()
            } else {
                val btCodecBadge = if (currentBtCodecName.isNotEmpty()) "BT [$currentBtCodecName]$dspTag" else "BLUETOOTH$dspTag"
                badgeDirect.text = btCodecBadge
                badgeDirect.setBackgroundResource(R.drawable.bg_badge_bluetooth)
                badgeDirect.setTextColor(Color.BLACK)
                textCodec.text = if (currentBtCodecName.isNotEmpty()) {
                    "$currentBtCodecName | ${currentCodec.uppercase()}"
                } else {
                    currentCodec.uppercase()
                }
            }
        } else {
            badgeDirect.text = "STANDARD MIX$dspTag"
            badgeDirect.setBackgroundResource(R.drawable.bg_badge_normal)
            badgeDirect.setTextColor(Color.LTGRAY)
            textCodec.text = currentCodec.uppercase()
        }
        textDacName.text = outputDeviceName

        val bitLabel = when (currentBitMode) {
            "32bit" -> "32 bit"
            "24bit" -> "24 bit"
            else -> "16 bit"
        }

        val effectiveRate = playbackService?.effectiveSampleRate ?: (baseSampleRate * activeFactor)
        val rateStr = String.format(java.util.Locale.US, "%.1f", effectiveRate / 1000.0)
        textRateBits.text = "$rateStr kHz / $bitLabel"
        textTransfer.text = String.format("%.1f MB", mb)

        val peakTextL = if (peakDbL > -50f && isPlayingState) String.format(java.util.Locale.US, "%.1f", peakDbL) else "-inf"
        val peakTextR = if (peakDbR > -50f && isPlayingState) String.format(java.util.Locale.US, "%.1f", peakDbR) else "-inf"
        textPeak.text = "PEAK  L: ${peakTextL} dB  /  R: ${peakTextR} dB"

        val maxBits = if (currentBitMode == "32bit") 32 else (if (currentBitMode == "24bit") 24 else 16)
        val activeBits = if (!isPlayingState || (peakDbL <= -50f && peakDbR <= -50f)) 0 else Integer.bitCount(bitActivityMask).coerceIn(0, maxBits)
        textBitDepth.text = "BIT: $activeBits/$maxBits ACTIVE"
        textBitDepth.setTextColor(Color.parseColor("#E5A93C"))

        val now = System.currentTimeMillis()
        if (now - lastBitResetTime > 250L) {
            bitActivityMask = (bitActivityMask ushr 2) or (bitActivityMask and 0x01)
            lastBitResetTime = now
        }
    }

    private fun setupGeckoView() {
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .consoleOutput(true)
            .aboutConfigEnabled(true)
            .build()

        geckoRuntime = GeckoRuntime.getDefault(this)

        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .allowJavascript(true)
            .build()

        geckoSession = GeckoSession(sessionSettings)
        
        geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                if (perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                ) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                }
                return null
            }
        }

        geckoRuntime?.let { runtime ->
            geckoSession.open(runtime)
            geckoView.setSession(geckoSession)

            val extensionLocation = "resource://android/assets/yt_capture_extension/"
            val extensionId = "yt_capture@example.com"

            val messageDelegate = object : WebExtension.MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    safeToJson(message)?.let { handleIncomingMessage(it) }
                    return GeckoResult.fromValue(JSONObject())
                }

                override fun onConnect(port: WebExtension.Port) {
                    activeWebExtensionPort = port
                    sendAdBlockSetting(isAdBlockOn)
                    // ★ 接続時に現在のテーマ (Dark / Light) をWebExtensionへ送信
                    sendWebThemeSetting(if (isDarkThemeActive()) "dark" else "light")

                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            safeToJson(message)?.let { handleIncomingMessage(it) }
                        }
                        override fun onDisconnect(port: WebExtension.Port) {
                            if (activeWebExtensionPort == port) activeWebExtensionPort = null
                        }
                    })
                }
            }

            runtime.webExtensionController
                .ensureBuiltIn(extensionLocation, extensionId)
                .accept({ extension ->
                    if (extension != null) {
                        runOnUiThread {
                            extension.setMessageDelegate(messageDelegate, "browser")
                            geckoSession.webExtensionController.setMessageDelegate(extension, messageDelegate, "browser")
                            geckoSession.loadUri("https://music.youtube.com")
                        }
                    }
                }, { e ->
                    Log.e("BitPerfect", "WebExtension error", e)
                    runOnUiThread {
                        geckoSession.loadUri("https://music.youtube.com")
                    }
                })
        }
    }

    override fun onPause() {
        super.onPause()
        geckoSession.setActive(true)
        geckoSession.setFocused(true)
    }

    override fun onStop() {
        super.onStop()
        geckoSession.setActive(true)
        geckoSession.setFocused(true)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.removeCallbacks(deviceDetectRunnable)
        if (appWakeLock?.isHeld == true) {
            appWakeLock?.release()
        }
        try {
            unregisterReceiver(btReceiver)
        } catch (e: Exception) {}
        try {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
        } catch (e: Exception) {}
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        geckoSession.close()
    }

    // ★ FREQ プリセットに応じた適切な M/S空間 ＆ 動的SBR の推奨値
    private fun getFreqPresetRecommendedSwitches(presetPos: Int): Pair<Boolean, Boolean> {
        return when (presetPos) {
            1 -> Pair(true, true)   // Auto AI: 両方ON (動的自律制御)
            2 -> Pair(false, false) // 男性ボーカル: 両方OFF (センター定位・声の太さ最優先)
            3 -> Pair(true, true)   // 女性ボーカル: 両方ON (残響と頭声倍音)
            4 -> Pair(true, true)   // パーカッション: 両方ON (ステレオ感とシンバル抜け)
            5 -> Pair(true, true)   // ストリングス: 両方ON (ホール残響最大)
            else -> Pair(false, false) // OFF
        }
    }

    private fun applyFreqPresetAndSyncSwitches(presetPos: Int) {
        val (recMs, recSbr) = getFreqPresetRecommendedSwitches(presetPos)
        val prefs = getSharedPreferences("bp_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("current_freq_preset", presetPos)
            .putBoolean("dev_ms_spatial", recMs)
            .putBoolean("dev_dynamic_sbr", recSbr)
            .apply()

        NativeAudioEngine.nativeSetFreqMode(presetPos)
        NativeAudioEngine.nativeSetMsSpatial(recMs)
        NativeAudioEngine.nativeSetDynamicSbr(recSbr)
    }
}