#include "dsp_freq_engine.h"
#include <algorithm>

namespace {

// 既存バイカッドと同一の DF2T 形式で1段を処理する (QMF クロスオーバー用)
inline double runBiquad(double x, double b0, double b1, double b2,
                        double a1, double a2, double& s1, double& s2) {
    double y = b0 * x + s1;
    s1 = b1 * x - a1 * y + s2;
    s2 = b2 * x - a2 * y;
    return y;
}

// 単帯域の波形整形 (状態を持たないサンプル単位の純関数)。
// 現行 processStereo の多項式倍音生成をそのまま移設したもので、
// 演算の値・順序は従来のインライン式と完全に同一に保たれる。
// rms は解析段が全帯域信号から求めた値を使う (従来通り)。
inline double shapeHarmonics(double x, double rms,
                             double h2Scale, double h3Scale, double h4Scale,
                             double wH2, double wH3, double wH4) {
    double norm = std::clamp(x / (rms * 1.414 + 1e-5), -2.5, 2.5);
    double softNorm = norm / (1.0 + 0.25 * std::abs(norm));
    double normSq = softNorm * softNorm;
    double h2 = (normSq - 0.42) * (rms * h2Scale);
    double h3 = (softNorm * normSq - 0.60 * softNorm) * (rms * h3Scale);
    double h4 = (normSq * normSq - 0.90 * normSq + 0.15) * (rms * h4Scale);
    return wH2 * h2 + wH3 * h3 + wH4 * h4;
}

} // namespace

DspFreqEngine::DspFreqEngine() {
    configure(FreqMode::AUTO_AI, 48000.0, 0.22f, 13000.0f);
}

