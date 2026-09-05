#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>
#include <memory>
#include <array>
#include <atomic>
#include "dsp_equalizer.h"

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define USE_ARM_NEON 1
#else
#define USE_ARM_NEON 0
#endif

enum class PerformanceMode : int {
    ECO = 0,
    STANDARD = 1,
    ULTRA_HQ = 2
};

enum class DitherMode : int {
    NONE = 0,
    TPDF = 1,
    HIGH_PASS_SHAPED = 2,
    PSYCHOACOUSTIC = 3
};

enum class FirFilterType : int {
    LINEAR_PHASE_SHARP = 0,
    LINEAR_PHASE_SLOW = 1,
    MINIMUM_PHASE_SHARP = 2,
    MINIMUM_PHASE_SLOW = 3
};

enum class DcPhaseType : int {
    OFF = 0,
    A_LOW = 1,
    A_STD = 2,
    A_HIGH = 3,
    B_LOW = 4,
    B_STD = 5,
    B_HIGH = 6
};

enum class FreqMode : int {
    OFF = 0,
    AUTO_AI = 1,
    STUDIO_VOCAL = 2,
    ACOUSTIC_INSTRUMENT = 3,
    DYNAMIC_PERCUSSION = 4,
    AIR_EXPANDER = 5
};

enum class TransientMode : int {
    OFF = 0,
    NATURAL = 1,
    PUNCH = 2,
    ACOUSTIC = 3
};

template <typename T, size_t Alignment = 16>
struct AlignedAllocator {
    using value_type = T;
    AlignedAllocator() noexcept = default;
    template <typename U> AlignedAllocator(const AlignedAllocator<U, Alignment>&) noexcept {}

    T* allocate(size_t n) {
        if (n == 0) return nullptr;
        void* ptr = nullptr;
        if (posix_memalign(&ptr, Alignment, n * sizeof(T)) != 0) {
            throw std::bad_alloc();
        }
        return static_cast<T*>(ptr);
    }

    void deallocate(T* p, size_t) noexcept {
        free(p);
    }

    template <typename U>
    struct rebind {
        using other = AlignedAllocator<U, Alignment>;
    };
};

template <typename T, typename U, size_t A>
bool operator==(const AlignedAllocator<T, A>&, const AlignedAllocator<U, A>&) { return true; }
template <typename T, typename U, size_t A>
bool operator!=(const AlignedAllocator<T, A>&, const AlignedAllocator<U, A>&) { return false; }

class FirStage2x {
public:
    FirStage2x() = default;
    void configure(size_t numTaps, double cutoffHz, double outputRateHz, FirFilterType filterType);
    void reset();
    void processStereo(const float* inL, const float* inR, size_t numFrames,
                       std::vector<float, AlignedAllocator<float, 16>>& outL,
                       std::vector<float, AlignedAllocator<float, 16>>& outR);
private:
    double besselI0(double x);
    void convertToMinimumPhase(std::vector<double>& h, int totalTaps);

    size_t numTaps_ = 0;
    size_t tapsPerPhase_ = 0;
    std::vector<float, AlignedAllocator<float, 16>> poly0_;
    std::vector<float, AlignedAllocator<float, 16>> poly1_;
    std::vector<float, AlignedAllocator<float, 16>> mirrorHistL_;
    std::vector<float, AlignedAllocator<float, 16>> mirrorHistR_;
    int writePos_ = 0;
};

class DspDcPhaseLinearizer {
public:
    DspDcPhaseLinearizer();
    void configure(DcPhaseType type, double sampleRate);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    DcPhaseType type_ = DcPhaseType::A_STD;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;

    double b0_ = 1.0, b1_ = 0.0, b2_ = 0.0;
    double a1_ = 0.0, a2_ = 0.0;
    double s1_L_ = 0.0, s2_L_ = 0.0;
    double s1_R_ = 0.0, s2_R_ = 0.0;
};

class DspTransientRestorer {
public:
    DspTransientRestorer();
    void configure(TransientMode mode, double sampleRate, bool useGroupDelay = false, bool useLattice = false);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    TransientMode mode_ = TransientMode::NATURAL;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;
    bool useGroupDelay_ = false;
    bool useLattice_ = false;

    double attackGain_ = 1.2;
    double fastAlpha_ = 0.04;
    double slowAlpha_ = 0.002;

    double latK1_L_ = 0.0, latK2_L_ = 0.0;
    double latK1_R_ = 0.0, latK2_R_ = 0.0;
    double latB1_L_ = 0.0, latB2_L_ = 0.0;
    double latB1_R_ = 0.0, latB2_R_ = 0.0;

    double envFastL_ = 0.0, envSlowL_ = 0.0;
    double envFastR_ = 0.0, envSlowR_ = 0.0;
    double prevSampleL_ = 0.0, prevSampleR_ = 0.0;
};

class DspFreqEngine {
public:
    DspFreqEngine();
    void configure(FreqMode mode, double sampleRate, float gain = 0.26f, float extractFreq = 7200.0f);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);
    void setPerformanceMode(PerformanceMode mode) { perfMode_ = mode; }

