package com.example.perfectbitrate

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import java.lang.ref.WeakReference
import kotlin.math.abs

data class FreqPresetDef(
    val id: Int,
    val name: String,
    val firType: Int,
    val transientMode: Int,
    val lpcAlgo: Int,
    val gain: Float,
    val extractFreq: Float,
    val useQmf: Boolean,
    val useGroupDelay: Boolean,
    val useLattice: Boolean,
    val useLrDither: Boolean,
    val useMsSpatial: Boolean,
    val useDynamicSbr: Boolean
)

object FreqPresetManager {

    val PRESET_NAMES = listOf("OFF", "Auto AI", "男性ボーカル", "女性ボーカル", "パーカッション", "ストリングス")

    // ★ 280曲実測プロファイル完全適合プリセット
    val PRESETS = listOf(
        // 0: OFF (Linear Phase Sharp確実適用・全DSPバイパス)
        FreqPresetDef(0, "OFF", 0, 0, 0, 0.0f, 13000f, false, false, false, false, false, false),
        // 1: Auto AI (Min Sharp・19.8kHzクロスオーバー・自然減衰-4.01dB/kHz)
        FreqPresetDef(1, "Auto AI", 2, 1, 0, 0.22f, 13000f, true, true, true, true, true, true),
        // 2: 男性ボーカル (12k息成分・直接音Midの位相を強力ロック)
        FreqPresetDef(2, "男性ボーカル", 2, 1, 0, 0.20f, 12000f, true, true, false, true, false, false),
        // 3: 女性ボーカル (12.5kブレス艶・刺さりゼロ・自然な空間エアー)
        FreqPresetDef(3, "女性ボーカル", 2, 1, 0, 0.22f, 12500f, true, true, false, true, true, false),
        // 4: パーカッション (13.8kシンバル帯域・アタック鈍化-0.04dB適合)
        FreqPresetDef(4, "パーカッション", 2, 2, 2, 0.18f, 13800f, false, true, true, true, false, false),
        // 5: ストリングス (12.5k弦倍音・自然減衰K2 LPC)
        FreqPresetDef(5, "ストリングス", 2, 3, 1, 0.22f, 12500f, true, true, true, true, true, true)
    )

    var currentPresetIndex = 0
    private var isSyncing = false

    private var frontSpinnerRef: WeakReference<Spinner>? = null
    private var backSpinnerRef: WeakReference<Spinner>? = null
    private var backDialogViewRef: WeakReference<View>? = null

