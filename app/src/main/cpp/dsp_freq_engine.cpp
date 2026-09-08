#include "dsp_freq_engine.h"
#include <algorithm>

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
        return;
    }
    isBypass_ = false;

    fExtract_ = (extractFreq > 6000.0f) ? static_cast<double>(extractFreq) : (isRichHarmonics_ ? 12000.0 : 13000.0);

    // ★ RICH HARMONICS: 15.8kHz〜 (ざらつきのない澄んだ帯域から滑らかに立ち上げ)
    double fOutHp = isRichHarmonics_ ? 15800.0 : 19800.0;
    
    // ふくよかモード時はざらつきの原因となる奇数次(3次)を徹底排除し、純粋で温かい2次倍音を 96% に集中
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

    double w0_in = 2.0 * DSP_PI * fExtract_ / sampleRate_;
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

    // ふくよかモード時は高域端をシルキーにロールオフさせてデジタル感を払拭
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
    r0_Mid_ = 1e-4; r0_Side_ = 1e-4;
    smoothedGainMid_ = 0.0; smoothedGainSide_ = 0.0;
    prevPowMid_ = 0.0; prevPowSide_ = 0.0;
    transientFluxMid_ = 0.0; transientFluxSide_ = 0.0;
    noiseFloorMid_ = 1e-5; noiseFloorSide_ = 1e-5;
}

void DspFreqEngine::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

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

        double targetGainMid = std::min(rmsMid * (isRichHarmonics_ ? 0.85 : 0.70), static_cast<double>(targetGain_ * modeGainScale_ * baseMidGain)) * floorGateMid;
        smoothedGainMid_ += (targetGainMid - smoothedGainMid_) * ((targetGainMid > smoothedGainMid_) ? 0.040 : 0.004);

        double targetGainSide = std::min(rmsSide * (isRichHarmonics_ ? 1.00 : 0.90), static_cast<double>(targetGain_ * modeGainScale_ * baseSideGain)) * floorGateSide;
        smoothedGainSide_ += (targetGainSide - smoothedGainSide_) * ((targetGainSide > smoothedGainSide_) ? 0.045 : 0.005);

        // ★ ソフトサチュレーション型非線形カーブ（スパイクとざらつきを物理阻止）
        double normMid = std::clamp(hiMid / (rmsMid * 1.414 + 1e-5), -2.5, 2.5);
        double softNormMid = normMid / (1.0 + 0.25 * std::abs(normMid));
        double normSqMid = softNormMid * softNormMid;
        double h2_Mid = (normSqMid - 0.42) * (rmsMid * (isRichHarmonics_ ? 1.35 : 1.0));
        double h3_Mid = (softNormMid * normSqMid - 0.60 * softNormMid) * (rmsMid * (isRichHarmonics_ ? 0.08 : 0.42));
        double h4_Mid = (normSqMid * normSqMid - 0.90 * normSqMid + 0.15) * (rmsMid * (isRichHarmonics_ ? 0.12 : 0.18));
        double airWeightMid = isBreathContext ? 0.16 : 0.08;
        double harmMid = (effEven * h2_Mid + effOdd * h3_Mid + airWeightMid * h4_Mid);

        double normSide = std::clamp(hiSide / (rmsSide * 1.414 + 1e-5), -2.5, 2.5);
        double softNormSide = normSide / (1.0 + 0.25 * std::abs(normSide));
        double normSqSide = softNormSide * softNormSide;
        double h2_Side = (normSqSide - 0.42) * (rmsSide * (isRichHarmonics_ ? 1.35 : 1.0));
        double h3_Side = (softNormSide * normSqSide - 0.60 * softNormSide) * (rmsSide * (isRichHarmonics_ ? 0.08 : 0.44));
        double h4_Side = (normSqSide * normSqSide - 0.90 * normSqSide + 0.15) * (rmsSide * (isRichHarmonics_ ? 0.12 : 0.20));
        double harmSide = (effEven * 0.95 * h2_Side + effOdd * 0.80 * h3_Side + 0.15 * h4_Side);

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

        double totalL = inL + silkHarmL;
        double totalR = inR + silkHarmR;

        left[i] = static_cast<float>(std::clamp(totalL, -1.0, 1.0));
        right[i] = static_cast<float>(std::clamp(totalR, -1.0, 1.0));
    }
}