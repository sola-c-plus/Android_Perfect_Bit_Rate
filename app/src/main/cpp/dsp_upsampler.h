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

// ★ 高音補完 (DSEE風 ハーモニック・エクステンダー) モード
enum class DseeMode : int {
    OFF = 0,
    STANDARD = 1, // 自然な高域・空気感
    VOCAL = 2,    // 女性ボーカル・息づかい・艶
    DYNAMIC = 3   // シンバル・抜けの良さ・ハイレゾ開放感
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

// ★ YouTube Opus 特化型 超高域・倍音復元 DSP
class DspHarmonicRestorer {
public:
    DspHarmonicRestorer();
    void configure(DseeMode mode, double sampleRate);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    DseeMode mode_ = DseeMode::STANDARD;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;
    double blendGain_ = 0.08;

    // 抽出用 HPF フィルター (10kHz)
    double hp_b0_ = 1.0, hp_b1_ = -1.0;
    double hp_a1_ = 0.0;
    double hp_s1_L_ = 0.0, hp_s1_R_ = 0.0;

    // 整形用 BPF フィルター (16kHz〜35kHz)
    double bp_b0_ = 1.0, bp_b1_ = 0.0, bp_b2_ = -1.0;
    double bp_a1_ = 0.0, bp_a2_ = 0.0;
    double bp_s1_L_ = 0.0, bp_s2_L_ = 0.0;
    double bp_s1_R_ = 0.0, bp_s2_R_ = 0.0;

    // エンベロープ追従
    double envL_ = 0.0, envR_ = 0.0;
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

    void setDseeMode(DseeMode mode);
    DseeMode getDseeMode() const { return dseeMode_; }

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
    DseeMode dseeMode_ = DseeMode::STANDARD;

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;
    DspDcPhaseLinearizer dcPhaseLinearizer_;
    DspHarmonicRestorer harmonicRestorer_;

    std::vector<std::vector<float, AlignedAllocator<float, 16>>> polyCoeffs_;
    std::vector<float, AlignedAllocator<float, 16>> historyL_;
    std::vector<float, AlignedAllocator<float, 16>> historyR_;

    std::vector<float, AlignedAllocator<float, 16>> tempInL_;
    std::vector<float, AlignedAllocator<float, 16>> tempInR_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutL_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutR_;
};