package com.example.perfectbitrate

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import java.lang.ref.WeakReference
import kotlin.math.abs

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

object FreqPresetManager {

    val PRESET_NAMES = listOf("OFF", "Auto AI", "男性ボーカル", "女性ボーカル", "パーカッション", "ストリングス")

    val PRESETS = listOf(
        // 0: OFF (Linear Phase Sharp確実適用・全DSP停止・全スイッチ完全消灯)
        FreqPresetDef(0, "OFF", 0, 0, 0, 0.0f, 10000f, false, false, false, false, false, false),
        // 1: Auto AI (Min Sharp・全DSPフル点灯・全スイッチON)
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

    var currentPresetIndex = 0 // 初期値: OFF
    private var isSyncing = false

    private var frontSpinnerRef: WeakReference<Spinner>? = null
    private var backSpinnerRef: WeakReference<Spinner>? = null
    private var backDialogViewRef: WeakReference<View>? = null

    /**
     * Native C++ DSP エンジンへ即座に反映 (OFF時も確実に Linear Phase を送信)
     */
    fun applyPresetToNative(p: FreqPresetDef) {
        if (p.id == 0) {
            NativeAudioEngine.nativeSetFirFilterType(0) // Linear Phase Sharp (直線位相)
            NativeAudioEngine.nativeSetFreqMode(0)      // DSEE HX / 倍音復元バイパス停止
            NativeAudioEngine.nativeSetTransientMode(0) // トランジェント停止
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
     * 表または裏のスピナーが変更されたときの共通処理（完全リンク＆パラメータ自動変化）
     */
    fun onPresetChanged(position: Int) {
        if (isSyncing) return
        isSyncing = true
        try {
            val safePos = position.coerceIn(0, PRESETS.size - 1)
            currentPresetIndex = safePos
            val preset = PRESETS[safePos]

            // 1. Native DSP へ反映
            applyPresetToNative(preset)

            // 2. 表スピナーの選択位置を同期
            frontSpinnerRef?.get()?.let { sp ->
                if (sp.selectedItemPosition != safePos) {
                    sp.setSelection(safePos)
                }
            }

            // 3. 裏スピナーの選択位置を同期
            backSpinnerRef?.get()?.let { sp ->
                if (sp.selectedItemPosition != safePos) {
                    sp.setSelection(safePos)
                }
            }

            // 4. 裏ダイアログの下部パラメータ・スイッチ6種を自動変化！
            backDialogViewRef?.get()?.let { view ->
                updateDevDialogUI(view, preset)
            }
        } finally {
            isSyncing = false
        }
    }

    /**
     * 裏設定ダイアログのUI更新（OFFなら全消灯、各プリセットで自動変化、OFF時は倍音スピナーグレーアウト）
     */
    fun updateDevDialogUI(dialogView: View?, p: FreqPresetDef) {
        if (dialogView == null) return
        dialogView.post {
            try {
                val isOff = (p.id == 0)
                val disableAlpha = if (isOff) 0.35f else 1.0f

                // 1. 最上部 TARGET PRESET スピナー
                val targetSp = dialogView.findViewById<View>(R.id.spinnerTargetPreset) as? Spinner
                if (targetSp != null && targetSp.selectedItemPosition != p.id) {
                    targetSp.setSelection(p.id)
                }

                // 2. FIR フィルター スピナー (OFF時: Linear Phase Sharp 0)
                (dialogView.findViewById<View>(R.id.devSpinnerFir) as? Spinner)?.setSelection(p.firType)

                // 3. OFF時は倍音復元系スピナーをグレーアウト非活性化
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

                // 4. ★ スイッチ6種 (OFFなら全てOFF完全消灯！各プリセットなら自動連動！)
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

    /**
     * 表設定ダイアログ (dialog_dsp_settings) をフック
     */
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

    /**
     * 裏設定ダイアログ (dialog_dev_presets) をフック
     */
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

            // 開いた瞬間に、現在の設定値で下の全パラメータを強制更新！
            updateDevDialogUI(dialogView, PRESETS[currentPresetIndex])
        }
    }
}