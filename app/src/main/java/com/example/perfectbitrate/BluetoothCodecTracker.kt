package com.example.perfectbitrate

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothCodecTracker(
    private val context: Context,
    private val onCodecChanged: (String) -> Unit
) {
    private var bluetoothA2dp: BluetoothA2dp? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    var currentCodecName: String = ""
        private set

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            fetchCurrentCodec()
        }
    }

    private var isReceiverRegistered = false

    @SuppressLint("MissingPermission")
    fun start() {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = btManager?.adapter
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                    if (profile == BluetoothProfile.A2DP) {
                        bluetoothA2dp = proxy as? BluetoothA2dp
                        fetchCurrentCodec()
                    }
                }
                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) {
                        bluetoothA2dp = null
                        updateCodec("")
                    }
                }
            }, BluetoothProfile.A2DP)

            val filter = IntentFilter().apply {
                addAction("android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED")
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ContextCompat.registerReceiver(context, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("BluetoothCodecTracker", "Bluetooth tracker start error", e)
        }
    }

    fun stop() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(btReceiver)
            } catch (e: Exception) {}
            isReceiverRegistered = false
        }
        try {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dp)
        } catch (e: Exception) {}
        bluetoothA2dp = null
    }

    @SuppressLint("MissingPermission")
    fun fetchCurrentCodec(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return currentCodecName
            }
        }
        val a2dp = bluetoothA2dp
        if (a2dp == null) {
            updateCodec("")
            return ""
        }

        try {
            val devices = a2dp.connectedDevices
            if (devices.isNullOrEmpty()) {
                updateCodec("")
                return ""
            }
            val activeDev = devices[0]
            val getCodecStatusMethod = a2dp.javaClass.getMethod("getCodecStatus", BluetoothDevice::class.java)
            val codecStatus = getCodecStatusMethod.invoke(a2dp, activeDev)
            if (codecStatus != null) {
                val getCodecConfigMethod = codecStatus.javaClass.getMethod("getCodecConfig")
                val codecConfig = getCodecConfigMethod.invoke(codecStatus)
                if (codecConfig != null) {
                    val codec = parseComprehensiveCodec(codecConfig, codecStatus)
                    updateCodec(codec)
                    return codec
                }
            }
        } catch (e: SecurityException) {
            Log.w("BluetoothCodecTracker", "Bluetooth permission not granted")
        } catch (e: Exception) {
            updateCodec("BT HD Audio")
            return "BT HD Audio"
        }

        return currentCodecName
    }

    private fun updateCodec(codec: String) {
        if (currentCodecName != codec) {
            currentCodecName = codec
            onCodecChanged(codec)
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
}