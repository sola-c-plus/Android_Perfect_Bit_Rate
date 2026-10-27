#pragma once

#include "dsp_types.h"
#include "dsp_equalizer.h"
#include "dsp_true_peak_guard.h"
#include "dsp_fir_stage.h"
#include "dsp_transient.h"
#include "dsp_freq_engine.h"
#include "dsp_dc_phase.h"
#include <atomic>
#include <array>

class DspUpsampler {
public:
    DspUpsampler();
    ~DspUpsampler() = default;

    void configure(int factor, float inSampleRate = 48000.0f);
    int getFactor() const { return factor_; }
    DspEqualizer& getEqualizer() { return equalizer_; }

    void setDirectSource(bool enabled);
    bool isDirectSource() const { return isDirectSource_; }

    void setCascadeFir(bool enabled);
    bool isCascadeFir() const { return isCascadeFir_; }

    void setDitherMode(DitherMode mode);
    DitherMode getDitherMode() const { return ditherMode_; }

    void setLrIndependentDither(bool enabled);
    bool isLrIndependentDither() const { return lrIndependentDither_; }

    void setFirFilterType(FirFilterType type);
    FirFilterType getFirFilterType() const { return filterType_; }

    void setDcPhaseType(DcPhaseType type);
    DcPhaseType getDcPhaseType() const { return dcPhaseType_; }

    void setFreqMode(FreqMode mode);
    void setFreqCustomParams(float gain, float extractFreq);
    void setFreqAdvancedParams(int lpcAlgo, bool useQmf, double qmfCrossoverHz);
    void setRichHarmonics(bool enabled) { freqEngine_.setRichHarmonics(enabled); }
    bool isRichHarmonics() const { return freqEngine_.isRichHarmonics(); }
    FreqMode getFreqMode() const { return freqMode_; }

    void setPerformanceMode(PerformanceMode mode);
    PerformanceMode getPerformanceMode() const { return perfMode_; }

    void setTransientMode(TransientMode mode);
    void setTransientCustomParams(bool useGroupDelay, bool useLattice);
    TransientMode getTransientMode() const { return transientMode_; }

    void setMsSpatial(bool enabled);
    bool isMsSpatial() const { return isMsSpatial_; }

    void setDynamicSbr(bool enabled);
    bool isDynamicSbr() const { return isDynamicSbr_; }

    // True Peak ガード (独立最終段)。enabled と天井 (dBTP) を保持し、
    // 実績出力レートでガードを構成する。Direct 時は process のバイパス分岐に
    // 入らないため構造的に無効。
    void setTruePeakGuard(bool enabled, double ceilingDb);
    bool isTruePeakGuardEnabled() const { return truePeakEnabled_; }
    // 統計: [現在低減 dB, 最大低減 dB, 発動回数, キャップ到達回数] (表示専用)
    void getTruePeakStats(float* out4) const;

    size_t process(
        const uint8_t* inPcm,
        size_t inBytes,
        const char* inBitMode,
        const char* outBitMode,
        std::vector<uint8_t>& outBuffer
    );

    void reset();
    void getSpectrum(float* out32Bands);

private:
    void generateFilterCoefficients(int factor);
    void convertToMinimumPhase(std::vector<double>& h, int totalTaps);
    double besselI0(double x);
    void executeFftAnalysis();

    void processMsSpatial(float* left, float* right, size_t numFrames);
    void updateDynamicSbr(const float* inL, const float* inR, size_t numFrames, double inputRate);

    int factor_ = 1;
    float inSampleRate_ = 48000.0f;
    int tapsPerPhase_ = 32;
    int historyLen_ = 128;
    int historyWritePos_ = 0;

    bool isDirectSource_ = false;
    bool isCascadeFir_ = true;
    bool lrIndependentDither_ = true;

    bool isMsSpatial_ = false;
    bool isDynamicSbr_ = false;
    float prevSideL_ = 0.0f, prevSideR_ = 0.0f;
    float detectedCutoffHz_ = 20000.0f;
    // Dynamic SBR のカットオフ検出状態 (1ポールHPの状態とブロック内エネルギー蓄積)
    double sbrHpState_ = 0.0;
    double sbrPrevMono_ = 0.0;
    double sbrEnergyHp_ = 0.0;
    double sbrEnergyTot_ = 0.0;
    int sbrBlockCount_ = 0;

    PerformanceMode perfMode_ = PerformanceMode::STANDARD;
    DitherMode ditherMode_ = DitherMode::TPDF;
    FirFilterType filterType_ = FirFilterType::MINIMUM_PHASE_SHARP;
    DcPhaseType dcPhaseType_ = DcPhaseType::A_STD;
    FreqMode freqMode_ = FreqMode::AUTO_AI;

    TransientMode transientMode_ = TransientMode::ACOUSTIC;
    bool customUseGroupDelay_ = true;
    bool customUseLattice_ = false;

    float customFreqGain_ = 0.22f;
    float customFreqExtractFreq_ = 13000.0f;

    // Freq 詳細パラメータ (LPC / QMF)。実体は FreqEngine 側が保持し、
    // FreqEngine::configure が制御値を保持するため再構成でも失われない。
    int customLpcAlgo_ = 0;
    bool customUseQmf_ = false;
    double customQmfXoverHz_ = 16900.0;

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;
    DspTruePeakGuard truePeakGuard_;
    // True Peak ガードの保持設定 (再構成でも失われないよう DspUpsampler が正を持つ)
    bool truePeakEnabled_ = true;
    double truePeakCeilingDb_ = -1.0;
    DspDcPhaseLinearizer dcPhaseLinearizer_;
    DspTransientRestorer transientRestorer_;
    DspFreqEngine freqEngine_;

    std::vector<std::vector<float, AlignedAllocator<float, 16>>> polyCoeffs_;
    // ULTRA_HQ 用の倍精度ポリフェーズ係数。ULTRA_HQ 以外では空 (未使用)。
    std::vector<std::vector<double>> polyCoeffsD_;
    std::vector<float, AlignedAllocator<float, 16>> historyL_;
    std::vector<float, AlignedAllocator<float, 16>> historyR_;

    std::array<FirStage2x, 3> cascadeStages_;
    std::vector<float, AlignedAllocator<float, 16>> stageBuf1_L_, stageBuf1_R_;
    std::vector<float, AlignedAllocator<float, 16>> stageBuf2_L_, stageBuf2_R_;

    std::vector<float, AlignedAllocator<float, 16>> tempInL_;
    std::vector<float, AlignedAllocator<float, 16>> tempInR_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutL_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutR_;

    float spectrumDb_[32] = {-60.0f};
    std::vector<float> specRingBuf_;
    std::atomic<size_t> specRingPos_{0};
};