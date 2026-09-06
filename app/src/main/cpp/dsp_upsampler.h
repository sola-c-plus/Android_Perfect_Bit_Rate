#pragma once

#include "dsp_types.h"
#include "dsp_equalizer.h"
#include "dsp_fir_stage.h"
#include "dsp_preecho.h"
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
    FreqMode getFreqMode() const { return freqMode_; }

    void setPerformanceMode(PerformanceMode mode) {
        perfMode_ = mode;
        freqEngine_.setPerformanceMode(mode);
    }
    PerformanceMode getPerformanceMode() const { return perfMode_; }

    void setTransientMode(TransientMode mode);
    void setTransientCustomParams(bool useGroupDelay, bool useLattice);
    TransientMode getTransientMode() const { return transientMode_; }

    void setMsSpatial(bool enabled);
    bool isMsSpatial() const { return isMsSpatial_; }

    void setDynamicSbr(bool enabled);
    bool isDynamicSbr() const { return isDynamicSbr_; }

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
    void processDynamicSbr(float* left, float* right, size_t numFrames);

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
    float sbrPhaseL_ = 0.0f, sbrPhaseR_ = 0.0f;

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

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;
    DspDcPhaseLinearizer dcPhaseLinearizer_;
    DspTransientRestorer transientRestorer_;
    DspFreqEngine freqEngine_;
    DspAntiPreecho antiPreecho_;
    DspBitContinuity bitContinuity_;

    std::vector<std::vector<float, AlignedAllocator<float, 16>>> polyCoeffs_;
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