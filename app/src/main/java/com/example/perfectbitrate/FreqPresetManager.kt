package com.example.perfectbitrate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import java.lang.ref.WeakReference
import kotlin.math.abs

data class FreqPresetDef(
    val id: Int,
    val name: String,
    var firType: Int,
    var transientMode: Int,
    var lpcAlgo: Int,
    var gain: Float,
    var extractFreq: Float,
    var useQmf: Boolean,
    var useGroupDelay: Boolean,
    var useLattice: Boolean,
    var useLrDither: Boolean,
    var useMsSpatial: Boolean,
    var useDynamicSbr: Boolean
)

object FreqPresetManager {

    val PRESET_NAMES = listOf("OFF", "Auto AI", "男性ボーカル", "女性ボーカル", "パーカッション", "ストリングス")

    val FIR_OPTIONS = arrayOf("Linear Phase Sharp", "Linear Phase Slow", "Minimum Phase Sharp", "Minimum Phase Slow")
    val TRANSIENT_OPTIONS = arrayOf("OFF", "Natural (CD)", "Punch (打楽器)", "Acoustic (弦・打弦)")
    val LPC_OPTIONS = arrayOf("DSEE HX AI (適応)", "K2 LPC Natural (物理)", "Adaptive Exciter (輪郭)")
    val GAIN_OPTIONS = arrayOf("0.16", "0.18", "0.20", "0.22", "0.24", "0.26")
    val GAIN_VALUES = floatArrayOf(0.16f, 0.18f, 0.20f, 0.22f, 0.24f, 0.26f)
    val FREQ_OPTIONS = arrayOf("10,000 Hz", "11,500 Hz", "12,000 Hz", "12,500 Hz", "13,000 Hz", "13,500 Hz", "13,800 Hz")
    val FREQ_VALUES = floatArrayOf(10000.0f, 11500.0f, 12000.0f, 12500.0f, 13000.0f, 13500.0f, 13800.0f)

    // ★ 280曲実測プロファイル完全適合プリセット初期値
    val DEFAULT_PRESETS = listOf(
        FreqPresetDef(0, "OFF", 0, 0, 0, 0.0f, 13000f, false, false, false, false, false, false),
        FreqPresetDef(1, "Auto AI", 2, 1, 0, 0.22f, 13000f, true, true, true, true, true, true),
        // 男性ボーカル: 格子型(Lattice), M/S空間, SBR をOFFにして太い芯と定位を最優先
        FreqPresetDef(2, "男性ボーカル", 2, 1, 0, 0.20f, 12000f, true, true, false, true, false, false),
        // 女性ボーカル: 格子型とSBRをOFF、サ行刺さり防止と残響エアーをON
        FreqPresetDef(3, "女性ボーカル", 2, 1, 0, 0.22f, 12500f, true, true, false, true, true, false),
        // パーカッション: アタック鈍化(-0.04dB)適合・QMFと空間拡散を排してパンチ最大化
        FreqPresetDef(4, "パーカッション", 2, 2, 2, 0.18f, 13800f, false, true, true, true, false, false),
        // ストリングス: 弦摩擦音・自然倍音K2・撥弦追従Lattice
        FreqPresetDef(5, "ストリングス", 2, 3, 1, 0.22f, 12500f, true, true, true, true, true, true)
    )

    val PRESETS = DEFAULT_PRESETS.map { it.copy() }.toMutableList()

    var currentPresetIndex = 1
        private set

    var onPresetChangedListener: ((Int, FreqPresetDef) -> Unit)? = null

    private var isSyncing = false
    private var isUpdatingUI = false // ★ UI更新中のリスナー誤爆完全遮断フラグ

    private var frontSpinnerRef: WeakReference<Spinner>? = null
    private var backSpinnerRef: WeakReference<Spinner>? = null
    private var backDialogViewRef: WeakReference<View>? = null

    fun setInitialPresetIndex(index: Int) {
        currentPresetIndex = index.coerceIn(0, PRESETS.size - 1)
    }

    fun getCurrentPreset(): FreqPresetDef = PRESETS[currentPresetIndex]

    fun applyCurrentPresetToNative() {
        applyPresetToNative(getCurrentPreset())
    }

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

