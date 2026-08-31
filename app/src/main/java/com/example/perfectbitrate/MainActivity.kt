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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class MainActivity : AppCompatActivity() {

    private lateinit var badgeDirect: TextView
    private lateinit var textDacName: TextView
    private lateinit var textRateBits: TextView
    private lateinit var textCodec: TextView
    private lateinit var textTransfer: TextView
    private lateinit var textPeak: TextView
    private lateinit var textBitDepth: TextView
    private var walkmanLevelMeter: WalkmanLevelMeterView? = null

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private var geckoRuntime: GeckoRuntime? = null
    private lateinit var audioManager: AudioManager
    private lateinit var spinnerBitDepth: Spinner
    private lateinit var switchAdBlock: SwitchCompat
    private lateinit var switchVolLock: SwitchCompat
    private lateinit var prefs: SharedPreferences

    private var playbackService: BitPerfectPlaybackService? = null
    private var isServiceBound = false
    private var activeWebExtensionPort: WebExtension.Port? = null

    private var currentSampleRate = 48000
    private var pcmPacketCount = 0L
    private var outputDeviceName = "内蔵スピーカー"
    private var activeOutputDevice: AudioDeviceInfo? = null
    private var currentCodec = "OPUS 160kbps (48k)"
    private var currentBtCodecName = ""

    private var bluetoothA2dp: BluetoothA2dp? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var currentBitMode = "16bit"
    private val bitOptions = arrayOf("16-bit (Std)", "24-bit (Hi-Res)", "32-bit (Int32)")
    private val bitModeValues = arrayOf("16bit", "24bit", "32bit")

    private var peakDbL = -60f
    private var peakDbR = -60f
    private var bitActivityMask = 0
    private var lastBitResetTime = 0L
    private var isAdBlockOn = true
    private var isVolLockOn = false
    private var isPlayingState = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 300)
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
            playbackService?.setOutputDevice(activeOutputDevice)

            playbackService?.onActualBitModeChanged = { actualMode ->
                runOnUiThread {
                    if (actualMode != currentBitMode) {
                        currentBitMode = actualMode
                        sendBitModeSetting(actualMode)
                        val idx = bitModeValues.indexOf(actualMode)
                        if (idx >= 0 && spinnerBitDepth.selectedItemPosition != idx) {
                            spinnerBitDepth.setSelection(idx)
                        }
                        updateStatus()
                    }
                }
            }

            playbackService?.onPeakListener = { dbL, dbR, mask ->
                peakDbL = dbL
                peakDbR = dbR
                bitActivityMask = bitActivityMask or mask
                walkmanLevelMeter?.setLevels(dbL, dbR)
            }

            playbackService?.onDeviceDisconnectedListener = {
                runOnUiThread {
                    isVolLockOn = false
                    switchVolLock.isChecked = false
                    prefs.edit { putBoolean("vol_lock_enabled", false) }
                    detectAudioOutputDevice()
                }
            }

            playbackService?.onCommandListener = { cmd ->
                try {
                    val jsonCmd = JSONObject().apply { put("command", cmd) }
                    activeWebExtensionPort?.postMessage(jsonCmd)
                } catch (e: Exception) {
                    Log.e("BitPerfect", "Failed to send command", e)
                }
            }

            playbackService?.onSeekListener = { pos ->
                try {
                    val jsonCmd = JSONObject().apply {
                        put("command", "seek")
                        put("position", pos)
                    }
                    activeWebExtensionPort?.postMessage(jsonCmd)
                } catch (e: Exception) {
                    Log.e("BitPerfect", "Failed to send seek", e)
                }
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

        badgeDirect = findViewById(R.id.badgeDirect)
        textDacName = findViewById(R.id.textDacName)
        textRateBits = findViewById(R.id.textRateBits)
        textCodec = findViewById(R.id.textCodec)
        textTransfer = findViewById(R.id.textTransfer)
        textPeak = findViewById(R.id.textPeak)
        textBitDepth = findViewById(R.id.textBitDepth)
        walkmanLevelMeter = findViewById(R.id.walkmanLevelMeter)

        geckoView = findViewById(R.id.geckoview)
        spinnerBitDepth = findViewById(R.id.spinnerBitDepth)
        switchAdBlock = findViewById(R.id.switchAdBlock)
        switchVolLock = findViewById(R.id.switchVolLock)
        val btnReload = findViewById<Button>(R.id.btnReload)

        prefs = getSharedPreferences("bp_settings", Context.MODE_PRIVATE)
        isAdBlockOn = prefs.getBoolean("ad_block_enabled", true)
        isVolLockOn = false
        currentBitMode = prefs.getString("selected_bit_mode", "16bit") ?: "16bit"

        switchAdBlock.isChecked = isAdBlockOn
        switchVolLock.isChecked = false
        
        updateVolLockSwitchUi(false)

        val adapter = ArrayAdapter(this, R.layout.item_spinner_dap, bitOptions)
        adapter.setDropDownViewResource(R.layout.item_spinner_dap)
        spinnerBitDepth.adapter = adapter

        val initialIndex = bitModeValues.indexOf(currentBitMode).let { if (it >= 0) it else 0 }
        spinnerBitDepth.setSelection(initialIndex)

        spinnerBitDepth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedMode = bitModeValues[position]
                if (selectedMode != currentBitMode) {
                    currentBitMode = selectedMode
                    prefs.edit { putString("selected_bit_mode", selectedMode) }
                    sendBitModeSetting(selectedMode)
                    playbackService?.initAudioTrack(selectedMode, currentSampleRate, activeOutputDevice)
                    bitActivityMask = 0
                    peakDbL = -60f
                    peakDbR = -60f
                    walkmanLevelMeter?.reset()
                    updateStatus()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchAdBlock.setOnCheckedChangeListener { _, isChecked ->
            isAdBlockOn = isChecked
            prefs.edit { putBoolean("ad_block_enabled", isChecked) }
            sendAdBlockSetting(isChecked)
        }

        switchVolLock.setOnCheckedChangeListener { _, isChecked ->
            val isUsb = isUsbDevice(activeOutputDevice)

            if (isChecked && !isUsb) {
                switchVolLock.isChecked = false
                return@setOnCheckedChangeListener
            }
            isVolLockOn = isChecked
            prefs.edit { putBoolean("vol_lock_enabled", isChecked) }
            playbackService?.isVolumeLocked = isChecked
            if (isChecked) {
                playbackService?.lockSystemVolumeToMax()
            }
            updateStatus()
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        checkAndRequestPermissions()
        setupBluetoothTracker()

        val serviceIntent = Intent(this, BitPerfectPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        registerAudioDeviceCallback()
        setupGeckoView()

        btnReload.setOnClickListener {
            reloadDirectStream()
        }

        uiHandler.post(uiUpdateRunnable)
    }

    private fun isUsbDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    private fun updateVolLockSwitchUi(isUsb: Boolean) {
        if (isUsb) {
            switchVolLock.isEnabled = true
            switchVolLock.alpha = 1.0f
            switchVolLock.setTextColor(Color.parseColor("#CCCCCC"))
        } else {
            if (switchVolLock.isChecked) {
                switchVolLock.isChecked = false
                isVolLockOn = false
                prefs.edit { putBoolean("vol_lock_enabled", false) }
                playbackService?.isVolumeLocked = false
            }
            switchVolLock.isEnabled = false
            switchVolLock.alpha = 0.35f
            switchVolLock.setTextColor(Color.parseColor("#555555"))
        }
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
                    Log.i("BitPerfect", "★ Bluetooth Codec Detected: $currentBtCodecName")
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
            playbackService?.initAudioTrack(currentBitMode, currentSampleRate, activeOutputDevice)
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
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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

    private fun sendBitModeSetting(mode: String) {
        try {
            val jsonCmd = JSONObject().apply {
                put("command", "setBitMode")
                put("mode", mode)
            }
            activeWebExtensionPort?.postMessage(jsonCmd)
        } catch (e: Exception) {}
    }

    private fun registerAudioDeviceCallback() {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.i("BitPerfect", "★ Output device connected -> Scheduling detection...")
                uiHandler.removeCallbacks(deviceDetectRunnable)
                uiHandler.postDelayed(deviceDetectRunnable, 250)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.i("BitPerfect", "★ Output device removed -> Scheduling detection...")
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

        val isUsb = (usbDevice != null)
        updateVolLockSwitchUi(isUsb)

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

    private fun handleIncomingMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "pcm" -> {
                val base64Pcm = msg.getString("pcm")
                val bitMode = msg.optString("bitMode", currentBitMode)
                val sampleRate = msg.optInt("sampleRate", currentSampleRate)
                if (sampleRate > 0) currentSampleRate = sampleRate

                val pcmBytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
                pcmPacketCount += pcmBytes.size
                isPlayingState = true

                playbackService?.pushPcm(pcmBytes, currentSampleRate, bitMode)
            }
            "codec" -> {
                currentCodec = msg.optString("codec", currentCodec)
                val sampleRate = msg.optInt("sampleRate", currentSampleRate)
                if (sampleRate > 0) currentSampleRate = sampleRate
                playbackService?.updateCodec(currentCodec)
            }
            "meta" -> {
                val title = msg.optString("title", "YouTube Music")
                val artist = msg.optString("artist", "")
                val artwork = msg.optString("artwork", "")
                playbackService?.updateMetadata(title, artist, artwork)
            }
            "progress" -> {
                val current = msg.optLong("current", 0L)
                val duration = msg.optLong("duration", 0L)
                val isPlaying = msg.optBoolean("playing", isPlayingState)
                isPlayingState = isPlaying
                playbackService?.updateProgress(current, duration, isPlaying)
            }
            "state" -> {
                val isPlaying = msg.optBoolean("playing", true)
                isPlayingState = isPlaying
                if (!isPlaying) {
                    peakDbL = -60f
                    peakDbR = -60f
                    walkmanLevelMeter?.reset()
                }
                playbackService?.updatePlaybackState(isPlaying)
            }
        }
    }

    private fun updateStatus() {
        val mb = pcmPacketCount / (1024.0 * 1024.0)

        val dev = activeOutputDevice
        val isUsb = isUsbDevice(dev)
        updateVolLockSwitchUi(isUsb)

        if (dev != null) {
            if (isUsb) {
                badgeDirect.text = "DIRECT STREAM"
                badgeDirect.setBackgroundResource(R.drawable.bg_badge_direct)
                badgeDirect.setTextColor(Color.BLACK)
                textCodec.text = currentCodec.uppercase()
            } else {
                val btCodecBadge = if (currentBtCodecName.isNotEmpty()) "BT [$currentBtCodecName]" else "BLUETOOTH"
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
            badgeDirect.text = "STANDARD MIX"
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

        val rateStr = String.format(java.util.Locale.US, "%.1f", currentSampleRate / 1000.0)
        textRateBits.text = "$rateStr kHz / $bitLabel"
        textTransfer.text = String.format("%.1f MB", mb)

        val peakTextL = if (peakDbL > -55f) String.format("%.1f", peakDbL) else "-inf"
        val peakTextR = if (peakDbR > -55f) String.format("%.1f", peakDbR) else "-inf"
        textPeak.text = "PEAK  L: ${peakTextL} dB  /  R: ${peakTextR} dB"

        val maxBits = if (currentBitMode == "32bit") 32 else (if (currentBitMode == "24bit") 24 else 16)
        val activeBits = Integer.bitCount(bitActivityMask).coerceAtMost(maxBits)
        textBitDepth.text = "BIT: $activeBits/$maxBits ACTIVE"
        textBitDepth.setTextColor(Color.parseColor("#E5A93C"))

        val now = System.currentTimeMillis()
        if (now - lastBitResetTime > 1000L) {
            bitActivityMask = bitActivityMask ushr 1
            lastBitResetTime = now
        }
    }

    private fun setupGeckoView() {
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .consoleOutput(false)
            .aboutConfigEnabled(false)
            .build()

        geckoRuntime = GeckoRuntime.getDefault(this)

        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .allowJavascript(true)
            .build()

        geckoSession = GeckoSession(sessionSettings)
        
        // Autoplay を無条件許可
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
                    if (message is JSONObject) {
                        handleIncomingMessage(message)
                    }
                    return GeckoResult.fromValue(JSONObject())
                }

                override fun onConnect(port: WebExtension.Port) {
                    activeWebExtensionPort = port
                    sendAdBlockSetting(isAdBlockOn)
                    sendBitModeSetting(currentBitMode)

                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            if (message is JSONObject) {
                                handleIncomingMessage(message)
                            }
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
    }

    override fun onStop() {
        super.onStop()
        geckoSession.setActive(true)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        uiHandler.removeCallbacks(deviceDetectRunnable)
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