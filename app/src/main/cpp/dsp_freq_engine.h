#pragma once
#include "dsp_types.h"

class DspFreqEngine {
public:
    DspFreqEngine();
    void configure(FreqMode mode, double sampleRate, float gain = 0.22f, float extractFreq = 13000.0f);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);
    void setPerformanceMode(PerformanceMode mode) { perfMode_ = mode; }
    
    // ★ ふくよか倍音スイッチ (ON: 16kHz〜偶数次倍音ブレンド / OFF: 19.8kHz〜リアルHi-Res)
    void setRichHarmonics(bool enabled) {
        if (isRichHarmonics_ != enabled) {
            isRichHarmonics_ = enabled;
            configure(mode_, sampleRate_, targetGain_, static_cast<float>(fExtract_));
        }
    }
    bool isRichHarmonics() const { return isRichHarmonics_; }

private:
    FreqMode mode_ = FreqMode::AUTO_AI;
    PerformanceMode perfMode_ = PerformanceMode::STANDARD;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;
    bool isRichHarmonics_ = false;
    float targetGain_ = 0.22f;
    double fExtract_ = 13000.0;

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

    double evenRatio_ = 0.65;
    double oddRatio_ = 0.35;
    double modeGainScale_ = 1.15;

    double r0_Mid_ = 1e-4, r0_Side_ = 1e-4;
    double smoothedGainMid_ = 0.0, smoothedGainSide_ = 0.0;

    double prevPowMid_ = 0.0, prevPowSide_ = 0.0;
    double transientFluxMid_ = 0.0, transientFluxSide_ = 0.0;
    double noiseFloorMid_ = 1e-5, noiseFloorSide_ = 1e-5;
};