void DspFreqEngine::configure(FreqMode mode, double sampleRate, float gain, float extractFreq) {
    mode_ = mode;
    sampleRate_ = std::max(8000.0, sampleRate);
    targetGain_ = std::clamp(gain, 0.0f, 1.0f);
    reset();

    if (mode_ == FreqMode::OFF) {
        isBypass_ = true;
        // バイパス中は使われないが、fs が変わった場合に備えて係数を実効値へ追随させる
        updateQmfCrossover();
        return;
    }
    isBypass_ = false;

    fExtract_ = (extractFreq > 6000.0f) ? static_cast<double>(extractFreq) : (isRichHarmonics_ ? 12000.0 : 13000.0);

    double fOutHp = isRichHarmonics_ ? 15800.0 : 19800.0;
    
    evenRatio_ = isRichHarmonics_ ? 0.96 : 0.65;
    oddRatio_  = isRichHarmonics_ ? 0.04 : 0.35;
    modeGainScale_ = isRichHarmonics_ ? 1.32 : 1.15;

    switch (mode_) {
        case FreqMode::AUTO_AI:
            fOutHp = isRichHarmonics_ ? 15600.0 : 19800.0;
            break;
        case FreqMode::STUDIO_VOCAL:
            fOutHp = isRichHarmonics_ ? 15200.0 : 19850.0;
            evenRatio_ = isRichHarmonics_ ? 0.98 : 0.72;
            oddRatio_  = isRichHarmonics_ ? 0.02 : 0.28;
            break;
        case FreqMode::ACOUSTIC_INSTRUMENT:
            fOutHp = isRichHarmonics_ ? 15400.0 : 19800.0;
            evenRatio_ = isRichHarmonics_ ? 0.95 : 0.60;
            oddRatio_  = isRichHarmonics_ ? 0.05 : 0.40;
            break;
        case FreqMode::DYNAMIC_PERCUSSION:
            fOutHp = isRichHarmonics_ ? 16200.0 : 19700.0;
            break;
        case FreqMode::AIR_EXPANDER:
            fOutHp = isRichHarmonics_ ? 16400.0 : 19900.0;
            break;
        default:
            break;
    }

    fExtract_ = std::clamp(fExtract_, 4000.0, sampleRate_ * 0.40);
    fOutHp    = std::clamp(fOutHp, 8000.0, sampleRate_ * 0.43);

    updateInputHp(fExtract_);
    lastAppliedEffExtract_ = fExtract_;

    double w0_out = 2.0 * DSP_PI * fOutHp / sampleRate_;
    double alpha_out = std::sin(w0_out) / (2.0 * 0.70710678);
    double cosw0_out = std::cos(w0_out);

    double out_b0 = (1.0 + cosw0_out) * 0.5;
    double out_b1 = -(1.0 + cosw0_out);
    double out_b2 = (1.0 + cosw0_out) * 0.5;
    double out_a0 = 1.0 + alpha_out;
    double out_a1 = -2.0 * cosw0_out;
    double out_a2 = 1.0 - alpha_out;

    double inv_out_a0 = 1.0 / out_a0;
    out_hp_b0_ = out_b0 * inv_out_a0;
    out_hp_b1_ = out_b1 * inv_out_a0;
    out_hp_b2_ = out_b2 * inv_out_a0;
    out_hp_a1_ = out_a1 * inv_out_a0;
    out_hp_a2_ = out_a2 * inv_out_a0;

    double fSilk = isRichHarmonics_ ? std::min(26000.0, sampleRate_ * 0.42) : std::min(32000.0, sampleRate_ * 0.44);
    double w0_silk = 2.0 * DSP_PI * fSilk / sampleRate_;
    double alpha_silk = std::sin(w0_silk) / (2.0 * 0.70710678);
    double cosw0_silk = std::cos(w0_silk);

    double silk_b0 = (1.0 - cosw0_silk) * 0.5;
    double silk_b1 = 1.0 - cosw0_silk;
    double silk_b2 = (1.0 - cosw0_silk) * 0.5;
    double silk_a0 = 1.0 + alpha_silk;
    double silk_a1 = -2.0 * cosw0_silk;
    double silk_a2 = 1.0 - alpha_silk;

    double inv_silk_a0 = 1.0 / silk_a0;
    silk_lp_b0_ = silk_b0 * inv_silk_a0;
    silk_lp_b1_ = silk_b1 * inv_silk_a0;
    silk_lp_b2_ = silk_b2 * inv_silk_a0;
    silk_lp_a1_ = silk_a1 * inv_silk_a0;
    silk_lp_a2_ = silk_a2 * inv_silk_a0;

    double fFormant = std::min(3200.0, sampleRate_ * 0.40);
    double w0_f = 2.0 * DSP_PI * fFormant / sampleRate_;
    double alpha_f = std::sin(w0_f) / (2.0 * 1.4);
    double cosw0_f = std::cos(w0_f);

    double f_b0 = alpha_f;
    double f_b1 = 0.0;
    double f_b2 = -alpha_f;
    double f_a0 = 1.0 + alpha_f;
    double f_a1 = -2.0 * cosw0_f;
    double f_a2 = 1.0 - alpha_f;

    double inv_f_a0 = 1.0 / f_a0;
    formant_bp_b0_ = f_b0 * inv_f_a0;
    formant_bp_b1_ = f_b1 * inv_f_a0;
    formant_bp_b2_ = f_b2 * inv_f_a0;
    formant_bp_a1_ = f_a1 * inv_f_a0;
    formant_bp_a2_ = f_a2 * inv_f_a0;

    dcCutCoeff_ = std::exp(-2.0 * DSP_PI * 120.0 / sampleRate_);

    // Dynamic SBR のキャップが有効なまま再構成された場合は、次の process で再適用する
    if (dynCapHz_ > 0.0) dynDirty_ = true;

    // QMF の制御値 (lpcAlgo_ / useQmf_ / qmfXoverHz_) は configure では消去しない。
    // fs / 抽出周波数が変わりうるため、クロスオーバー係数だけはここで再計算する。
    updateQmfCrossover();
}

void DspFreqEngine::setAdvancedParams(int lpcAlgo, bool useQmf, double qmfCrossoverHz) {
    lpcAlgo_ = lpcAlgo;  // LPC の音響処理は次 PR で実装する。今回は値の保持のみ。
    useQmf_ = useQmf;
    qmfXoverHz_ = qmfCrossoverHz;
    updateQmfCrossover();
}

