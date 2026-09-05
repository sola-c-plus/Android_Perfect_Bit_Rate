package com.example.perfectbitrate

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
    
    // ★ AppPreferences による型安全な設定アクセス
    private val appPrefs by lazy { AppPreferences.get() }
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

    private var currentPerfMode = 1
    private val perfModeOptions = arrayOf("Eco (省電力)", "普通 (標準)", "超高音質 (フルスペック)")
    private val perfModeValues = arrayOf(0, 1, 2)

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

    private var frontPerfSectionWeakRef: java.lang.ref.WeakReference<View>? = null
    private var frontPerfSpinnerWeakRef: java.lang.ref.WeakReference<Spinner>? = null

    private fun updateFrontPerfModeUI() {
        val isDirect = isDirectSource
        val factor = if (isDirect) 1 else upsampleFactor
        val isPerfActive = !isDirect && (factor >= 2) && (FreqPresetManager.currentPresetIndex != 0)
        val alpha = if (isPerfActive) 1.0f else 0.35f

        frontPerfSectionWeakRef?.get()?.let { it.alpha = alpha }
        frontPerfSpinnerWeakRef?.get()?.let {
            it.isEnabled = isPerfActive
            it.alpha = alpha
        }
    }

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

            NativeAudioEngine.nativeSetPerformanceMode(currentPerfMode)
            NativeAudioEngine.nativeSetDirectSource(isDirectSource)
            NativeAudioEngine.nativeSetCascadeFir(isCascadeFir)
            NativeAudioEngine.nativeSetDitherMode(currentDitherMode)
            NativeAudioEngine.nativeSetLrIndependentDither(isLrIndependentDither)
            NativeAudioEngine.nativeSetDcPhaseType(currentDcPhaseType)
            
            FreqPresetManager.applyCurrentPresetToNative()
            NativeAudioEngine.nativeSetEqualizer(isEqEnabled, eqGains)

            playbackService?.onActualBitModeChanged = { actualMode ->
                runOnUiThread {
                    if (actualMode != currentBitMode) {
                        currentBitMode = actualMode
                        appPrefs.selectedBitMode = actualMode
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
                    appPrefs.isVolLockEnabled = false
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

        // ★ AppPreferences 初期化
        AppPreferences.init(this)

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

        // ★ 設定の読み出し (AppPreferences から型安全に取得)
        isDirectSource = appPrefs.isDirectSource
        isCascadeFir = appPrefs.isCascadeFir
        isLrIndependentDither = appPrefs.isLrIndependentDither
        isSpectrumOn = appPrefs.isSpectrumEnabled
        isAdBlockOn = appPrefs.isAdBlockEnabled
        currentThemeMode = appPrefs.uiThemeMode
        isVolLockOn = false
        currentBitMode = appPrefs.selectedBitMode
        currentPerfMode = appPrefs.selectedPerfMode
        upsampleFactor = appPrefs.selectedUpsampleFactor
        currentDitherMode = appPrefs.selectedDitherMode
        currentDcPhaseType = appPrefs.selectedDcPhaseType

        val savedPresetIdx = appPrefs.selectedPresetIndex
        FreqPresetManager.setInitialPresetIndex(savedPresetIdx)
        FreqPresetManager.onPresetChangedListener = { pos, _ ->
            appPrefs.selectedPresetIndex = pos
            updateFrontPerfModeUI()
        }

        isEqEnabled = appPrefs.isEqEnabled
        for (i in 0..9) {
            eqGains[i] = appPrefs.getEqGain(i)
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

    private fun showDspSettingsDialog() {
        val dialog = BottomSheetDialog(this, R.style.CustomBottomSheetDialogTheme)
        dialog.window?.setDimAmount(0f)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val view = layoutInflater.inflate(R.layout.dialog_dsp_settings, null)
        FreqPresetManager.hookDspSettingsDialog(view)
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
        val spinnerPerfMode = view.findViewById<Spinner>(R.id.dialogSpinnerPerfMode)
        val layoutSectionPerfMode = view.findViewById<View>(R.id.layoutSectionPerfMode)
        val spinnerDsee = view.findViewById<Spinner>(R.id.dialogSpinnerDsee)
        val spinnerUpsample = view.findViewById<Spinner>(R.id.dialogSpinnerUpsample)

        frontPerfSectionWeakRef = java.lang.ref.WeakReference(layoutSectionPerfMode)
        frontPerfSpinnerWeakRef = java.lang.ref.WeakReference(spinnerPerfMode)

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
            view.findViewById<TextView>(R.id.textPerfModeTitle)?.setTextColor(Color.parseColor("#1C1C1E"))
            view.findViewById<TextView>(R.id.textPerfModeSub)?.setTextColor(Color.parseColor("#636366"))
            view.findViewById<View>(R.id.dividerDspPerf)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
            spinnerPerfMode?.setBackgroundResource(R.drawable.bg_spinner_dap_light)
            spinnerPerfMode?.setPopupBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
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
            val dsp4Id = resources.getIdentifier("dividerDsp4", "id", packageName)
            if (dsp4Id != 0) view.findViewById<View>(dsp4Id)?.setBackgroundColor(Color.parseColor("#E0E0E5"))
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
            appPrefs.isSpectrumEnabled = isChecked
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

            val isPerfActive = isDseeActive && (FreqPresetManager.currentPresetIndex != 0)
            val perfAlpha = if (isPerfActive) 1.0f else 0.35f
            layoutSectionPerfMode?.alpha = perfAlpha
            spinnerPerfMode?.isEnabled = isPerfActive
            spinnerPerfMode?.alpha = perfAlpha
        }

        switchDirectSource.isChecked = isDirectSource
        val initialEffectiveFactor = if (isDirectSource) 1 else upsampleFactor
        updateDspSectionsState(isDirectSource, initialEffectiveFactor)

        switchDirectSource.setOnCheckedChangeListener { _, isChecked ->
            isDirectSource = isChecked
            appPrefs.isDirectSource = isChecked
            NativeAudioEngine.nativeSetDirectSource(isChecked)

            val effectiveFactor = if (isChecked) 1 else upsampleFactor
            updateDspSectionsState(isChecked, effectiveFactor)
            playbackService?.setUpsampling(effectiveFactor)
            updateStatus()
        }

        switchCascadeFir.isChecked = isCascadeFir
        switchCascadeFir.setOnCheckedChangeListener { _, isChecked ->
            isCascadeFir = isChecked
            appPrefs.isCascadeFir = isChecked
            NativeAudioEngine.nativeSetCascadeFir(isChecked)
        }

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
            appPrefs.isVolLockEnabled = isChecked
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
            appPrefs.setAllEqGains(eqGains)
        }

        btnEqEdit.setOnClickListener { setEditMode(!walkmanEqView.isEditMode) }
        switchEqEnable.setOnCheckedChangeListener { _, isChecked ->
            isEqEnabled = isChecked
            walkmanEqView.isDirectBypass = !isChecked
            appPrefs.isEqEnabled = isChecked
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
                    appPrefs.selectedBitMode = selectedMode
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
                    appPrefs.selectedDitherMode = selectedMode
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
                    appPrefs.selectedDcPhaseType = selectedType
                    NativeAudioEngine.nativeSetDcPhaseType(selectedType)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val perfAdapter = ArrayAdapter(this, spinnerLayout, perfModeOptions)
        perfAdapter.setDropDownViewResource(spinnerLayout)
        spinnerPerfMode?.adapter = perfAdapter
        spinnerPerfMode?.setSelection(currentPerfMode.coerceIn(0, 2))

        spinnerPerfMode?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (currentPerfMode != position) {
                    currentPerfMode = position
                    appPrefs.selectedPerfMode = position
                    NativeAudioEngine.nativeSetPerformanceMode(position)
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
                    appPrefs.selectedUpsampleFactor = selectedFactor
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
            frontPerfSectionWeakRef = null
            frontPerfSpinnerWeakRef = null
            FreqPresetManager.clearFrontDialogRefs()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

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
                    appPrefs.uiThemeMode = selected
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
            appPrefs.isAdBlockEnabled = isChecked
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
        dialog.setContentView(view)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }

        dialog.setOnDismissListener {
            FreqPresetManager.clearDevDialogRefs()
        }

        val btnDevClose = view.findViewById<Button>(R.id.btnDevClose)
        btnDevClose?.setOnClickListener { dialog.dismiss() }
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
}