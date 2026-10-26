#pragma once
#include "dsp_types.h"

class DspFreqEngine {
public:
    DspFreqEngine();
    void configure(FreqMode mode, double sampleRate, float gain = 0.22f, float extractFreq = 13000.0f);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);
    void setPerformanceMode(PerformanceMode mode) { perfMode_ = mode; }
    // Dynamic SBR: 抽出開始点の上限キャップ (Hz, <=0 で無効) と倍音ゲイン倍率を受け取る。
    // 係数の差し替えは次回 processStereo の先頭でブロック境界でのみ行う。
    void setDynamicSbrState(double extractCapHz, double gainScale);

    // Freq 詳細パラメータ: lpcAlgo は LPC 分析による倍音ミックス適応
    // (0=適応 / 1=K2 Natural / 2=Exciter、それ以外は無効で従来動作と完全同一)、
    // useQmf / qmfCrossoverHz は QMF サブバンド分割 (LR4 クロスオーバー) を駆動する。
    // 制御値のため configure() では消去されず、reset() ではフィルタ状態のみ消去される。
    void setAdvancedParams(int lpcAlgo, bool useQmf, double qmfCrossoverHz);

    void setRichHarmonics(bool enabled) {
        if (isRichHarmonics_ != enabled) {
            isRichHarmonics_ = enabled;
            configure(mode_, sampleRate_, targetGain_, static_cast<float>(fExtract_));
        }
    }
    bool isRichHarmonics() const { return isRichHarmonics_; }

private:
    void updateInputHp(double fExtractHz);
    double effectiveExtractHz() const;
    void updateQmfCrossover();
    void resetLpcState();
    void updateLpcAnalysis();

    FreqMode mode_ = FreqMode::AUTO_AI;
    PerformanceMode perfMode_ = PerformanceMode::STANDARD;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;
    bool isRichHarmonics_ = false;
    float targetGain_ = 0.22f;
    double fExtract_ = 13000.0;

    // Dynamic SBR の制御状態 (フィルタ状態ではないため reset() では消去しない)
    double dynCapHz_ = 0.0;
    double dynGainScale_ = 1.0;
    bool dynDirty_ = false;
    double lastAppliedEffExtract_ = 13000.0;

    // QMF / LPC の制御状態 (フィルタ状態ではないため reset() では消去しない)
    int lpcAlgo_ = -1;             // 未設定 = LPC 無効 (乗数すべて 1.0 で従来と完全同一)。有効値は 0/1/2 のみ
    bool useQmf_ = false;
    double qmfXoverHz_ = 16900.0;  // ユーザー設定値 (実効値は updateQmfCrossover でクランプ)

    // QMF クロスオーバー係数 (LR4: LP/HP とも RBJ Butterworth 双2次×2段、両段で同一係数)
    double qmf_lp_b0_ = 1.0, qmf_lp_b1_ = 0.0, qmf_lp_b2_ = 0.0;
    double qmf_lp_a1_ = 0.0, qmf_lp_a2_ = 0.0;
    double qmf_hp_b0_ = 1.0, qmf_hp_b1_ = -2.0, qmf_hp_b2_ = 1.0;
    double qmf_hp_a1_ = 0.0, qmf_hp_a2_ = 0.0;

    // QMF クロスオーバーのフィルタ状態 (Mid/Side × LP/HP × 2段、reset() で消去)
    double qmf_lp1_s1_mid_ = 0.0, qmf_lp1_s2_mid_ = 0.0;
    double qmf_lp2_s1_mid_ = 0.0, qmf_lp2_s2_mid_ = 0.0;
    double qmf_hp1_s1_mid_ = 0.0, qmf_hp1_s2_mid_ = 0.0;
    double qmf_hp2_s1_mid_ = 0.0, qmf_hp2_s2_mid_ = 0.0;
    double qmf_lp1_s1_side_ = 0.0, qmf_lp1_s2_side_ = 0.0;
    double qmf_lp2_s1_side_ = 0.0, qmf_lp2_s2_side_ = 0.0;
    double qmf_hp1_s1_side_ = 0.0, qmf_hp1_s2_side_ = 0.0;
    double qmf_hp2_s1_side_ = 0.0, qmf_hp2_s2_side_ = 0.0;

    double in_hp_b0_ = 1.0, in_hp_b1_ = -2.0, in_hp_b2_ = 1.0;
    double in_hp_a1_ = 0.0, in_hp_a2_ = 0.0;
    double in_s1_L_ = 0.0, in_s2_L_ = 0.0;
    double in_s1_R_ = 0.0, in_s2_R_ = 0.0;

    double out_hp_b0_ = 1.0, out_hp_b1_ = -2.0, out_hp_b2_ = 1.0;
    double out_hp_a1_ = 0.0, out_hp_a2_ = 0.0;
    double out_s1_L_ = 0.0, out_s2_L_ = 0.0;
    double out_s1_R_ = 0.0, out_s2_R_ = 0.0;

    double silk_lp_b0_ = 1.0, silk_lp_b1_ = 0.0, silk_lp_b2_ = 0.0;
    double silk_lp_a1_ = 0.0, silk_lp_a2_ = 0.0;
    double silk_s1_L_ = 0.0, silk_s2_L_ = 0.0;
    double silk_s1_R_ = 0.0, silk_s2_R_ = 0.0;

    double formant_bp_b0_ = 0.0, formant_bp_b1_ = 0.0, formant_bp_b2_ = 0.0;
    double formant_bp_a1_ = 0.0, formant_bp_a2_ = 0.0;
    double formant_s1_L_ = 0.0, formant_s2_L_ = 0.0;
    double formant_s1_R_ = 0.0, formant_s2_R_ = 0.0;

    double dcCutCoeff_ = 0.985;
    double dc_xL_ = 0.0, dc_yL_ = 0.0;
    double dc_xR_ = 0.0, dc_yR_ = 0.0;

    double evenRatio_ = 0.65;
    double oddRatio_ = 0.35;
    double modeGainScale_ = 1.15;

    double r0_Mid_ = 1e-4, r0_Side_ = 1e-4;
    double smoothedGainMid_ = 0.0, smoothedGainSide_ = 0.0;

    double prevPowMid_ = 0.0, prevPowSide_ = 0.0;
    double transientFluxMid_ = 0.0, transientFluxSide_ = 0.0;
    double noiseFloorMid_ = 1e-5, noiseFloorSide_ = 1e-5;

    // LPC 分析状態 (reset() でニュートラルに初期化する。lpcAlgo_ 自体は消去しない)
    static constexpr int kLpcOrder = 10;
    static constexpr int kLpcBlockSize = 256;
    double lpcBuf_[kLpcBlockSize] = {};
    int lpcFill_ = 0;
    double lpcTiltPrev_ = 0.0, lpcTiltCur_ = 0.0;  // 平滑済みスペクトルティルト (前ブロック値 / 現ブロック値)
    double lpcPgPrev_ = 0.0, lpcPgCur_ = 0.0;      // 平滑済み予測ゲイン (同上)
};