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
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // ★ 表示中のダイアログへのプレイヤー更新コントローラー
    private var activePlayerDialog: PlayerDialogController? = null
    private var activeDspDialog: DspSettingsDialog? = null

    private var bluetoothA2dp: BluetoothA2dp? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var isDirectSource = false
    private var currentThemeMode = "dark"
    private var currentBitMode = "16bit"
    private var isVolLockOn = false
    private var isPlayingState = false

    private var peakDbL = -60f
    private var peakDbR = -60f
    private var bitActivityMask = 0
    private var lastBitResetTime = 0L
    private var lastPcmTime = 0L

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

            NativeAudioEngine.nativeSetPerformanceMode(appPrefs.selectedPerfMode)
            NativeAudioEngine.nativeSetDirectSource(isDirectSource)
            NativeAudioEngine.nativeSetCascadeFir(appPrefs.isCascadeFir)
            NativeAudioEngine.nativeSetDitherMode(appPrefs.selectedDitherMode)
            NativeAudioEngine.nativeSetLrIndependentDither(appPrefs.isLrIndependentDither)
            NativeAudioEngine.nativeSetDcPhaseType(appPrefs.selectedDcPhaseType)
            
            FreqPresetManager.applyCurrentPresetToNative()
            val eqGains = FloatArray(10) { appPrefs.getEqGain(it) }
            NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, eqGains)

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
                activePlayerDialog?.setSpectrumLevels(spectrumBands)
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

        isDirectSource = appPrefs.isDirectSource
        currentThemeMode = appPrefs.uiThemeMode
        isVolLockOn = false
        currentBitMode = appPrefs.selectedBitMode
        upsampleFactor = appPrefs.selectedUpsampleFactor

        FreqPresetManager.setInitialPresetIndex(appPrefs.selectedPresetIndex)
        FreqPresetManager.onPresetChangedListener = { pos, _ ->
            appPrefs.selectedPresetIndex = pos
            activeDspDialog?.updatePerfModeState(upsampleFactor >= 2 && !isDirectSource)
        }

        val eqGains = FloatArray(10) { appPrefs.getEqGain(it) }
        NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, eqGains)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        checkAndRequestPermissions()
        setupBluetoothTracker()

        val serviceIntent = Intent(this, BitPerfectPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        registerAudioDeviceCallback()
        setupGeckoView()

        applyThemeUi(currentThemeMode)

        btnReload.setOnClickListener { reloadDirectStream() }

        btnDspSettings.setOnClickListener { showDspSettingsDialog() }

        btnDspSettings.setOnLongClickListener {
            Toast.makeText(this, "DEVELOPER PRESET TUNER", Toast.LENGTH_SHORT).show()
            showDevPresetsDialog()
            true
        }

        btnUiSettings.setOnClickListener { showUiSettingsDialog() }

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

    private fun isUsbDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun updateDialogPlayerUi() {
        activePlayerDialog?.updatePlayerState(
            title = currentTitle,
            artist = currentArtist,
            artwork = currentArtworkBitmap,
            isPlaying = isPlayingState,
            currentPositionMs = currentPosition,
            durationMs = currentDuration
        )
    }

    // =========================================================================
    // 分割された各ダイアログの表示ハンドラ
    // =========================================================================
    private fun showDspSettingsDialog() {
        val dspDialog = DspSettingsDialog(
            activity = this,
            isDarkTheme = isDarkThemeActive(),
            topPanelHeight = topInfoPanel?.height ?: 0,
            activeOutputDevice = activeOutputDevice,
            baseSampleRate = baseSampleRate,
            isVolumeLocked = isVolLockOn,
            onVolumeLockChanged = { isLocked ->
                isVolLockOn = isLocked
                appPrefs.isVolLockEnabled = isLocked
                playbackService?.isVolumeLocked = isLocked
                if (isLocked) playbackService?.lockSystemVolumeToMax()
                updateStatus()
            },
            onBitModeChanged = { newMode ->
                currentBitMode = newMode
                playbackService?.currentBitMode = newMode
                playbackService?.initAudioTrack(newMode, baseSampleRate, if (isDirectSource) 1 else upsampleFactor, activeOutputDevice)
                bitActivityMask = 0
                peakDbL = -60f
                peakDbR = -60f
                walkmanLevelMeter?.reset()
                updateStatus()
            },
            onUpsampleFactorChanged = { newFactor ->
                upsampleFactor = newFactor
                if (!isDirectSource) playbackService?.setUpsampling(newFactor)
                updateStatus()
            },
            onDirectSourceChanged = { isDirect ->
                isDirectSource = isDirect
                val effectiveFactor = if (isDirect) 1 else upsampleFactor
                playbackService?.setUpsampling(effectiveFactor)
                updateStatus()
            },
            onPlayerCommand = { cmd ->
                if (cmd == "play_pause") {
                    val actCmd = if (isPlayingState) "pause" else "play"
                    try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", actCmd) }) } catch (e: Exception) {}
                } else {
                    try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", cmd) }) } catch (e: Exception) {}
                }
            },
            onSeekTo = { pos ->
                try {
                    activeWebExtensionPort?.postMessage(JSONObject().apply {
                        put("command", "seek")
                        put("position", pos)
                    })
                } catch (e: Exception) {}
            },
            onDismiss = {
                activePlayerDialog = null
                activeDspDialog = null
            }
        )

        activePlayerDialog = dspDialog
        activeDspDialog = dspDialog
        dspDialog.show()
    }

    private fun showUiSettingsDialog() {
        val uiDialog = UiSettingsDialog(
            activity = this,
            isDarkTheme = isDarkThemeActive(),
            topPanelHeight = topInfoPanel?.height ?: 0,
            onThemeChanged = { newTheme ->
                applyThemeUi(newTheme)
                showUiSettingsDialog()
            },
            onAdBlockChanged = { isEnabled ->
                sendAdBlockSetting(isEnabled)
            },
            onPlayerCommand = { cmd ->
                if (cmd == "play_pause") {
                    val actCmd = if (isPlayingState) "pause" else "play"
                    try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", actCmd) }) } catch (e: Exception) {}
                } else {
                    try { activeWebExtensionPort?.postMessage(JSONObject().apply { put("command", cmd) }) } catch (e: Exception) {}
                }
            },
            onSeekTo = { pos ->
                try {
                    activeWebExtensionPort?.postMessage(JSONObject().apply {
                        put("command", "seek")
                        put("position", pos)
                    })
                } catch (e: Exception) {}
            },
            onDismiss = {
                activePlayerDialog = null
            }
        )

        activePlayerDialog = uiDialog
        uiDialog.show()
    }

    private fun showDevPresetsDialog() {
        DevPresetsDialog(this).show()
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
                                updateDialogPlayerUi()
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
                    sendAdBlockSetting(appPrefs.isAdBlockEnabled)
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