            onPresetChangedListener?.invoke(safePos, preset)
        } finally {
            isSyncing = false
        }
    }

    fun updateDevDialogUI(dialogView: View?, p: FreqPresetDef) {
        if (dialogView == null) return
        
        val updateAction = Runnable {
            isUpdatingUI = true // ★ プログラムからの値反映中はリスナー発火を一切遮断！
            try {
                val isOff = (p.id == 0)
                val disableAlpha = if (isOff) 0.35f else 1.0f

                val targetSp = dialogView.findViewById<View>(R.id.spinnerTargetPreset) as? Spinner
                if (targetSp != null && targetSp.selectedItemPosition != p.id) {
                    targetSp.setSelection(p.id)
                }

                val spFir = dialogView.findViewById<View>(R.id.devSpinnerFir) as? Spinner
                spFir?.setSelection(p.firType.coerceIn(0, FIR_OPTIONS.size - 1))

                val transView = dialogView.findViewById<View>(R.id.devSpinnerTransient) as? Spinner
                val lpcView = dialogView.findViewById<View>(R.id.devSpinnerLpcAlgo) as? Spinner
                val gainView = dialogView.findViewById<View>(R.id.devSpinnerGain) as? Spinner
                val freqView = dialogView.findViewById<View>(R.id.devSpinnerExtractFreq) as? Spinner

                listOf(transView, lpcView, gainView, freqView).forEach { v ->
                    (v?.parent as? View)?.let {
                        it.isEnabled = !isOff
                        it.alpha = disableAlpha
                    }
                    v?.isEnabled = !isOff
                    v?.alpha = disableAlpha
                }

                lpcView?.setSelection(p.lpcAlgo.coerceIn(0, LPC_OPTIONS.size - 1))
                transView?.setSelection(p.transientMode.coerceIn(0, TRANSIENT_OPTIONS.size - 1))

                gainView?.let { sp ->
                    var best = 0
                    var minD = Float.MAX_VALUE
                    for (i in GAIN_VALUES.indices) {
                        val d = abs(GAIN_VALUES[i] - p.gain)
                        if (d < minD) {
                            minD = d
                            best = i
                        }
                    }
                    sp.setSelection(best)
                }

                freqView?.let { sp ->
                    var best = 0
                    var minD = Float.MAX_VALUE
                    for (i in FREQ_VALUES.indices) {
                        val d = abs(FREQ_VALUES[i] - p.extractFreq)
                        if (d < minD) {
                            minD = d
                            best = i
                        }
                    }
                    sp.setSelection(best)
                }

                (dialogView.findViewById<View>(R.id.devSwitchQmf) as? SwitchCompat)?.isChecked = p.useQmf
                (dialogView.findViewById<View>(R.id.devSwitchGroupDelay) as? SwitchCompat)?.isChecked = p.useGroupDelay
                (dialogView.findViewById<View>(R.id.devSwitchLattice) as? SwitchCompat)?.isChecked = p.useLattice
                (dialogView.findViewById<View>(R.id.devSwitchLrDither) as? SwitchCompat)?.isChecked = p.useLrDither
                (dialogView.findViewById<View>(R.id.devSwitchMsSpatial) as? SwitchCompat)?.isChecked = p.useMsSpatial
                (dialogView.findViewById<View>(R.id.devSwitchDynamicSbr) as? SwitchCompat)?.isChecked = p.useDynamicSbr
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isUpdatingUI = false // ★ 値反映が完了したらガードを解除
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateAction.run()
        } else {
            Handler(Looper.getMainLooper()).post(updateAction)
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
            val ctx = dialogView.context

            fun setupAdapter(spId: Int, items: Array<String>): Spinner? {
                val sp = dialogView.findViewById<View>(spId) as? Spinner ?: return null
                val ad = ArrayAdapter(ctx, R.layout.item_spinner_dap, items)
                ad.setDropDownViewResource(R.layout.item_spinner_dap)
                sp.adapter = ad
                return sp
            }

            val backSp = dialogView.findViewById<View>(R.id.spinnerTargetPreset) as? Spinner
            if (backSp != null) {
                val ad = ArrayAdapter(ctx, R.layout.item_spinner_dap, PRESET_NAMES)
                ad.setDropDownViewResource(R.layout.item_spinner_dap)
                backSp.adapter = ad
                backSp.setSelection(currentPresetIndex)
                backSpinnerRef = WeakReference(backSp)
            }

            val devSpFir = setupAdapter(R.id.devSpinnerFir, FIR_OPTIONS)
            val devSpTrans = setupAdapter(R.id.devSpinnerTransient, TRANSIENT_OPTIONS)
            val devSpLpc = setupAdapter(R.id.devSpinnerLpcAlgo, LPC_OPTIONS)
            val devSpGain = setupAdapter(R.id.devSpinnerGain, GAIN_OPTIONS)
            val devSpFreq = setupAdapter(R.id.devSpinnerExtractFreq, FREQ_OPTIONS)

            backDialogViewRef = WeakReference(dialogView)

            backSp?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    onPresetChanged(pos)
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }

            // ★ ユーザーが指で手動操作した時のみ保存・適用する（プログラムによる自動設定時はガード）
            fun saveAndApplyFromUI() {
                if (isSyncing || isUpdatingUI) return
                val p = getCurrentPreset()
                if (p.id == 0) return

                devSpFir?.let { p.firType = it.selectedItemPosition }
                devSpTrans?.let { p.transientMode = it.selectedItemPosition }
                devSpLpc?.let { p.lpcAlgo = it.selectedItemPosition }
                devSpGain?.let { p.gain = GAIN_VALUES[it.selectedItemPosition.coerceIn(0, GAIN_VALUES.size - 1)] }
                devSpFreq?.let { p.extractFreq = FREQ_VALUES[it.selectedItemPosition.coerceIn(0, FREQ_VALUES.size - 1)] }

                (dialogView.findViewById<View>(R.id.devSwitchQmf) as? SwitchCompat)?.let { p.useQmf = it.isChecked }
                (dialogView.findViewById<View>(R.id.devSwitchGroupDelay) as? SwitchCompat)?.let { p.useGroupDelay = it.isChecked }
                (dialogView.findViewById<View>(R.id.devSwitchLattice) as? SwitchCompat)?.let { p.useLattice = it.isChecked }
                (dialogView.findViewById<View>(R.id.devSwitchLrDither) as? SwitchCompat)?.let { p.useLrDither = it.isChecked }
                (dialogView.findViewById<View>(R.id.devSwitchMsSpatial) as? SwitchCompat)?.let { p.useMsSpatial = it.isChecked }
                (dialogView.findViewById<View>(R.id.devSwitchDynamicSbr) as? SwitchCompat)?.let { p.useDynamicSbr = it.isChecked }

                applyPresetToNative(p)
            }

            val itemListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, v: View?, p2: Int, p3: Long) { saveAndApplyFromUI() }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
            devSpFir?.onItemSelectedListener = itemListener
            devSpTrans?.onItemSelectedListener = itemListener
            devSpLpc?.onItemSelectedListener = itemListener
            devSpGain?.onItemSelectedListener = itemListener
            devSpFreq?.onItemSelectedListener = itemListener

            listOf(
                R.id.devSwitchQmf, R.id.devSwitchGroupDelay, R.id.devSwitchLattice,
                R.id.devSwitchLrDither, R.id.devSwitchMsSpatial, R.id.devSwitchDynamicSbr
            ).forEach { id ->
                (dialogView.findViewById<View>(id) as? SwitchCompat)?.setOnCheckedChangeListener { _, _ ->
                    saveAndApplyFromUI()
                }
            }

            dialogView.findViewById<Button>(R.id.btnDevCopyConfig)?.setOnClickListener {
                val sb = StringBuilder()
                sb.append("// ========================================================\n")
                sb.append("// ★ 実測最適化プリセット設定コード\n")
                sb.append("// ========================================================\n")
                PRESETS.forEach { pr ->
                    sb.append("FreqPresetDef(${pr.id}, \"${pr.name}\", ${pr.firType}, ${pr.transientMode}, ${pr.lpcAlgo}, ${pr.gain}f, ${pr.extractFreq}f, ${pr.useQmf}, ${pr.useGroupDelay}, ${pr.useLattice}, ${pr.useLrDither}, ${pr.useMsSpatial}, ${pr.useDynamicSbr}),\n")
                }
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("PresetConfig", sb.toString()))
                Toast.makeText(ctx, "全プリセット設定コードをコピーしました！", Toast.LENGTH_SHORT).show()
            }

            dialogView.findViewById<Button>(R.id.btnDevResetDefault)?.setOnClickListener {
                for (i in DEFAULT_PRESETS.indices) {
                    PRESETS[i] = DEFAULT_PRESETS[i].copy()
                }
                updateDevDialogUI(dialogView, getCurrentPreset())
                applyCurrentPresetToNative()
                Toast.makeText(ctx, "初期値にリセットしました", Toast.LENGTH_SHORT).show()
            }

            updateDevDialogUI(dialogView, getCurrentPreset())
        }
    }

    fun clearFrontDialogRefs() {
        frontSpinnerRef = null
    }

    fun clearDevDialogRefs() {
        backSpinnerRef = null
        backDialogViewRef = null
    }
}