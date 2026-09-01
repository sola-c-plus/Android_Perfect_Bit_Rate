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

// ★ SONY 実機リバースエンジニアリング準拠 DC Phase Linearizer
enum class DcPhaseType : int {
    OFF = 0,
    A_LOW = 1,   // A Curve 2Hz
    A_STD = 2,   // A Curve 4Hz (Walkman Default)
    A_HIGH = 3,  // A Curve 8Hz
    B_LOW = 4,   // B Curve 2Hz Enhanced
    B_STD = 5,   // B Curve 4Hz Enhanced
    B_HIGH = 6   // B Curve 8Hz Enhanced
};

// ★ 64-bit 倍精度 DC Phase Linearizer IIR フィルター
class DspDcPhaseLinearizer {
public:
    DspDcPhaseLinearizer();
    void configure(DcPhaseType type, double sampleRate);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    DcPhaseType type_ = DcPhaseType::OFF;
    double sampleRate_ = 48000.0;

    double b0_ = 1.0, b1_ = 0.0, b2_ = 0.0;
    double a1_ = 0.0, a2_ = 0.0;
    double s1_L_ = 0.0, s2_L_ = 0.0;
    double s1_R_ = 0.0, s2_R_ = 0.0;
    bool isBypass_ = true;
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

class DspUpsampler {
public:
    DspUpsampler();
    ~DspUpsampler() = default;

    void configure(int factor, float inSampleRate = 48000.0f);
    int getFactor() const { return factor_; }
    DspEqualizer& getEqualizer() { return equalizer_; }

    void setDitherMode(DitherMode mode);
    DitherMode getDitherMode() const { return ditherMode_; }

    void setFirFilterType(FirFilterType type);
    FirFilterType getFirFilterType() const { return filterType_; }

    void setDcPhaseType(DcPhaseType type);
    DcPhaseType getDcPhaseType() const { return dcPhaseType_; }

    size_t process(
        const uint8_t* inPcm,
        size_t inBytes,
        const char* inBitMode,
        const char* outBitMode,
        std::vector<uint8_t>& outBuffer
    );

    void reset();

private:
    void generateFilterCoefficients(int factor);
    void convertToMinimumPhase(std::vector<double>& h, int totalTaps);
    double besselI0(double x);

    int factor_ = 1;
    float inSampleRate_ = 48000.0f;
    int tapsPerPhase_ = 32;
    int historyLen_ = 128;
    int historyWritePos_ = 0;

    DitherMode ditherMode_ = DitherMode::TPDF;
    FirFilterType filterType_ = FirFilterType::LINEAR_PHASE_SHARP;
    DcPhaseType dcPhaseType_ = DcPhaseType::A_STD;

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;
    DspDcPhaseLinearizer dcPhaseLinearizer_;

    std::vector<std::vector<float, AlignedAllocator<float, 16>>> polyCoeffs_;
    std::vector<float, AlignedAllocator<float, 16>> historyL_;
    std::vector<float, AlignedAllocator<float, 16>> historyR_;

    std::vector<float, AlignedAllocator<float, 16>> tempInL_;
    std::vector<float, AlignedAllocator<float, 16>> tempInR_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutL_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutR_;
};