void DspFreqEngine::updateQmfCrossover() {
    // 実効クロスオーバー周波数: ユーザー設定値を [実効抽出周波数, fs×0.40] にクランプする。
    // 実効抽出周波数は Dynamic SBR のキャップ込み (effectiveExtractHz) で、
    // 入力 HP の実効値と常に一致させる。fs / 抽出が未確定な場合は既存コードの
    // 既定値 (48000 / 13000) で補う。
    double fs = (sampleRate_ > 0.0) ? sampleRate_ : 48000.0;
    double effExtract = effectiveExtractHz();
    if (!(effExtract > 0.0)) effExtract = 13000.0;
    effExtract = std::min(effExtract, fs * 0.40);
    double fX = std::clamp(qmfXoverHz_, effExtract, fs * 0.40);
    if (!(fX > 0.0)) fX = effExtract;  // 設定値が NaN 等の場合の保険

    const double q = 0.70710678;  // Butterworth (LR4 は同一バイカッド2段で構成)
    double w0 = 2.0 * DSP_PI * fX / fs;
    double alpha = std::sin(w0) / (2.0 * q);
    double cosw0 = std::cos(w0);

    // ローパス段 (RBJ Butterworth LP)
    double lp_b0 = (1.0 - cosw0) * 0.5;
    double lp_b1 = 1.0 - cosw0;
    double lp_b2 = (1.0 - cosw0) * 0.5;
    double lp_a0 = 1.0 + alpha;
    double lp_a1 = -2.0 * cosw0;
    double lp_a2 = 1.0 - alpha;

    double inv_lp_a0 = 1.0 / lp_a0;
    qmf_lp_b0_ = lp_b0 * inv_lp_a0;
    qmf_lp_b1_ = lp_b1 * inv_lp_a0;
    qmf_lp_b2_ = lp_b2 * inv_lp_a0;
    qmf_lp_a1_ = lp_a1 * inv_lp_a0;
    qmf_lp_a2_ = lp_a2 * inv_lp_a0;

    // ハイパス段 (RBJ Butterworth HP)
    double hp_b0 = (1.0 + cosw0) * 0.5;
    double hp_b1 = -(1.0 + cosw0);
    double hp_b2 = (1.0 + cosw0) * 0.5;
    double hp_a0 = 1.0 + alpha;
    double hp_a1 = -2.0 * cosw0;
    double hp_a2 = 1.0 - alpha;

    double inv_hp_a0 = 1.0 / hp_a0;
    qmf_hp_b0_ = hp_b0 * inv_hp_a0;
    qmf_hp_b1_ = hp_b1 * inv_hp_a0;
    qmf_hp_b2_ = hp_b2 * inv_hp_a0;
    qmf_hp_a1_ = hp_a1 * inv_hp_a0;
    qmf_hp_a2_ = hp_a2 * inv_hp_a0;
}

void DspFreqEngine::updateInputHp(double fExtractHz) {
    double f = std::clamp(fExtractHz, 4000.0, sampleRate_ * 0.40);

    double w0_in = 2.0 * DSP_PI * f / sampleRate_;
    double alpha_in = std::sin(w0_in) / (2.0 * 0.70710678);
    double cosw0_in = std::cos(w0_in);

    double in_b0 = (1.0 + cosw0_in) * 0.5;
    double in_b1 = -(1.0 + cosw0_in);
    double in_b2 = (1.0 + cosw0_in) * 0.5;
    double in_a0 = 1.0 + alpha_in;
    double in_a1 = -2.0 * cosw0_in;
    double in_a2 = 1.0 - alpha_in;

    double inv_in_a0 = 1.0 / in_a0;
    in_hp_b0_ = in_b0 * inv_in_a0;
    in_hp_b1_ = in_b1 * inv_in_a0;
    in_hp_b2_ = in_b2 * inv_in_a0;
    in_hp_a1_ = in_a1 * inv_in_a0;
    in_hp_a2_ = in_a2 * inv_in_a0;
}

double DspFreqEngine::effectiveExtractHz() const {
    return (dynCapHz_ > 0.0) ? std::min(fExtract_, dynCapHz_) : fExtract_;
}