    fun applyPresetToNative(p: FreqPresetDef) {
        if (p.id == 0) {
            NativeAudioEngine.nativeSetFirFilterType(0)
            NativeAudioEngine.nativeSetFreqMode(0)
            NativeAudioEngine.nativeSetTransientMode(0)
            NativeAudioEngine.nativeSetMsSpatial(false)
            NativeAudioEngine.nativeSetDynamicSbr(false)
            NativeAudioEngine.nativeSetTransientCustomParams(false, false)
            NativeAudioEngine.nativeSetFreqCustomParams(0.0f, 13000.0f)
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

    fun onPresetChanged(position: Int) {
        if (isSyncing) return
        isSyncing = true
        try {
            val safePos = position.coerceIn(0, PRESETS.size - 1)
            currentPresetIndex = safePos
            val preset = PRESETS[safePos]

            applyPresetToNative(preset)

            frontSpinnerRef?.get()?.let { sp ->
                if (sp.selectedItemPosition != safePos) {
                    sp.setSelection(safePos)
                }
            }

            backSpinnerRef?.get()?.let { sp ->
                if (sp.selectedItemPosition != safePos) {
                    sp.setSelection(safePos)
                }
            }

            backDialogViewRef?.get()?.let { view ->
                updateDevDialogUI(view, preset)
            }
        } finally {
            isSyncing = false
        }
    }

    fun updateDevDialogUI(dialogView: View?, p: FreqPresetDef) {
        if (dialogView == null) return
        dialogView.post {
            try {
                val isOff = (p.id == 0)
                val disableAlpha = if (isOff) 0.35f else 1.0f

                val targetSp = dialogView.findViewById<View>(R.id.spinnerTargetPreset) as? Spinner
                if (targetSp != null && targetSp.selectedItemPosition != p.id) {
                    targetSp.setSelection(p.id)
                }

                (dialogView.findViewById<View>(R.id.devSpinnerFir) as? Spinner)?.setSelection(p.firType)

                dialogView.findViewById<View>(R.id.devSpinnerLpcAlgo)?.apply {
                    isEnabled = !isOff
                    alpha = disableAlpha
                    (this as? Spinner)?.setSelection(p.lpcAlgo)
                }

                dialogView.findViewById<View>(R.id.devSpinnerTransient)?.apply {
                    isEnabled = !isOff
                    alpha = disableAlpha
                    (this as? Spinner)?.setSelection(p.transientMode)
                }

                dialogView.findViewById<View>(R.id.devSpinnerGain)?.apply {
                    isEnabled = !isOff
                    alpha = disableAlpha
                    (this as? Spinner)?.let { sp ->
                        val ad = sp.adapter ?: return@let
                        var best = 0
                        var minD = Float.MAX_VALUE
                        for (i in 0 until ad.count) {
                            val num = Regex("""\d+(\.\d+)?""").find(ad.getItem(i).toString())?.value?.toFloatOrNull()
                            if (num != null && abs(num - p.gain) < minD) {
                                minD = abs(num - p.gain)
                                best = i
                            }
                        }
                        sp.setSelection(best)
                    }
                }

                dialogView.findViewById<View>(R.id.devSpinnerExtractFreq)?.apply {
                    isEnabled = !isOff
                    alpha = disableAlpha
                    (this as? Spinner)?.let { sp ->
                        val ad = sp.adapter ?: return@let
                        var best = 0
                        var minD = Float.MAX_VALUE
                        for (i in 0 until ad.count) {
                            val cleanStr = ad.getItem(i).toString().replace(",", "")
                            val num = Regex("""\d+""").find(cleanStr)?.value?.toFloatOrNull()
                            if (num != null && abs(num - p.extractFreq) < minD) {
                                minD = abs(num - p.extractFreq)
                                best = i
                            }
                        }
                        sp.setSelection(best)
                    }
                }

                (dialogView.findViewById<View>(R.id.devSwitchQmf) as? SwitchCompat)?.isChecked = p.useQmf
                (dialogView.findViewById<View>(R.id.devSwitchGroupDelay) as? SwitchCompat)?.isChecked = p.useGroupDelay
                (dialogView.findViewById<View>(R.id.devSwitchLattice) as? SwitchCompat)?.isChecked = p.useLattice
                (dialogView.findViewById<View>(R.id.devSwitchLrDither) as? SwitchCompat)?.isChecked = p.useLrDither
                (dialogView.findViewById<View>(R.id.devSwitchMsSpatial) as? SwitchCompat)?.isChecked = p.useMsSpatial
                (dialogView.findViewById<View>(R.id.devSwitchDynamicSbr) as? SwitchCompat)?.isChecked = p.useDynamicSbr
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hookDspSettingsDialog(dialogView: View?) {
        if (dialogView == null) return
        dialogView.post {
            val frontSp = dialogView.findViewById<View>(R.id.dialogSpinnerDsee) as? Spinner ?: return@post
            frontSpinnerRef = WeakReference(frontSp)

            val ad = ArrayAdapter(dialogView.context, R.layout.item_spinner_dap, PRESET_NAMES)
            ad.setDropDownViewResource(R.layout.item_spinner_dap)
            frontSp.adapter = ad
            frontSp.setSelection(currentPresetIndex)

            frontSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onPresetChanged(pos)
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
    }

    fun hookDevPresetsDialog(dialogView: View?) {
        if (dialogView == null) return
        dialogView.post {
            val backSp = dialogView.findViewById<View>(R.id.spinnerTargetPreset) as? Spinner ?: return@post
            backSpinnerRef = WeakReference(backSp)
            backDialogViewRef = WeakReference(dialogView)

            val ad = ArrayAdapter(dialogView.context, R.layout.item_spinner_dap, PRESET_NAMES)
            ad.setDropDownViewResource(R.layout.item_spinner_dap)
            backSp.adapter = ad
            backSp.setSelection(currentPresetIndex)

            backSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onPresetChanged(pos)
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

            updateDevDialogUI(dialogView, PRESETS[currentPresetIndex])
        }
    }
}