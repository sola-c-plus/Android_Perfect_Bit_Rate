#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>
#include <memory>
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
    LINEAR_PHASE_SHARP = 0,  // 直線位相・シャープ (原音忠実)
    LINEAR_PHASE_SLOW = 1,   // 直線位相・スロー (滑らか)
    MINIMUM_PHASE_SHARP = 2, // 最小位相・シャープ (プリリンギング0・パンチ力)
    MINIMUM_PHASE_SLOW = 3   // 最小位相・スロー (プリリンギング0・アナログトーン)
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

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;

    std::vector<std::vector<float, AlignedAllocator<float, 16>>> polyCoeffs_;
    std::vector<float, AlignedAllocator<float, 16>> historyL_;
    std::vector<float, AlignedAllocator<float, 16>> historyR_;

    std::vector<float, AlignedAllocator<float, 16>> tempInL_;
    std::vector<float, AlignedAllocator<float, 16>> tempInR_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutL_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutR_;
};