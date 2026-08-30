package com.example.perfectbitrate

import com.example.perfectbitrate.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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

    private var currentBitMode = "16bit"
    private val bitOptions = arrayOf("16-bit (Std)", "24-bit (Hi-Res)", "32-bit (Float)")
    private val bitModeValues = arrayOf("16bit", "24bit", "32bit")

    private var peakDbL = -60f
    private var peakDbR = -60f
    private var bitActivityMask = 0
    private var isAdBlockOn = true
    private var isVolLockOn = false
    private var isPlayingState = false

    private var isOtherAppInterfering = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            uiHandler.postDelayed(this, 300)
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
            Log.i("BitPerfect", "PlaybackService connected.")
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
        switchVolLock.isChecked = isVolLockOn

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
                    if (isPlayingState) {
                        playbackService?.initAudioTrack(selectedMode, currentSampleRate, activeOutputDevice)
                    }
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
            // USB DAC接続時のみ0dBを許可
            val isUsb = activeOutputDevice?.let { dev ->
                dev.type == AudioDeviceInfo.TYPE_USB_DEVICE || dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: false

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

        registerAudioPlaybackCallback()

        val serviceIntent = Intent(this, BitPerfectPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        registerAudioDeviceCallback()
        setupGeckoView()

        btnReload.setOnClickListener {
            reloadDirectStream()
        }
        btnReload.setOnLongClickListener {
            reloadDirectStream()
            true
        }

        uiHandler.post(uiUpdateRunnable)
    }

    private fun reloadDirectStream() {
        Log.i("BitPerfect", "Reloading Direct Stream...")
        runOnUiThread {
            pcmPacketCount = 0L
            bitActivityMask = 0
            peakDbL = -60f
            peakDbR = -60f
            walkmanLevelMeter?.reset()
            playbackService?.forceCloseDacStream()
            playbackService?.resetBuffer()
            geckoSession.reload()
            updateStatus()
        }
    }

    private fun registerAudioPlaybackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    super.onPlaybackConfigChanged(configs)
                    if (configs == null) return

                    val activeConfigs = configs.filter { it.audioAttributes.usage != AudioAttributes.USAGE_UNKNOWN }

                    val otherInterfering = activeConfigs.size > 2 || activeConfigs.any { config ->
                        val usage = config.audioAttributes.usage
                        usage == AudioAttributes.USAGE_NOTIFICATION ||
                        usage == AudioAttributes.USAGE_ALARM ||
                        usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION ||
                        usage == AudioAttributes.USAGE_GAME ||
                        usage == AudioAttributes.USAGE_VOICE_COMMUNICATION
                    }

                    if (isOtherAppInterfering != otherInterfering) {
                        isOtherAppInterfering = otherInterfering
                        updateStatus()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVolLockOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            val isUsb = activeOutputDevice?.let { dev ->
                dev.type == AudioDeviceInfo.TYPE_USB_DEVICE || dev.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } ?: false
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
                detectAudioOutputDevice()
                playbackService?.setOutputDevice(activeOutputDevice)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                detectAudioOutputDevice()
                playbackService?.setOutputDevice(activeOutputDevice)
            }
        }, Handler(Looper.getMainLooper()))
    }

    // 優先度判定: USB DAC ＞ Bluetooth ＞ 内蔵スピーカー
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
        } else if (btDevice != null) {
            activeOutputDevice = btDevice
            val rawName = btDevice.productName.toString()
            outputDeviceName = if (rawName.isNotEmpty()) rawName else "Bluetooth Audio"
            
            // Bluetooth接続時は0dBロックを解除
            if (isVolLockOn) {
                isVolLockOn = false
                switchVolLock.isChecked = false
                prefs.edit { putBoolean("vol_lock_enabled", false) }
            }
        } else {
            activeOutputDevice = null
            outputDeviceName = "内蔵スピーカー"
            
            // スピーカー時は0dBをOFFにして音量0
            if (isVolLockOn) {
                isVolLockOn = false
                switchVolLock.isChecked = false
                prefs.edit { putBoolean("vol_lock_enabled", false) }
            }
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
        if (dev != null) {
            if (dev.type == AudioDeviceInfo.TYPE_USB_DEVICE || dev.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                badgeDirect.text = "DIRECT STREAM"
                badgeDirect.setBackgroundResource(R.drawable.bg_badge_direct)
                badgeDirect.setTextColor(Color.BLACK)
            } else {
                // Bluetooth接続時
                badgeDirect.text = "BLUETOOTH"
                badgeDirect.setBackgroundResource(R.drawable.bg_badge_bluetooth)
                badgeDirect.setTextColor(Color.BLACK)
            }
        } else {
            badgeDirect.text = "STANDARD MIX"
            badgeDirect.setBackgroundResource(R.drawable.bg_badge_normal)
            badgeDirect.setTextColor(Color.LTGRAY)
        }
        textDacName.text = outputDeviceName

        val bitLabel = when (currentBitMode) {
            "32bit" -> "32 bit Float"
            "24bit" -> "24 bit"
            else -> "16 bit"
        }

        val rateStr = String.format(java.util.Locale.US, "%.1f", currentSampleRate / 1000.0)
        textRateBits.text = "$rateStr kHz / $bitLabel"
        textCodec.text = currentCodec.uppercase()
        textTransfer.text = String.format("%.1f MB", mb)

        val peakTextL = if (peakDbL > -55f) String.format("%.1f", peakDbL) else "-inf"
        val peakTextR = if (peakDbR > -55f) String.format("%.1f", peakDbR) else "-inf"
        textPeak.text = "PEAK  L: ${peakTextL} dB  /  R: ${peakTextR} dB"

        val maxBits = if (currentBitMode == "32bit") 32 else (if (currentBitMode == "24bit") 24 else 16)
        val activeBits = Integer.bitCount(bitActivityMask).coerceAtMost(maxBits)
        textBitDepth.text = "BIT: $activeBits/$maxBits ACTIVE"
        textBitDepth.setTextColor(Color.parseColor("#E5A93C"))
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

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(uiUpdateRunnable)
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        geckoSession.close()
    }
}