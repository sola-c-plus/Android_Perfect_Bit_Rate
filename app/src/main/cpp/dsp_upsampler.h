#pragma once

#include <vector>
#include <cstdint>
#include <cstddef>
#include <memory>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define USE_ARM_NEON 1
#else
#define USE_ARM_NEON 0
#endif

// 16バイトアライメント対応カスタムアロケータ
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

    void configure(int factor);
    int getFactor() const { return factor_; }

    // 入力バイト列 (16bit/24bit/32bit PCM) をアップサンプリングし、指定出力フォーマットのバイト列として書き込む
    // 戻り値: 出力バイト数
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
    double besselI0(double x);

    int factor_ = 1;
    int tapsPerPhase_ = 32; // 各ポリフェーズのタップ数（4の倍数でNEON最適化）
    int historyLen_ = 128;
    int historyWritePos_ = 0;

    // 16バイトアライメントされたポリフェーズFIR係数 [phase][tap]
    std::vector<std::vector<float, AlignedAllocator<float, 16>>> polyCoeffs_;

    // 16バイトアライメントされたL/Rチャンネル用履歴バッファ
    std::vector<float, AlignedAllocator<float, 16>> historyL_;
    std::vector<float, AlignedAllocator<float, 16>> historyR_;

    // 一時Floatバッファ
    std::vector<float, AlignedAllocator<float, 16>> tempInL_;
    std::vector<float, AlignedAllocator<float, 16>> tempInR_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutL_;
    std::vector<float, AlignedAllocator<float, 16>> tempOutR_;
};
