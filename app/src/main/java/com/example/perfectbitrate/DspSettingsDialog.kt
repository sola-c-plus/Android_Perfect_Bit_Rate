package com.example.perfectbitrate

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.UUID

class DspSettingsDialog(
    private val activity: Activity,
    private val isDarkTheme: Boolean,
    private val topPanelHeight: Int,
    private val activeOutputDevice: AudioDeviceInfo?,
    private val isVolumeLocked: Boolean,
    private val onVolumeLockChanged: (Boolean) -> Unit,
    private val onBitModeChanged: (String) -> Unit,
    private val onUpsampleFactorChanged: (Int) -> Unit,
    private val onDirectSourceChanged: (Boolean) -> Unit,
    private val onPlayerCommand: (String) -> Unit,
    private val onSeekTo: (Long) -> Unit,
    private val onDismiss: () -> Unit
) : PlayerDialogController {

    private var walkmanEqView: WalkmanEqView? = null
    private var textTrackTitle: TextView? = null
    private var textTrackArtist: TextView? = null
    private var textCurrentTime: TextView? = null
    private var textTotalTime: TextView? = null
    private var imageArtwork: ImageView? = null
    private var seekBar: SeekBar? = null
    private var btnPlayPause: ImageButton? = null

    // ★ PR-C1: 行化した 12 セクションのビルダー（閉じるときに破棄する）
    private var dspRows: DspSettingsRows? = null
    private var spinnerEqPreset: Spinner? = null
    private var btnEqDelete: Button? = null

    private var isUserSeeking = false
    private var currentDuration = 0L
    private var isApplyingPreset = false

    private val bitOptions = arrayOf("16-bit (Std)", "24-bit (Hi-Res)", "32-bit (Int32)")
    private val bitModeValues = arrayOf("16bit", "24bit", "32bit")

    private val upsampleOptions = arrayOf("1x Direct (Bypass)", "2x Hi-Res (96k/88k)", "4x Ultra (192k/176k)", "8x Master (384k/352k)")
    private val upsampleFactorValues = arrayOf(1, 2, 4, 8)

    private val ditherOptions = arrayOf("TPDF (Studio Standard)", "High-Pass Shaped (Clear)", "Psychoacoustic (Walkman SBM)", "None (Direct Bypass)")
    private val ditherModeValues = arrayOf(1, 2, 3, 0)

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

    private val perfModeOptions = arrayOf("Eco (省電力)", "普通 (標準)", "超高音質 (フルスペック)")

    private val truePeakCeilingOptions = arrayOf("0 dBTP", "-0.5 dBTP", "-1.0 dBTP (推奨)", "-2.0 dBTP")

    // ★ DSD 変換レート: 選択肢の正は AppPreferences.DSD_PCM_RATE_CHOICES。
    //   ラベルは元の設定画面と同じ書式（"88.2kHz" 等）に揃える。
    private val dsdRateValues = AppPreferences.DSD_PCM_RATE_CHOICES
    private val dsdRateOptions = dsdRateValues.map {
        String.format(java.util.Locale.US, "%.1fkHz", it / 1000.0)
    }.toTypedArray()

    data class BuiltinEqPreset(val id: String, val name: String, val gains: FloatArray)

    private val builtinPresets = listOf(
        BuiltinEqPreset("builtin:Flat", "Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        BuiltinEqPreset("builtin:Rock", "Rock", floatArrayOf(3.5f, 2.0f, 1.0f, -0.5f, -1.5f, -1.0f, 0.5f, 2.0f, 3.0f, 3.5f)),
        BuiltinEqPreset("builtin:Pop", "Pop", floatArrayOf(-1.0f, 0.0f, 1.5f, 2.5f, 3.0f, 2.0f, 1.0f, 0.5f, 1.5f, 2.0f)),
        BuiltinEqPreset("builtin:Jazz", "Jazz", floatArrayOf(2.5f, 1.5f, 0.5f, 1.0f, -1.0f, -1.0f, 0.0f, 1.0f, 2.0f, 2.5f)),
        BuiltinEqPreset("builtin:Classical", "Classical", floatArrayOf(3.0f, 2.0f, 1.5f, 1.0f, -1.0f, -1.0f, 0.0f, 1.5f, 2.5f, 3.0f)),
        BuiltinEqPreset("builtin:BassBoost", "Bass Boost", floatArrayOf(4.5f, 3.5f, 2.0f, 0.5f, -0.5f, -1.0f, -1.0f, -0.5f, 0.0f, 0.0f)),
        BuiltinEqPreset("builtin:Vocal", "Vocal", floatArrayOf(-2.0f, -1.5f, -0.5f, 1.5f, 3.0f, 3.0f, 2.0f, 0.5f, -0.5f, -1.5f)),
        BuiltinEqPreset("builtin:TrebleBoost", "Treble Boost", floatArrayOf(-1.0f, -1.0f, -0.5f, 0.0f, 0.0f, 0.5f, 1.5f, 2.5f, 3.5f, 4.5f))
    )

    private var savedCustomPresets = mutableListOf<CustomEqPreset>()

    sealed class EqSpinnerItem(val id: String, val displayName: String) {
        class Builtin(val preset: BuiltinEqPreset) : EqSpinnerItem(preset.id, preset.name)
        object WorkingCustom : EqSpinnerItem("custom:working", "Custom")
        class SavedCustom(val preset: CustomEqPreset) : EqSpinnerItem("custom:${preset.id}", preset.name)
    }

    private var spinnerItemList = mutableListOf<EqSpinnerItem>()

    fun show() {
        val bottomSheetDialog = BottomSheetDialog(activity, R.style.CustomBottomSheetDialogTheme).apply {
            window?.setDimAmount(0f)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val view = activity.layoutInflater.inflate(R.layout.dialog_dsp_settings, null)
        bottomSheetDialog.setContentView(view)

        val btnClose = view.findViewById<ImageButton>(R.id.btnDspDialogClose)
        val switchEqEnable = view.findViewById<SwitchCompat>(R.id.switchEqEnable)
        val switchSpectrumEnable = view.findViewById<SwitchCompat>(R.id.switchSpectrumEnable)

        seekBar = view.findViewById(R.id.dialogSeekBar)
        btnPlayPause = view.findViewById(R.id.dialogBtnPlayPause)
        val btnPrev = view.findViewById<ImageButton>(R.id.dialogBtnPrevTrack)
        val btnNext = view.findViewById<ImageButton>(R.id.dialogBtnNextTrack)

        val btnEqPlus = view.findViewById<ImageButton>(R.id.btnEqPlus)
        val btnEqMinus = view.findViewById<ImageButton>(R.id.btnEqMinus)
        val btnEqFlat = view.findViewById<Button>(R.id.btnEqFlat)
        val btnEqEdit = view.findViewById<Button>(R.id.btnEqEdit)
        val btnEqSave = view.findViewById<Button>(R.id.btnEqSave)
        btnEqDelete = view.findViewById(R.id.btnEqDelete)

        walkmanEqView = view.findViewById(R.id.walkmanEqView)
        val textEqBandFreq = view.findViewById<TextView>(R.id.textEqBandFreq)
        val textEqGainValue = view.findViewById<TextView>(R.id.textEqGainValue)
        val layoutEqAdjustControls = view.findViewById<View>(R.id.layoutEqAdjustControls)

        val layoutSectionEq = view.findViewById<View>(R.id.layoutSectionEq)
        spinnerEqPreset = view.findViewById(R.id.dialogSpinnerEqPreset)

        imageArtwork = view.findViewById(R.id.dialogImageArtwork)
        textTrackTitle = view.findViewById(R.id.dialogTextTrackTitle)
        textTrackArtist = view.findViewById(R.id.dialogTextTrackArtist)
        textCurrentTime = view.findViewById(R.id.dialogTextCurrentTime)
        textTotalTime = view.findViewById(R.id.dialogTextTotalTime)

        val appPrefs = AppPreferences.get()
        savedCustomPresets = appPrefs.getSavedCustomEqPresets()

        if (!isDarkTheme) {
            // ★ PR-C1: 行（DspSettingsRows）は構築時に isDark で色が確定する
            //   ため、ライト付け替えの対象は EQ ブロックとミニプレイヤーだけ
            //   になった（行はこの後で構築する）。
            applyLightModeStyle(
                view, btnClose, switchEqEnable, switchSpectrumEnable,
                btnEqEdit, btnEqSave, btnEqDelete!!, btnEqFlat, btnEqPlus, btnEqMinus,
                textEqBandFreq, textEqGainValue, spinnerEqPreset!!, btnPrev, btnNext, btnPlayPause!!
            )
        } else {
            // ★ PR-C: ダーク時も Spinner ドロップダウンの枠をシート地と同系に
            //   揃える（従来はフレームワーク既定）。ライト時は
            //   applyLightModeStyle 側で専用 drawable（_light）を当てる。
            //   PR-C1 で残る Spinner は EQ プリセットのみ（他は行化した）。
            spinnerEqPreset?.setPopupBackgroundResource(R.drawable.bg_spinner_popup_dap)
        }

        bottomSheetDialog.setOnShowListener {
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) as? FrameLayout
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                val screenH = activity.resources.displayMetrics.heightPixels
                val targetH = screenH - topPanelHeight
                if (targetH > 0) {
                    val lp = bottomSheet.layoutParams
                    lp.height = targetH
                    bottomSheet.layoutParams = lp
                    behavior.peekHeight = targetH
                }
            }
        }

        // ★ PR-C1: RICH HARMONICS / TRUE PEAK GUARD（スイッチ＋天井）の
        //   初期反映とリスナーは、行の構築側（switchRow / valueRow の
        //   initial / onChange / isEnabled）へ移った。天井行の有効条件は
        //   「非 Direct かつ TP スイッチ ON」を isEnabled ラムダで宣言し、
        //   refreshStates() が減光（alpha 0.35）込みで再評価する。

        walkmanEqView?.isLightMode = !isDarkTheme
        walkmanEqView?.isSpectrumEnabled = appPrefs.isSpectrumEnabled
        switchSpectrumEnable.isChecked = appPrefs.isSpectrumEnabled
        switchSpectrumEnable.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.isSpectrumEnabled = isChecked
            walkmanEqView?.isSpectrumEnabled = isChecked
        }

        fun updateDeleteButtonState() {
            val isCustomSaved = appPrefs.selectedEqPresetId.startsWith("custom:") && appPrefs.selectedEqPresetId != "custom:working"
            val isEqActive = !appPrefs.isDirectSource && switchEqEnable.isChecked
            btnEqDelete?.isEnabled = isEqActive && isCustomSaved
            btnEqDelete?.alpha = if (isEqActive && isCustomSaved) 1.0f else 0.35f
        }

        fun updateEqPresetState(isDirect: Boolean, eqEnabled: Boolean) {
            val isPresetActive = !isDirect && eqEnabled
            spinnerEqPreset?.isEnabled = isPresetActive
            spinnerEqPreset?.alpha = if (isPresetActive) 1.0f else 0.35f
            btnEqSave?.isEnabled = isPresetActive
            btnEqSave?.alpha = if (isPresetActive) 1.0f else 0.35f
            updateDeleteButtonState()
        }

        // ★ PR-C1: 行化した 12 セクションの有効/無効は DspSettingsRows の
        //   isEnabled ラムダ＋refreshStates() が担う。本関数は残った
        //   EQ ブロック側の減光・無効化だけを受け持つ（状態の正は
        //   従来どおり Prefs。呼び出し側は Prefs を更新してから呼ぶ）。
        fun updateDspSectionsState() {
            val isDirect = appPrefs.isDirectSource
            val dspAlpha = if (isDirect) 0.3f else 1.0f
            val dspEnabled = !isDirect

            layoutSectionEq.alpha = dspAlpha

            switchEqEnable.isEnabled = dspEnabled
            switchSpectrumEnable.isEnabled = dspEnabled
            btnEqEdit.isEnabled = dspEnabled
            btnEqFlat.isEnabled = dspEnabled
            btnEqSave?.isEnabled = dspEnabled
            btnEqPlus.isEnabled = dspEnabled
            btnEqMinus.isEnabled = dspEnabled
            walkmanEqView?.isEnabled = dspEnabled

            dspRows?.refreshStates()
            updateEqPresetState(isDirect, switchEqEnable.isChecked)
        }

        // ★ PR-C1: 12 セクションを行化してコンテナへ構築する。行の
        //   isEnabled ラムダが相互依存（Direct 時の無効化、倍率 <2 での
        //   FREQ/CASCADE 無効化、PERF/RICH の二重条件、天井行の TP
        //   スイッチ連動、VOL LOCK の USB 条件）を宣言し、状態変更の
        //   たびに refreshStates() で再評価する。有効倍率は従来どおり
        //   「Direct 時は 1、それ以外は Prefs の選択倍率」。選択肢の
        //   ラベル・保存先・反映経路は従来の Spinner と同一。
        val container = view.findViewById<LinearLayout>(R.id.dspSectionsContainer)
        val rows = DspSettingsRows(activity, container, isDarkTheme)
        dspRows = rows

        fun effectiveFactor(): Int =
            if (appPrefs.isDirectSource) 1 else appPrefs.selectedUpsampleFactor
        fun isFreqEngineActive(): Boolean =
            !appPrefs.isDirectSource && effectiveFactor() >= 2

        var directSwitch: SwitchCompat? = null
        directSwitch = rows.switchRow(
            title = "DIRECT SOURCE",
            description = "全DSPを無効化しダイレクト送信します",
            initial = appPrefs.isDirectSource,
            onChange = { isChecked ->
                // 切替直後 700ms は二重発火防止のためトグルを無効化する
                //   （従来のガードを行版に読み替え。時間経過後は
                //   refreshStates() が有効状態を復元する）。
                directSwitch?.let { rows.holdSwitchDisabled(it, 700L) }
                appPrefs.isDirectSource = isChecked
                NativeAudioEngine.nativeSetDirectSource(isChecked)
                updateDspSectionsState()
                onDirectSourceChanged(isChecked)
            }
        )

        // EQ ブロックは XML のままコンテナ内に残っているため、DIRECT 行の
        // 直後（従来位置）へ差し戻す。以後の行はその後ろへ積まれる。
        container.removeView(layoutSectionEq)
        container.addView(layoutSectionEq, 1)

        rows.categoryHeader("再生処理")

        rows.valueRow(
            title = "BIT DEPTH FORMAT",
            description = "DACへ伝送するPCMビット深度",
            currentValue = {
                bitOptions[bitModeValues.indexOf(appPrefs.selectedBitMode).coerceAtLeast(0)]
            },
            // ビット深度は要求値（Prefs）の編集のため Direct 時も無効化しない
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "BIT DEPTH FORMAT", bitOptions.toList(),
                    bitModeValues.indexOf(appPrefs.selectedBitMode).coerceAtLeast(0)
                ) { pos ->
                    val selectedMode = bitModeValues[pos]
                    if (selectedMode != appPrefs.selectedBitMode) {
                        appPrefs.selectedBitMode = selectedMode
                        onBitModeChanged(selectedMode)
                    }
                    rows.refreshStates()
                }
            }
        )

        rows.valueRow(
            title = "DITHERING ALGORITHM",
            description = "量子化ノイズ分散・SBMシェービング",
            currentValue = {
                ditherOptions[ditherModeValues.indexOf(appPrefs.selectedDitherMode).coerceAtLeast(0)]
            },
            isEnabled = { !appPrefs.isDirectSource },
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "DITHERING ALGORITHM", ditherOptions.toList(),
                    ditherModeValues.indexOf(appPrefs.selectedDitherMode).coerceAtLeast(0)
                ) { pos ->
                    val selectedMode = ditherModeValues[pos]
                    if (selectedMode != appPrefs.selectedDitherMode) {
                        appPrefs.selectedDitherMode = selectedMode
                        NativeAudioEngine.nativeSetDitherMode(selectedMode)
                    }
                    rows.refreshStates()
                }
            }
        )

        rows.switchRow(
            title = "TRUE PEAK GUARD (サンプル間ピーク保護)",
            description = "補間で生じるピーク超過を先読みで抑制 (Direct時は無効)",
            initial = appPrefs.isTruePeakGuardEnabled,
            isEnabled = { !appPrefs.isDirectSource },
            onChange = { isChecked ->
                appPrefs.isTruePeakGuardEnabled = isChecked
                NativeAudioEngine.nativeSetTruePeakGuard(isChecked, appPrefs.truePeakCeilingDb())
                rows.refreshStates()
            }
        )

        rows.valueRow(
            title = "天井 (CEILING)",
            description = null,
            currentValue = {
                truePeakCeilingOptions[
                    appPrefs.selectedTruePeakCeiling.coerceIn(0, truePeakCeilingOptions.lastIndex)
                ]
            },
            isEnabled = { !appPrefs.isDirectSource && appPrefs.isTruePeakGuardEnabled },
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "天井 (CEILING)", truePeakCeilingOptions.toList(),
                    appPrefs.selectedTruePeakCeiling.coerceIn(0, truePeakCeilingOptions.lastIndex)
                ) { pos ->
                    if (appPrefs.selectedTruePeakCeiling != pos) {
                        appPrefs.selectedTruePeakCeiling = pos
                        NativeAudioEngine.nativeSetTruePeakGuard(
                            appPrefs.isTruePeakGuardEnabled, appPrefs.truePeakCeilingDb()
                        )
                    }
                    rows.refreshStates()
                }
            }
        )

        rows.valueRow(
            title = "DC PHASE LINEARIZER",
            description = "アナログアンプ低域位相 (A/B Curve 2/4/8Hz)",
            currentValue = {
                dcPhaseOptions[dcPhaseTypeValues.indexOf(appPrefs.selectedDcPhaseType).coerceAtLeast(0)]
            },
            isEnabled = { !appPrefs.isDirectSource },
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "DC PHASE LINEARIZER", dcPhaseOptions.toList(),
                    dcPhaseTypeValues.indexOf(appPrefs.selectedDcPhaseType).coerceAtLeast(0)
                ) { pos ->
                    val selectedType = dcPhaseTypeValues[pos]
                    if (selectedType != appPrefs.selectedDcPhaseType) {
                        appPrefs.selectedDcPhaseType = selectedType
                        NativeAudioEngine.nativeSetDcPhaseType(selectedType)
                    }
                    rows.refreshStates()
                }
            }
        )

        // ★ FREQ ENGINE の実体は FreqPresetManager のプリセット選択。
        //   現在値は manager の currentPresetIndex から引き、選択は
        //   onPresetChanged() へ委ねる（native 適用は manager 側）。
        rows.valueRow(
            title = "FREQ ENGINE (倍音復元)",
            description = "直交多項式による20k〜40kHz+超高域倍音補完",
            currentValue = {
                FreqPresetManager.PRESET_NAMES[
                    FreqPresetManager.currentPresetIndex.coerceIn(
                        0, FreqPresetManager.PRESET_NAMES.lastIndex
                    )
                ]
            },
            isEnabled = { isFreqEngineActive() },
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "FREQ ENGINE (倍音復元)", FreqPresetManager.PRESET_NAMES,
                    FreqPresetManager.currentPresetIndex
                ) { pos ->
                    FreqPresetManager.onPresetChanged(pos)
                    rows.refreshStates()
                }
            }
        )

        rows.valueRow(
            title = "PERFORMANCE PROFILE (動作モード)",
            description = "Eco (省電力) / 普通 (標準) / 超高音質 (フルスペック倍音)",
            currentValue = {
                perfModeOptions[appPrefs.selectedPerfMode.coerceIn(0, perfModeOptions.lastIndex)]
            },
            isEnabled = {
                isFreqEngineActive() && FreqPresetManager.currentPresetIndex != 0
            },
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "PERFORMANCE PROFILE (動作モード)", perfModeOptions.toList(),
                    appPrefs.selectedPerfMode.coerceIn(0, perfModeOptions.lastIndex)
                ) { pos ->
                    if (appPrefs.selectedPerfMode != pos) {
                        appPrefs.selectedPerfMode = pos
                        NativeAudioEngine.nativeSetPerformanceMode(pos)
                    }
                    rows.refreshStates()
                }
            }
        )

        rows.switchRow(
            title = "RICH HARMONICS (ふくよか倍音)",
            description = "16kHz〜へ温かみのある偶数次倍音をブレンドし音に厚みを付加",
            initial = appPrefs.isRichHarmonicsEnabled,
            isEnabled = {
                isFreqEngineActive() && FreqPresetManager.currentPresetIndex != 0
            },
            onChange = { isChecked ->
                appPrefs.isRichHarmonicsEnabled = isChecked
                NativeAudioEngine.nativeSetRichHarmonics(isChecked)
            }
        )

        rows.valueRow(
            title = "ULTRA HQ UPSAMPLING",
            description = "ARM NEON 多段ポリフェーズFIR (96k/192k/384k)",
            currentValue = {
                upsampleOptions[
                    upsampleFactorValues.indexOf(appPrefs.selectedUpsampleFactor).coerceAtLeast(0)
                ]
            },
            isEnabled = { !appPrefs.isDirectSource },
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "ULTRA HQ UPSAMPLING", upsampleOptions.toList(),
                    upsampleFactorValues.indexOf(appPrefs.selectedUpsampleFactor).coerceAtLeast(0)
                ) { pos ->
                    val selectedFactor = upsampleFactorValues[pos]
                    if (selectedFactor != appPrefs.selectedUpsampleFactor) {
                        appPrefs.selectedUpsampleFactor = selectedFactor
                        onUpsampleFactorChanged(selectedFactor)
                        updateDspSectionsState()
                    }
                    rows.refreshStates()
                }
            }
        )

        rows.switchRow(
            title = "MULTISTAGE CASCADE FIR",
            description = "3段2x接続 (255→63→39 taps) 超低ノイズ補間",
            initial = appPrefs.isCascadeFir,
            isEnabled = { isFreqEngineActive() },
            onChange = { isChecked ->
                appPrefs.isCascadeFir = isChecked
                NativeAudioEngine.nativeSetCascadeFir(isChecked)
            }
        )

        rows.categoryHeader("変換・出力")

        // ★ DSD 変換レート（仕様書 §5.2）: 実体は Prefs のみで native への
        //   即時送信は無い。デコード側が読む設定のため、ビット深度と同様に
        //   Direct 時も無効化しない。
        rows.valueRow(
            title = "DSD CONVERSION RATE",
            description = "Local再生時 DSD→PCM 変換レート",
            currentValue = {
                dsdRateOptions[dsdRateValues.indexOf(appPrefs.dsdPcmRateHz).coerceAtLeast(0)]
            },
            onClick = {
                PbrChooserSheet.showSingleChoice(
                    activity, "DSD CONVERSION RATE", dsdRateOptions.toList(),
                    dsdRateValues.indexOf(appPrefs.dsdPcmRateHz).coerceAtLeast(0)
                ) { pos ->
                    val selectedRate = dsdRateValues[pos]
                    if (selectedRate != appPrefs.dsdPcmRateHz) {
                        appPrefs.dsdPcmRateHz = selectedRate
                    }
                    rows.refreshStates()
                }
            }
        )

        var volLockSwitch: SwitchCompat? = null
        volLockSwitch = rows.switchRow(
            title = "0dB VOLUME LOCK",
            description = "最大音量固定 / 音量UP:次へ / DOWN:前へ",
            initial = isVolumeLocked,
            isEnabled = { isUsbDevice(activeOutputDevice) },
            onChange = { isChecked ->
                if (isChecked && !isUsbDevice(activeOutputDevice)) {
                    volLockSwitch?.isChecked = false
                } else {
                    onVolumeLockChanged(isChecked)
                }
            }
        )

        // 開いている間の外部変更（開発プリセット側の操作など）で
        // FreqPresetManager のプリセットが変わったら、行の現在値と
        // PERF/RICH の有効状態を追従させる。
        FreqPresetManager.registerDspFreqRow { dspRows?.refreshStates() }

        // ★ 初期状態の確実な反映 (起動時のグレーアウトバグ修正)
        switchEqEnable.isChecked = appPrefs.isEqEnabled
        walkmanEqView?.isDirectBypass = !appPrefs.isEqEnabled

        updateDspSectionsState()
        updateEqPresetState(appPrefs.isDirectSource, appPrefs.isEqEnabled)

        fun setEditMode(enabled: Boolean) {
            if (appPrefs.isDirectSource) return
            walkmanEqView?.isEditMode = enabled
            if (enabled) {
                btnEqEdit.text = "完了"
                layoutEqAdjustControls.visibility = View.VISIBLE
                if (!switchEqEnable.isChecked) switchEqEnable.isChecked = true
            } else {
                btnEqEdit.text = "調整"
                layoutEqAdjustControls.visibility = View.GONE
            }
        }

        fun updateEqHeader(bandIdx: Int, gain: Float) {
            textEqBandFreq.text = "${walkmanEqView?.bandLabels?.getOrNull(bandIdx) ?: "1K"} Hz"
            textEqGainValue.text = String.format(java.util.Locale.US, "%+.1f dB", gain)
        }

        val savedGains = appPrefs.getAllEqGains()
        for (i in 0..9) walkmanEqView?.gains?.set(i, savedGains[i])
        setEditMode(false)
        updateEqHeader(walkmanEqView?.selectedBandIndex ?: 7, walkmanEqView?.gains?.getOrNull(walkmanEqView?.selectedBandIndex ?: 7) ?: 0f)

        // --- イコライザープリセットスピナーの再構築 ---
        val spinnerLayout = if (isDarkTheme) R.layout.item_spinner_dap else R.layout.item_spinner_dap_light

        fun rebuildSpinnerItems(targetSelectId: String? = null) {
            spinnerItemList.clear()
            builtinPresets.forEach { spinnerItemList.add(EqSpinnerItem.Builtin(it)) }
            spinnerItemList.add(EqSpinnerItem.WorkingCustom)
            savedCustomPresets.forEach { spinnerItemList.add(EqSpinnerItem.SavedCustom(it)) }

            val displayNames = spinnerItemList.map { it.displayName }.toTypedArray()
            val adapter = ArrayAdapter(activity, spinnerLayout, displayNames).apply { setDropDownViewResource(spinnerLayout) }
            spinnerEqPreset?.adapter = adapter

            val selId = targetSelectId ?: appPrefs.selectedEqPresetId
            var selectedIdx = 0
            for (i in spinnerItemList.indices) {
                if (spinnerItemList[i].id == selId) {
                    selectedIdx = i
                    break
                }
            }
            spinnerEqPreset?.setSelection(selectedIdx)
            updateDeleteButtonState()
        }

        rebuildSpinnerItems()

        fun showSaveCustomDialog() {
            val input = EditText(PbrDialogs.themedContext(activity)).apply {
                hint = "プリセット名を入力"
                setSingleLine()
                val currentItem = spinnerItemList.getOrNull(spinnerEqPreset?.selectedItemPosition ?: 0)
                val defaultName = if (currentItem is EqSpinnerItem.SavedCustom) currentItem.preset.name else "プリセット ${savedCustomPresets.size + 1}"
                setText(defaultName)
                selectAll()
            }

            PbrDialogs.builder(activity)
                .setTitle("カスタムEQプリセットの保存")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val newName = input.text.toString().trim().ifEmpty { "プリセット ${savedCustomPresets.size + 1}" }
                    val currentGains = walkmanEqView?.gains?.copyOf() ?: FloatArray(10)
                    val existing = savedCustomPresets.firstOrNull { it.name == newName }

                    if (existing != null) {
                        // 同名が存在する場合は上書き確認
                        PbrDialogs.builder(activity)
                            .setTitle("上書きの確認")
                            .setMessage("「$newName」は既に存在します。上書き保存しますか？")
                            .setPositiveButton("上書き") { _, _ ->
                                for (i in 0..9) existing.gains[i] = currentGains[i]
                                appPrefs.saveCustomEqPresets(savedCustomPresets)
                                appPrefs.selectedEqPresetId = "custom:${existing.id}"
                                rebuildSpinnerItems("custom:${existing.id}")
                                Toast.makeText(activity, "「$newName」を上書き保存しました", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("キャンセル", null)
                            .show()
                            .also { PbrDialogs.applyDestructive(it) }
                    } else {
                        // 新規保存
                        val newCustom = CustomEqPreset(
                            id = UUID.randomUUID().toString().take(8),
                            name = newName,
                            gains = currentGains
                        )
                        savedCustomPresets.add(newCustom)
                        appPrefs.saveCustomEqPresets(savedCustomPresets)
                        appPrefs.selectedEqPresetId = "custom:${newCustom.id}"
                        rebuildSpinnerItems("custom:${newCustom.id}")
                        Toast.makeText(activity, "「$newName」を保存しました", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        fun showDeleteCustomDialog() {
            val selId = appPrefs.selectedEqPresetId
            if (!selId.startsWith("custom:") || selId == "custom:working") return

            val customId = selId.removePrefix("custom:")
            val targetCustom = savedCustomPresets.firstOrNull { it.id == customId } ?: return

            PbrDialogs.builder(activity)
                .setTitle("プリセットの削除")
                .setMessage("カスタムプリセット「${targetCustom.name}」を削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    savedCustomPresets.remove(targetCustom)
                    appPrefs.saveCustomEqPresets(savedCustomPresets)
                    appPrefs.selectedEqPresetId = "custom:working"
                    rebuildSpinnerItems("custom:working")
                    Toast.makeText(activity, "「${targetCustom.name}」を削除しました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
                .also { PbrDialogs.applyDestructive(it) }
        }

        spinnerEqPreset?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position !in spinnerItemList.indices || isApplyingPreset) return
                val item = spinnerItemList[position]
                appPrefs.selectedEqPresetId = item.id

                when (item) {
                    is EqSpinnerItem.Builtin -> {
                        isApplyingPreset = true
                        try {
                            for (i in 0..9) walkmanEqView?.gains?.set(i, item.preset.gains[i])
                            walkmanEqView?.invalidate()
                            val gains = walkmanEqView?.gains ?: FloatArray(10)
                            NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, gains)
                            appPrefs.setAllEqGains(gains)
                            val curBand = walkmanEqView?.selectedBandIndex ?: 7
                            updateEqHeader(curBand, gains.getOrNull(curBand) ?: 0f)
                        } finally {
                            isApplyingPreset = false
                        }
                    }
                    is EqSpinnerItem.WorkingCustom -> {
                        isApplyingPreset = true
                        try {
                            val customGains = appPrefs.getWorkingCustomGains()
                            for (i in 0..9) walkmanEqView?.gains?.set(i, customGains[i])
                            walkmanEqView?.invalidate()
                            NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, customGains)
                            appPrefs.setAllEqGains(customGains)
                            val curBand = walkmanEqView?.selectedBandIndex ?: 7
                            updateEqHeader(curBand, customGains.getOrNull(curBand) ?: 0f)
                        } finally {
                            isApplyingPreset = false
                        }
                    }
                    is EqSpinnerItem.SavedCustom -> {
                        isApplyingPreset = true
                        try {
                            for (i in 0..9) walkmanEqView?.gains?.set(i, item.preset.gains[i])
                            walkmanEqView?.invalidate()
                            val gains = walkmanEqView?.gains ?: FloatArray(10)
                            NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, gains)
                            appPrefs.setAllEqGains(gains)
                            val curBand = walkmanEqView?.selectedBandIndex ?: 7
                            updateEqHeader(curBand, gains.getOrNull(curBand) ?: 0f)
                        } finally {
                            isApplyingPreset = false
                        }
                    }
                }
                updateDeleteButtonState()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        walkmanEqView?.onBandSelectedListener = { bandIdx, gain -> updateEqHeader(bandIdx, gain) }
        walkmanEqView?.onGainChangedListener = { bandIdx, gain, allGains ->
            updateEqHeader(bandIdx, gain)
            NativeAudioEngine.nativeSetEqualizer(appPrefs.isEqEnabled, allGains)
            appPrefs.setAllEqGains(allGains)
            appPrefs.setWorkingCustomGains(allGains)

            if (!isApplyingPreset) {
                val curSelId = appPrefs.selectedEqPresetId
                if (curSelId != "custom:working") {
                    // ★ プリセット(Flat/Rock等)選択中にEQを触ったら自動で Custom へ移行
                    appPrefs.selectedEqPresetId = "custom:working"
                    isApplyingPreset = true
                    try {
                        rebuildSpinnerItems("custom:working")
                    } finally {
                        isApplyingPreset = false
                    }
                }
            }
        }

        btnEqEdit.setOnClickListener { setEditMode(!(walkmanEqView?.isEditMode ?: false)) }
        btnEqSave.setOnClickListener { showSaveCustomDialog() }
        btnEqDelete?.setOnClickListener { showDeleteCustomDialog() }

        btnEqFlat.setOnClickListener {
            walkmanEqView?.resetAllFlat()
            appPrefs.selectedEqPresetId = "builtin:Flat"
            rebuildSpinnerItems("builtin:Flat")
        }

        switchEqEnable.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.isEqEnabled = isChecked
            walkmanEqView?.isDirectBypass = !isChecked
            NativeAudioEngine.nativeSetEqualizer(isChecked, walkmanEqView?.gains ?: FloatArray(10))
            if (!isChecked) setEditMode(false)
            updateEqPresetState(appPrefs.isDirectSource, isChecked)
        }

        btnEqPlus.setOnClickListener { walkmanEqView?.stepGain(+0.5f) }
        btnEqMinus.setOnClickListener { walkmanEqView?.stepGain(-0.5f) }

        // ★ PR-C1: ビット深度／ディザ／DC位相／TP天井／性能／倍率／DSD の
        //   各 Spinner の設定は、行の構築側（valueRow の onClick → 選択
        //   シート）へ移った。保存先・反映経路は従来と同一。

        btnPlayPause?.setOnClickListener {
            it.hapticTick(appPrefs)
            onPlayerCommand("play_pause")
        }
        btnPrev.setOnClickListener {
            it.hapticTick(appPrefs)
            onPlayerCommand("prev")
        }
        btnNext.setOnClickListener {
            it.hapticTick(appPrefs)
            onPlayerCommand("next")
        }

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentDuration > 0) {
                    val seekMs = (progress.toLong() * currentDuration) / 1000L
                    textCurrentTime?.text = formatTime(seekMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                if (currentDuration > 0 && sb != null) {
                    val seekMs = (sb.progress.toLong() * currentDuration) / 1000L
                    onSeekTo(seekMs)
                }
            }
        })

        val dismissClickListener = View.OnClickListener { bottomSheetDialog.dismiss() }
        view.findViewById<View>(R.id.dspDialogPlayerControl)?.setOnClickListener(dismissClickListener)
        view.findViewById<View>(R.id.dspPlayerCard)?.setOnClickListener(dismissClickListener)
        imageArtwork?.setOnClickListener(dismissClickListener)
        textTrackTitle?.setOnClickListener(dismissClickListener)
        textTrackArtist?.setOnClickListener(dismissClickListener)
        textCurrentTime?.setOnClickListener(dismissClickListener)
        textTotalTime?.setOnClickListener(dismissClickListener)

        btnClose.setOnClickListener {
            it.hapticTick(appPrefs)
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setOnDismissListener {
            walkmanEqView = null
            textTrackTitle = null
            textTrackArtist = null
            textCurrentTime = null
            textTotalTime = null
            imageArtwork = null
            seekBar = null
            btnPlayPause = null
            btnEqDelete = null
            dspRows = null
            spinnerEqPreset = null
            FreqPresetManager.clearDspFreqRow()
            onDismiss()
        }

        bottomSheetDialog.show()
    }

    // ★ PR-C1: 従来は PERFORMANCE / RICH HARMONICS セクションの有効状態を
    //   ここで付け替えていた。PR-C1 で両者は行化され、有効状態は行の
    //   isEnabled ラムダ（FREQ ENGINE 有効 かつ プリセット index != 0）が
    //   refreshStates() で再評価する。本メソッドは MainActivity 側の
    //   FreqPresetManager.onPresetChangedListener からの呼び出し互換の
    //   ため残し、中身を行の再評価へ委ねる（引数は従来の呼び出し形式の
    //   名残で、判定は行側が Prefs と currentPresetIndex から行う）。
    fun updatePerfModeState(isDseeActive: Boolean) {
        dspRows?.refreshStates()
    }

    override fun updatePlayerState(
        title: String,
        artist: String,
        artwork: Bitmap?,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long
    ) {
        currentDuration = durationMs
        textTrackTitle?.text = title
        textTrackArtist?.text = artist

        btnPlayPause?.setImageResource(
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )

        if (artwork != null) {
            imageArtwork?.setImageBitmap(artwork)
        }

        if (!isUserSeeking && durationMs > 0) {
            val progress = ((currentPositionMs.toDouble() / durationMs.toDouble()) * 1000).toInt().coerceIn(0, 1000)
            seekBar?.progress = progress
            textCurrentTime?.text = formatTime(currentPositionMs)
            textTotalTime?.text = formatTime(durationMs)
        } else if (durationMs <= 0L) {
            seekBar?.progress = 0
            textCurrentTime?.text = "0:00"
            textTotalTime?.text = "0:00"
        }
    }

    override fun setSpectrumLevels(spectrumBands: FloatArray) {
        walkmanEqView?.setSpectrumLevels(spectrumBands)
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

    // ★ PR-C1: 行化したセクションは DspSettingsRows が構築時に isDark で
    //   色を確定するため、本関数の付け替え対象は EQ ブロックと
    //   ミニプレイヤーだけになった（旧来の約130行の付け替えから縮小）。
    private fun applyLightModeStyle(
        view: View,
        btnClose: ImageButton,
        switchEqEnable: SwitchCompat,
        switchSpectrumEnable: SwitchCompat,
        btnEqEdit: Button,
        btnEqSave: Button,
        btnEqDelete: Button,
        btnEqFlat: Button,
        btnEqPlus: ImageButton,
        btnEqMinus: ImageButton,
        textEqBandFreq: TextView,
        textEqGainValue: TextView,
        spinnerEqPreset: Spinner,
        btnPrev: ImageButton,
        btnNext: ImageButton,
        btnPlayPause: ImageButton
    ) {
        view.setBackgroundResource(R.drawable.bg_bottom_sheet_dap_light)
        view.findViewById<View>(R.id.layoutSectionEq)?.setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_panel_light))
        view.findViewById<TextView>(R.id.textEqTitle)?.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))
        view.findViewById<TextView>(R.id.textEqSub)?.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))
        view.findViewById<TextView>(R.id.textEqSwLabel)?.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))

        spinnerEqPreset.setBackgroundResource(R.drawable.bg_spinner_dap_light)
        spinnerEqPreset.setPopupBackgroundResource(R.drawable.bg_spinner_popup_dap_light)

        val swTrackLight = ContextCompat.getDrawable(activity, R.drawable.switch_track_walkman_outline_light)
        val swThumbLight = ContextCompat.getDrawable(activity, R.drawable.switch_thumb_light)
        listOf(switchEqEnable, switchSpectrumEnable).forEach { sw ->
            sw.trackDrawable = swTrackLight
            sw.thumbDrawable = swThumbLight
        }

        btnClose.setBackgroundResource(R.drawable.bg_btn_icon_light)
        btnClose.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary_light))

        btnEqEdit.setBackgroundResource(R.drawable.bg_btn_dap_outline_light)
        btnEqSave.setBackgroundResource(R.drawable.bg_btn_dap_outline_light)
        btnEqDelete.setBackgroundResource(R.drawable.bg_btn_dap_outline_light)
        btnEqFlat.setBackgroundResource(R.drawable.bg_btn_dap_outline_light)
        btnEqEdit.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))
        btnEqSave.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))
        btnEqDelete.setTextColor(ContextCompat.getColor(activity, R.color.danger_light))
        btnEqFlat.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))
        btnEqPlus.setBackgroundResource(R.drawable.bg_btn_circle_light)
        btnEqMinus.setBackgroundResource(R.drawable.bg_btn_circle_light)
        btnEqPlus.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary_light))
        btnEqMinus.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary_light))

        textEqBandFreq.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))
        textEqGainValue.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))

        seekBar?.progressBackgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.component_border_light))
        seekBar?.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent_light))
        seekBar?.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.text_primary_light))

        btnPrev.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
        btnNext.setBackgroundResource(R.drawable.bg_btn_walkman_circle_small_light)
        btnPlayPause.setBackgroundResource(R.drawable.bg_btn_walkman_circle_large_light)
        btnPrev.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary_light))
        btnNext.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary_light))
        btnPlayPause.setColorFilter(ContextCompat.getColor(activity, R.color.text_primary_light))

        view.findViewById<View>(R.id.dspDialogPlayerControl)?.setBackgroundColor(ContextCompat.getColor(activity, R.color.surface_panel_light))
        view.findViewById<View>(R.id.dspPlayerDivider)?.setBackgroundColor(ContextCompat.getColor(activity, R.color.divider_light))
        view.findViewById<TextView>(R.id.dialogTextTrackTitle)?.setTextColor(ContextCompat.getColor(activity, R.color.text_primary_light))
        view.findViewById<TextView>(R.id.dialogTextTrackArtist)?.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))
        view.findViewById<TextView>(R.id.dialogTextCurrentTime)?.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))
        view.findViewById<TextView>(R.id.dialogTextTotalTime)?.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary_light))
    }
}