private:
    void runLpcAnalysis();

    FreqMode mode_ = FreqMode::AUTO_AI;
    PerformanceMode perfMode_ = PerformanceMode::STANDARD;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;
    float targetGain_ = 0.26f;

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

    // 口腔フォルマント検出 (3.2kHz BPF)
    double formant_bp_b0_ = 0.0, formant_bp_b1_ = 0.0, formant_bp_b2_ = 0.0;
    double formant_bp_a1_ = 0.0, formant_bp_a2_ = 0.0;
    double formant_s1_L_ = 0.0, formant_s2_L_ = 0.0;
    double formant_s1_R_ = 0.0, formant_s2_R_ = 0.0;

    // ★ 1. アンチ・リンギング (20kHz 遮断リンギング相殺 BPF)
    double ar_bp_b0_ = 0.0, ar_bp_b1_ = 0.0, ar_bp_b2_ = 0.0;
    double ar_bp_a1_ = 0.0, ar_bp_a2_ = 0.0;
    double ar_s1_L_ = 0.0, ar_s2_L_ = 0.0;
    double ar_s1_R_ = 0.0, ar_s2_R_ = 0.0;

    // ★ 2. 心理音響リバース・マスキング (中域基音エネルギー追従 BPF)
    double mask_bp_b0_ = 0.0, mask_bp_b1_ = 0.0, mask_bp_b2_ = 0.0;
    double mask_bp_a1_ = 0.0, mask_bp_a2_ = 0.0;
    double mask_s1_L_ = 0.0, mask_s2_L_ = 0.0;
    double mask_s1_R_ = 0.0, mask_s2_R_ = 0.0;
    double maskMidPowerL_ = 1e-4, maskMidPowerR_ = 1e-4;

    double evenRatio_ = 0.70;
    double oddRatio_ = 0.30;
    double modeGainScale_ = 1.25;

    double r0_L_ = 1e-4, r0_R_ = 1e-4;
    double smoothedGainL_ = 0.0, smoothedGainR_ = 0.0;

    double prevPowL_ = 0.0, prevPowR_ = 0.0;
    double transientFluxL_ = 0.0, transientFluxR_ = 0.0;
    double noiseFloorL_ = 1e-5, noiseFloorR_ = 1e-5;

    // 8次 LPC 物理モデリングステート
    static constexpr int LPC_ORDER = 8;
    static constexpr int LPC_FRAME_SIZE = 128;
    double lpcBuffer_[LPC_FRAME_SIZE] = {0.0};
    int lpcPos_ = 0;
    int lpcCounter_ = 0;
    double lpcCoeffs_[LPC_ORDER] = {0.0};
    double lpcHistL_[LPC_ORDER] = {0.0};
    double lpcHistR_[LPC_ORDER] = {0.0};

    // 動的スペクトル包絡スロープ追従ステート
    double slope_bpA_b0_ = 0.0, slope_bpA_b1_ = 0.0, slope_bpA_b2_ = 0.0;
    double slope_bpA_a1_ = 0.0, slope_bpA_a2_ = 0.0;
    double slope_s1_A_L_ = 0.0, slope_s2_A_L_ = 0.0;
    double slope_s1_A_R_ = 0.0, slope_s2_A_R_ = 0.0;

    double slope_bpB_b0_ = 0.0, slope_bpB_b1_ = 0.0, slope_bpB_b2_ = 0.0;
    double slope_bpB_a1_ = 0.0, slope_bpB_a2_ = 0.0;
    double slope_s1_B_L_ = 0.0, slope_s2_B_L_ = 0.0;
    double slope_s1_B_R_ = 0.0, slope_s2_B_R_ = 0.0;

    double slopePowerA_L_ = 1e-5, slopePowerB_L_ = 1e-6;
    double slopePowerA_R_ = 1e-5, slopePowerB_R_ = 1e-6;
    double smoothedSlopeL_ = -9.0, smoothedSlopeR_ = -9.0;
};

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
    float detectedCutoffHz_ = 16000.0f;
    float sbrPhaseL_ = 0.0f, sbrPhaseR_ = 0.0f;

    PerformanceMode perfMode_ = PerformanceMode::STANDARD;
    DitherMode ditherMode_ = DitherMode::TPDF;
    FirFilterType filterType_ = FirFilterType::MINIMUM_PHASE_SHARP;
    DcPhaseType dcPhaseType_ = DcPhaseType::A_STD;
    FreqMode freqMode_ = FreqMode::AUTO_AI;

    TransientMode transientMode_ = TransientMode::ACOUSTIC;
    bool customUseGroupDelay_ = true;
    bool customUseLattice_ = false;

    float customFreqGain_ = 0.26f;
    float customFreqExtractFreq_ = 7200.0f;

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;
    DspDcPhaseLinearizer dcPhaseLinearizer_;
    DspTransientRestorer transientRestorer_;
    DspFreqEngine freqEngine_;

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