void DspFreqEngine::setDynamicSbrState(double extractCapHz, double gainScale) {
    dynCapHz_ = extractCapHz;
    dynGainScale_ = gainScale;
    // 実効抽出周波数が適用済みの値から 25Hz を超えて変わる場合のみ係数差し替えを予約する
    if (std::abs(effectiveExtractHz() - lastAppliedEffExtract_) > 25.0) {
        dynDirty_ = true;
    }
}

void DspFreqEngine::reset() {
    in_s1_L_ = 0.0; in_s2_L_ = 0.0;
    in_s1_R_ = 0.0; in_s2_R_ = 0.0;
    out_s1_L_ = 0.0; out_s2_L_ = 0.0;
    out_s1_R_ = 0.0; out_s2_R_ = 0.0;
    silk_s1_L_ = 0.0; silk_s2_L_ = 0.0;
    silk_s1_R_ = 0.0; silk_s2_R_ = 0.0;
    formant_s1_L_ = 0.0; formant_s2_L_ = 0.0;
    formant_s1_R_ = 0.0; formant_s2_R_ = 0.0;
    // QMF クロスオーバーのフィルタ状態のみ消去 (制御値・係数は保持する)
    qmf_lp1_s1_mid_ = 0.0; qmf_lp1_s2_mid_ = 0.0;
    qmf_lp2_s1_mid_ = 0.0; qmf_lp2_s2_mid_ = 0.0;
    qmf_hp1_s1_mid_ = 0.0; qmf_hp1_s2_mid_ = 0.0;
    qmf_hp2_s1_mid_ = 0.0; qmf_hp2_s2_mid_ = 0.0;
    qmf_lp1_s1_side_ = 0.0; qmf_lp1_s2_side_ = 0.0;
    qmf_lp2_s1_side_ = 0.0; qmf_lp2_s2_side_ = 0.0;
    qmf_hp1_s1_side_ = 0.0; qmf_hp1_s2_side_ = 0.0;
    qmf_hp2_s1_side_ = 0.0; qmf_hp2_s2_side_ = 0.0;
    dc_xL_ = 0.0; dc_yL_ = 0.0;
    dc_xR_ = 0.0; dc_yR_ = 0.0;
    r0_Mid_ = 1e-4; r0_Side_ = 1e-4;
    smoothedGainMid_ = 0.0; smoothedGainSide_ = 0.0;
    prevPowMid_ = 0.0; prevPowSide_ = 0.0;
    transientFluxMid_ = 0.0; transientFluxSide_ = 0.0;
    noiseFloorMid_ = 1e-5; noiseFloorSide_ = 1e-5;
}

