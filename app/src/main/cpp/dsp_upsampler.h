#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>
#include <memory>
#include <array>
#include "dsp_equalizer.h"

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define USE_ARM_NEON 1
#else
#define USE_ARM_NEON 0
#endif

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

enum class DseeMode : int {
    OFF = 0,
    AUTO_AI = 1,
    MALE_VOCAL = 2,
    FEMALE_VOCAL = 3,
    PERCUSSION = 4,
    STRINGS = 5
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
    std::vector<float, AlignedAllocator<float, 16>> histL_;
    std::vector<float, AlignedAllocator<float, 16>> histR_;
    int histWritePos_ = 0;
    int histLen_ = 0;
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

class DspLpcHarmonicAi {
public:
    DspLpcHarmonicAi();
    void configure(DseeMode mode, double sampleRate, int lpcAlgo = 1, float gain = 0.18f, float extractFreq = 10000.0f, bool useQmf = false);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    DseeMode mode_ = DseeMode::AUTO_AI;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;
    int lpcAlgo_ = 1;
    bool useQmf_ = false;

    double hp_b0_ = 1.0, hp_b1_ = -1.0, hp_a1_ = 0.0;
    double hp_s1_L_ = 0.0, hp_s1_R_ = 0.0;

    double out_hp_b0_ = 1.0, out_hp_b1_ = -2.0, out_hp_b2_ = 1.0;
    double out_hp_a1_ = 0.0, out_hp_a2_ = 0.0;
    double out_s1_L_ = 0.0, out_s2_L_ = 0.0;
    double out_s1_R_ = 0.0, out_s2_R_ = 0.0;

    double dcL_ = 0.0, dcR_ = 0.0;

    double prevSampleL_ = 0.0, prevSampleR_ = 0.0;
    double envHfL_ = 0.0, envHfR_ = 0.0;
    double envTotalL_ = 0.0, envTotalR_ = 0.0;
    double transientFluxL_ = 0.0, transientFluxR_ = 0.0;
    double targetGain_ = 0.25;

    double smoothedGainL_ = 0.0;
    double smoothedGainR_ = 0.0;
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

    void setDseeMode(DseeMode mode);
    void setDseeCustomParams(int lpcAlgo, float gain, float extractFreq, bool useQmf);
    DseeMode getDseeMode() const { return dseeMode_; }

    void setTransientMode(TransientMode mode);
    void setTransientCustomParams(bool useGroupDelay, bool useLattice);
    TransientMode getTransientMode() const { return transientMode_; }

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

    // ★ 32バンド 2次 IIR フィルタバンク構造体
    struct SpecBiquad {
        float b0 = 0.0f, b1 = 0.0f, b2 = 0.0f;
        float a1 = 0.0f, a2 = 0.0f;
        float s1 = 0.0f, s2 = 0.0f;
        float env = 0.0f;
        bool active = true;

        void initBandpass(float f0, float Q, float fs);
        inline float processSample(float in) {
            if (!active) return 0.0f;
            float out = b0 * in + s1;
            s1 = b1 * in - a1 * out + s2;
            s2 = b2 * in - a2 * out;
            return out;
        }
    };

    void initSpectrumFilterBank(float fs);
    void processSpectrumFilterBank(const float* l, const float* r, size_t numFrames);

    int factor_ = 1;
    float inSampleRate_ = 48000.0f;
    int tapsPerPhase_ = 32;
    int historyLen_ = 128;
    int historyWritePos_ = 0;

    bool isDirectSource_ = false;
    bool isCascadeFir_ = true;
    bool lrIndependentDither_ = true;

    DitherMode ditherMode_ = DitherMode::TPDF;
    FirFilterType filterType_ = FirFilterType::MINIMUM_PHASE_SHARP;
    DcPhaseType dcPhaseType_ = DcPhaseType::A_STD;
    DseeMode dseeMode_ = DseeMode::AUTO_AI;
    TransientMode transientMode_ = TransientMode::ACOUSTIC;

    int customLpcAlgo_ = 1;
    float customGain_ = 0.18f;
    float customExtractFreq_ = 10000.0f;
    bool customUseQmf_ = true;

    bool customUseGroupDelay_ = true;
    bool customUseLattice_ = false;

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;
    DspDcPhaseLinearizer dcPhaseLinearizer_;
    DspTransientRestorer transientRestorer_;
    DspLpcHarmonicAi lpcHarmonicAi_;

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

    // ★ 32バンド IIR フィルタバンク
    std::array<SpecBiquad, 32> specFilters_;
    float spectrumDb_[32] = {-60.0f};
};