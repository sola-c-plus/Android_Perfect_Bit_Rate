package com.example.perfectbitrate

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    // ★ 独立管理された各コントローラー
    private val appPrefs by lazy { AppPreferences.get() }
    private lateinit var btCodecTracker: BluetoothCodecTracker
    private lateinit var geckoController: GeckoSessionController
    private var activePlayerDialog: PlayerDialogController? = null
    private var activeDspDialog: DspSettingsDialog? = null

    private var playbackService: BitPerfectPlaybackService? = null
    private var isServiceBound = false
    private var audioManager: AudioManager? = null
    private var appWakeLock: PowerManager.WakeLock? = null

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
        btCodecTracker.fetchCurrentCodec()
        geckoController.sendCommand("resume_audio")
    }

    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.BLUETOOTH_CONNECT] == true) {
                btCodecTracker.fetchCurrentCodec()
                updateStatus()
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
            NativeAudioEngine.nativeSetRichHarmonics(appPrefs.isRichHarmonicsEnabled)
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

            playbackService?.onCommandListener = { cmd -> geckoController.sendCommand(cmd) }
            playbackService?.onSeekListener = { pos -> geckoController.sendSeek(pos) }
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

        // 1. Bluetooth コントローラー起動
        btCodecTracker = BluetoothCodecTracker(this) { codec ->
            currentBtCodecName = codec
            updateStatus()
        }
        btCodecTracker.start()

        // 2. GeckoView コントローラー起動
        geckoController = GeckoSessionController(
            activity = this,
            geckoView = findViewById(R.id.geckoview),
            listener = object : GeckoSessionController.Listener {
                override fun onFlush() { playbackService?.resetBuffer() }
                override fun onPcm(pcmBytes: ByteArray, inBitMode: String) {
                    pcmPacketCount += pcmBytes.size
                    isPlayingState = true
                    lastPcmTime = System.currentTimeMillis()
                    playbackService?.pushPcm(pcmBytes, baseSampleRate, inBitMode)
                }
                override fun onCodec(codec: String, rate: Int) {
                    currentCodec = codec
                    if (rate > 0 && rate != baseSampleRate) {
                        playbackService?.resetBuffer()
                        baseSampleRate = rate
                        playbackService?.setUpsampling(if (isDirectSource) 1 else upsampleFactor)
                    }
                    playbackService?.updateCodec(codec)
                    updateStatus()
                }
                override fun onMetadata(title: String, artist: String, artworkUrl: String) {
                    currentTitle = title
                    currentArtist = artist
                    playbackService?.updateMetadata(title, artist, artworkUrl)
                    if (artworkUrl.isNotEmpty()) {
                        imageExecutor.execute {
                            try {
                                val stream = URL(artworkUrl).openStream()
                                val bmp = BitmapFactory.decodeStream(stream)
                                runOnUiThread {
                                    currentArtworkBitmap = bmp
                                    updateDialogPlayerUi()
                                }
                            } catch (e: Exception) { Log.e("MainActivity", "Image decode error", e) }
                        }
                    }
                    updateDialogPlayerUi()
                }
                override fun onProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
                    currentPosition = currentMs
                    currentDuration = durationMs
                    isPlayingState = isPlaying
                    playbackService?.updateProgress(currentMs, durationMs, isPlaying)
                    updateDialogPlayerUi()
                }
                override fun onState(isPlaying: Boolean) {
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
                override fun isDarkTheme(): Boolean = isDarkThemeActive()
                override fun isAdBlockEnabled(): Boolean = appPrefs.isAdBlockEnabled
            }
        )
        geckoController.init()

        val serviceIntent = Intent(this, BitPerfectPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        registerAudioDeviceCallback()
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
            "auto" -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }

    private fun applyThemeUi(themeMode: String) {
        currentThemeMode = themeMode
        val isDark = isDarkThemeActive()
        walkmanLevelMeter?.isLightMode = !isDark
        geckoController.sendWebTheme(isDark)

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
                    geckoController.sendCommand(if (isPlayingState) "pause" else "play")
                } else {
                    geckoController.sendCommand(cmd)
                }
            },
            onSeekTo = { pos -> geckoController.sendSeek(pos) },
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
            onAdBlockChanged = { isEnabled -> geckoController.sendAdBlock(isEnabled) },
            onPlayerCommand = { cmd ->
                if (cmd == "play_pause") {
                    geckoController.sendCommand(if (isPlayingState) "pause" else "play")
                } else {
                    geckoController.sendCommand(cmd)
                }
            },
            onSeekTo = { pos -> geckoController.sendSeek(pos) },
            onDismiss = { activePlayerDialog = null }
        )
        activePlayerDialog = uiDialog
        uiDialog.show()
    }

    private fun showDevPresetsDialog() {
        DevPresetsDialog(this).show()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissions.toTypedArray())
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
            geckoController.reload()
            updateStatus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolLockOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (isUsbDevice(activeOutputDevice)) {
                playbackService?.lockSystemVolumeToMax()
                if (event?.repeatCount == 0) {
                    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) geckoController.sendCommand("next")
                    else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) geckoController.sendCommand("prev")
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolLockOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (isUsbDevice(activeOutputDevice)) return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun registerAudioDeviceCallback() {
        audioManager?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
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
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return
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
                textCodec.text = if (currentBtCodecName.isNotEmpty()) "$currentBtCodecName | ${currentCodec.uppercase()}" else currentCodec.uppercase()
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

    override fun onPause() {
        super.onPause()
        geckoController.onPause()
    }

    override fun onStop() {
        super.onStop()
        geckoController.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.removeCallbacks(deviceDetectRunnable)
        if (appWakeLock?.isHeld == true) appWakeLock?.release()
        btCodecTracker.stop()
        geckoController.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}