void DspFreqEngine::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    // Dynamic SBR: 予約された係数差し替えをブロック境界でのみ適用する
    // (フィルタ状態はリセットしないためクリックノイズにならない)
    if (dynDirty_) {
        double effExtract = effectiveExtractHz();
        updateInputHp(effExtract);
        lastAppliedEffExtract_ = effExtract;
        dynDirty_ = false;
        // QMF クロスオーバーの下限は実効抽出周波数に連動するため、合わせて再計算する
        // (入力 HP の処理自体には干渉しない)
        updateQmfCrossover();
    }

    // Dynamic SBR のゲイン倍率は targetGain_ の消費点すべてに共通する最上流で一度だけ掛ける
    const double effTargetGain = static_cast<double>(targetGain_) * dynGainScale_;

    for (size_t i = 0; i < numFrames; ++i) {
        double inL = static_cast<double>(left[i]);
        double inR = static_cast<double>(right[i]);

        double hiL = in_hp_b0_ * inL + in_s1_L_;
        in_s1_L_ = in_hp_b1_ * inL - in_hp_a1_ * hiL + in_s2_L_;
        in_s2_L_ = in_hp_b2_ * inL - in_hp_a2_ * hiL;

        double hiR = in_hp_b0_ * inR + in_s1_R_;
        in_s1_R_ = in_hp_b1_ * inR - in_hp_a1_ * hiR + in_s2_R_;
        in_s2_R_ = in_hp_b2_ * inR - in_hp_a2_ * hiR;

        double formantL = formant_bp_b0_ * inL + formant_s1_L_;
        formant_s1_L_ = formant_bp_b1_ * inL - formant_bp_a1_ * formantL + formant_s2_L_;
        formant_s2_L_ = formant_bp_b2_ * inL - formant_bp_a2_ * formantL;

        double formantR = formant_bp_b0_ * inR + formant_s1_R_;
        formant_s1_R_ = formant_bp_b1_ * inR - formant_bp_a1_ * formantR + formant_s2_R_;
        formant_s2_R_ = formant_bp_b2_ * inR - formant_bp_a2_ * formantR;

        double hiMid  = (hiL + hiR) * 0.5;
        double hiSide = (hiL - hiR) * 0.5;

        if (perfMode_ == PerformanceMode::ULTRA_HQ) {
            double sideAlign = (hiSide * 0.90) + (hiMid * 0.10);
            hiSide = sideAlign;
        }

        double midPow = hiMid * hiMid;
        double diffMid = std::max(0.0, midPow - prevPowMid_);
        prevPowMid_ = midPow;
        transientFluxMid_ = transientFluxMid_ * 0.94 + diffMid * 0.06;

        if (midPow < noiseFloorMid_) noiseFloorMid_ = noiseFloorMid_ * 0.999 + midPow * 0.001;
        else noiseFloorMid_ = noiseFloorMid_ * 0.99995 + midPow * 0.00005;
        noiseFloorMid_ = std::clamp(noiseFloorMid_, 1e-10, 1e-4);

        double sidePow = hiSide * hiSide;
        double diffSide = std::max(0.0, sidePow - prevPowSide_);
        prevPowSide_ = sidePow;
        transientFluxSide_ = transientFluxSide_ * 0.94 + diffSide * 0.06;

        if (sidePow < noiseFloorSide_) noiseFloorSide_ = noiseFloorSide_ * 0.999 + sidePow * 0.001;
        else noiseFloorSide_ = noiseFloorSide_ * 0.99995 + sidePow * 0.00005;
        noiseFloorSide_ = std::clamp(noiseFloorSide_, 1e-10, 1e-4);

        double formantPow = (formantL * formantL + formantR * formantR) * 0.5;
        bool isBreathContext = (formantPow > noiseFloorMid_ * 6.0) && (midPow > noiseFloorMid_ * 3.0);

        double snrMid = midPow / (noiseFloorMid_ + 1e-11);
        double floorGateMid = 1.0;
        if (!isBreathContext) {
            if (snrMid < 1.4) floorGateMid = 0.20;
            else if (snrMid < 3.8) {
                double t = (snrMid - 1.4) / 2.4;
                floorGateMid = 0.20 + 0.80 * (t * t);
            }
        }

        double snrSide = sidePow / (noiseFloorSide_ + 1e-11);
        double floorGateSide = 1.0;
        if (snrSide < 1.2) floorGateSide = 0.25;
        else if (snrSide < 3.5) {
            double t = (snrSide - 1.2) / 2.3;
            floorGateSide = 0.25 + 0.75 * (t * t);
        }

        double adaptAlphaMid = (midPow > r0_Mid_) ? 0.025 : 0.0035;
        r0_Mid_ = r0_Mid_ * (1.0 - adaptAlphaMid) + midPow * adaptAlphaMid;
        double rmsMid = std::sqrt(std::max(1e-12, r0_Mid_));

        double adaptAlphaSide = (sidePow > r0_Side_) ? 0.025 : 0.0035;
        r0_Side_ = r0_Side_ * (1.0 - adaptAlphaSide) + sidePow * adaptAlphaSide;
        double rmsSide = std::sqrt(std::max(1e-12, r0_Side_));

        double tonalityMid = std::clamp(1.0 - (transientFluxMid_ / (rmsMid * 2.0 + 1e-5)), 0.0, 1.0);
        if (isBreathContext) tonalityMid = std::max(tonalityMid, 0.65);

        double effEven = (mode_ == FreqMode::AUTO_AI) ? (isRichHarmonics_ ? (0.75 + 0.20 * tonalityMid) : (0.45 + 0.35 * tonalityMid)) : evenRatio_;
        double effOdd  = (mode_ == FreqMode::AUTO_AI) ? (1.0 - effEven) : oddRatio_;

        double baseMidGain = isRichHarmonics_ ? 0.24f : 0.18f;
        double baseSideGain = isRichHarmonics_ ? 0.28f : 0.22f;

        double targetGainMid = std::min(rmsMid * (isRichHarmonics_ ? 0.85 : 0.70), effTargetGain * modeGainScale_ * baseMidGain) * floorGateMid;
        smoothedGainMid_ += (targetGainMid - smoothedGainMid_) * ((targetGainMid > smoothedGainMid_) ? 0.040 : 0.004);

        double targetGainSide = std::min(rmsSide * (isRichHarmonics_ ? 1.00 : 0.90), effTargetGain * modeGainScale_ * baseSideGain) * floorGateSide;
        smoothedGainSide_ += (targetGainSide - smoothedGainSide_) * ((targetGainSide > smoothedGainSide_) ? 0.045 : 0.005);

        double airWeightMid = isBreathContext ? 0.16 : 0.08;

        // 波形整形のスケール係数 (h2 は Mid/Side 共通、h3/h4 は Side のみ異なる)
        const double h2Scale = isRichHarmonics_ ? 1.35 : 1.0;
        const double h3ScaleMid = isRichHarmonics_ ? 0.08 : 0.42;
        const double h4ScaleMid = isRichHarmonics_ ? 0.12 : 0.18;
        const double h3ScaleSide = isRichHarmonics_ ? 0.08 : 0.44;
        const double h4ScaleSide = isRichHarmonics_ ? 0.12 : 0.20;

        double harmMid;
        double harmSide;
        if (!useQmf_) {
            // 単帯域パス: shapeHarmonics は現行のインライン式の移設であり、
            // 渡す係数・重みが従来と同一のため演算結果はビット同一になる
            harmMid = shapeHarmonics(hiMid, rmsMid, h2Scale, h3ScaleMid, h4ScaleMid,
                                      effEven, effOdd, airWeightMid);
            harmSide = shapeHarmonics(hiSide, rmsSide, h2Scale, h3ScaleSide, h4ScaleSide,
                                      effEven * 0.95, effOdd * 0.80, 0.15);
        } else {
            // QMF パス: 抽出帯域 (hiMid / hiSide) を LR4 クロスオーバーで
            // 低/高サブバンドへ分割し、サブバンド毎に同じ整形を掛けて合算する。
            // 低サブバンドは現行ミックス (h2 中心の太さ・芯)、
            // 高サブバンドは h3 (奇数次=刺さり要因) を ×0.5 に抑え、h4 (air) を ×1.5 に上げる。
            // 解析段 (rms / ゲイン平滑) は全帯域信号で従来通り更新済みであり、
            // 合算後は従来と同じ帯域制限段 (out HP + Silk LP) へ渡る。
            double midLo = runBiquad(hiMid, qmf_lp_b0_, qmf_lp_b1_, qmf_lp_b2_, qmf_lp_a1_, qmf_lp_a2_,
                                     qmf_lp1_s1_mid_, qmf_lp1_s2_mid_);
            midLo = runBiquad(midLo, qmf_lp_b0_, qmf_lp_b1_, qmf_lp_b2_, qmf_lp_a1_, qmf_lp_a2_,
                              qmf_lp2_s1_mid_, qmf_lp2_s2_mid_);
            double midHi = runBiquad(hiMid, qmf_hp_b0_, qmf_hp_b1_, qmf_hp_b2_, qmf_hp_a1_, qmf_hp_a2_,
                                     qmf_hp1_s1_mid_, qmf_hp1_s2_mid_);
            midHi = runBiquad(midHi, qmf_hp_b0_, qmf_hp_b1_, qmf_hp_b2_, qmf_hp_a1_, qmf_hp_a2_,
                              qmf_hp2_s1_mid_, qmf_hp2_s2_mid_);

            double sideLo = runBiquad(hiSide, qmf_lp_b0_, qmf_lp_b1_, qmf_lp_b2_, qmf_lp_a1_, qmf_lp_a2_,
                                      qmf_lp1_s1_side_, qmf_lp1_s2_side_);
            sideLo = runBiquad(sideLo, qmf_lp_b0_, qmf_lp_b1_, qmf_lp_b2_, qmf_lp_a1_, qmf_lp_a2_,
                               qmf_lp2_s1_side_, qmf_lp2_s2_side_);
            double sideHi = runBiquad(hiSide, qmf_hp_b0_, qmf_hp_b1_, qmf_hp_b2_, qmf_hp_a1_, qmf_hp_a2_,
                                      qmf_hp1_s1_side_, qmf_hp1_s2_side_);
            sideHi = runBiquad(sideHi, qmf_hp_b0_, qmf_hp_b1_, qmf_hp_b2_, qmf_hp_a1_, qmf_hp_a2_,
                               qmf_hp2_s1_side_, qmf_hp2_s2_side_);

            harmMid = shapeHarmonics(midLo, rmsMid, h2Scale, h3ScaleMid, h4ScaleMid,
                                     effEven, effOdd, airWeightMid)
                    + shapeHarmonics(midHi, rmsMid, h2Scale, h3ScaleMid, h4ScaleMid,
                                     effEven, effOdd * 0.5, airWeightMid * 1.5);
            harmSide = shapeHarmonics(sideLo, rmsSide, h2Scale, h3ScaleSide, h4ScaleSide,
                                      effEven * 0.95, effOdd * 0.80, 0.15)
                     + shapeHarmonics(sideHi, rmsSide, h2Scale, h3ScaleSide, h4ScaleSide,
                                      effEven * 0.95, effOdd * 0.80 * 0.5, 0.15 * 1.5);
        }

        double harmL = (harmMid * smoothedGainMid_) + (harmSide * smoothedGainSide_);
        double harmR = (harmMid * smoothedGainMid_) - (harmSide * smoothedGainSide_);

        double outHarmL = out_hp_b0_ * harmL + out_s1_L_;
        out_s1_L_ = out_hp_b1_ * harmL - out_hp_a1_ * outHarmL + out_s2_L_;
        out_s2_L_ = out_hp_b2_ * harmL - out_hp_a2_ * outHarmL;

        double silkHarmL = silk_lp_b0_ * outHarmL + silk_s1_L_;
        silk_s1_L_ = silk_lp_b1_ * outHarmL - silk_lp_a1_ * silkHarmL + silk_s2_L_;
        silk_s2_L_ = silk_lp_b2_ * outHarmL - silk_lp_a2_ * silkHarmL;

        double outHarmR = out_hp_b0_ * harmR + out_s1_R_;
        out_s1_R_ = out_hp_b1_ * harmR - out_hp_a1_ * outHarmR + out_s2_R_;
        out_s2_R_ = out_hp_b2_ * harmR - out_hp_a2_ * outHarmR;

        double silkHarmR = silk_lp_b0_ * outHarmR + silk_s1_R_;
        silk_s1_R_ = silk_lp_b1_ * outHarmR - silk_lp_a1_ * silkHarmR + silk_s2_R_;
        silk_s2_R_ = silk_lp_b2_ * outHarmR - silk_lp_a2_ * silkHarmR;

        // ★ DC除去フィルター (120Hz 1次ハイパス): 2次倍音合成による直流バイアスとウーファー偏流を完全遮断
        double cleanHarmL = silkHarmL - dc_xL_ + dcCutCoeff_ * dc_yL_;
        dc_xL_ = silkHarmL; dc_yL_ = cleanHarmL;

        double cleanHarmR = silkHarmR - dc_xR_ + dcCutCoeff_ * dc_yR_;
        dc_xR_ = silkHarmR; dc_yR_ = cleanHarmR;

        double totalL = inL + cleanHarmL;
        double totalR = inR + cleanHarmR;

        left[i] = static_cast<float>(std::clamp(totalL, -1.0, 1.0));
        right[i] = static_cast<float>(std::clamp(totalR, -1.0, 1.0));
    }
}