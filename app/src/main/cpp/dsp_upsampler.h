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

// ★ LPC スペクトル外挿 ＆ DSEE HX AI モード
enum class DseeMode : int {
    OFF = 0,
    DSEE_AI = 1,       // DSEE HX AI (LPC + リアルタイム音響特徴量自動適応)
    K2_LPC = 2,        // JVC K2風 純粋LPC線形予測外挿 (上品・破綻ゼロ)
    ADAPTIVE_EXCITER = 3 // ディテール保護型 トランジェント進相エキサイター
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

// ★ LPC スペクトル外挿 ＆ リアルタイム音響解析 AI エンジン
class DspLpcHarmonicAi {
public:
    DspLpcHarmonicAi();
    void configure(DseeMode mode, double sampleRate);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    DseeMode mode_ = DseeMode::DSEE_AI;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;

    // 10kHz〜16kHz 帯域抽出フィルター
    double hp_b0_ = 1.0, hp_b1_ = -1.0, hp_a1_ = 0.0;
    double hp_s1_L_ = 0.0, hp_s1_R_ = 0.0;

    // 20kHz〜38kHz 超高域 LPC シェーピング BPF
    double bp_b0_ = 1.0, bp_b1_ = 0.0, bp_b2_ = -1.0;
    double bp_a1_ = 0.0, bp_a2_ = 0.0;
    double bp_s1_L_ = 0.0, bp_s2_L_ = 0.0;
    double bp_s1_R_ = 0.0, bp_s2_R_ = 0.0;

    // リアルタイム音響特徴量アナライザー
    double prevSampleL_ = 0.0, prevSampleR_ = 0.0;
    double envHfL_ = 0.0, envHfR_ = 0.0;
    double envTotalL_ = 0.0, envTotalR_ = 0.0;
    double transientFluxL_ = 0.0, transientFluxR_ = 0.0;

    // LPC 自己相関・減衰スロープ
    double lpcAlphaL_ = 0.5, lpcAlphaR_ = 0.5;
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

    void setDirectSource(bool enabled);
    bool isDirectSource() const { return isDirectSource_; }

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

    bool isDirectSource_ = false;

    DitherMode ditherMode_ = DitherMode::TPDF;
    FirFilterType filterType_ = FirFilterType::LINEAR_PHASE_SHARP;
    DcPhaseType dcPhaseType_ = DcPhaseType::A_STD;
    DseeMode dseeMode_ = DseeMode::DSEE_AI;

    double errHistL_[4] = {0.0, 0.0, 0.0, 0.0};
    double errHistR_[4] = {0.0, 0.0, 0.0, 0.0};

    DspEqualizer equalizer_;
    DspDcPhaseLinearizer dcPhaseLinearizer_;
    DspLpcHarmonicAi lpcHarmonicAi_;

    std::vector<std::vector<float, AlignedAllocator<float, 16>>> polyCoeffs_;
    std::vector<float, AlignedAllocator<float, 16>> historyL_;
    std::vector<float, AlignedAllocator<float, 16>> historyR_;

    std::vector<float, AlignedAllocator<float, 16>> tempInL_;
    std::vector<float, AlignedAllocator<float, 16>> tempInR_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutL_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutR_;
};