#include "dsp_upsampler.h"
#include <cmath>
#include <cstring>
#include <algorithm>

constexpr double PI = 3.14159265358979323846;

DspUpsampler::DspUpsampler() {
    configure(1);
}

double DspUpsampler::besselI0(double x) {
    double sum = 1.0;
    double term = 1.0;
    double halfX = x * 0.5;
    for (int k = 1; k <= 30; ++k) {
        term *= (halfX / k);
        double termSq = term * term;
        sum += termSq;
        if (termSq < 1e-16 * sum) break;
    }
    return sum;
}

void DspUpsampler::generateFilterCoefficients(int factor) {
    if (factor <= 1) {
        polyCoeffs_.clear();
        return;
    }

    // 倍率に応じた高品質タップ数設定 (4の倍数アライメント)
    if (factor == 2) {
        tapsPerPhase_ = 64;  // 全体 128 タップ
    } else if (factor == 4) {
        tapsPerPhase_ = 48;  // 全体 192 タップ
    } else if (factor == 8) {
        tapsPerPhase_ = 32;  // 全体 256 タップ
    } else {
        tapsPerPhase_ = 32;
    }

    int totalTaps = factor * tapsPerPhase_;
    double cutoff = 0.94 / (2.0 * factor); // 急峻なカットオフ (Nyquist帯域保護)
    double beta = 10.5; // Kaiser窓パラメータ (阻止域減衰量 > 120dB)
    double i0Beta = besselI0(beta);
    double center = (totalTaps - 1) * 0.5;

    std::vector<double> protoFilter(totalTaps);
    double sumGain = 0.0;

    for (int i = 0; i < totalTaps; ++i) {
        double t = i - center;
        // Sinc
        double sinc = (t == 0.0) ? 1.0 : (std::sin(2.0 * PI * cutoff * t) / (PI * t));
        // Kaiser Window
        double norm = (2.0 * i / (totalTaps - 1)) - 1.0;
        double arg = 1.0 - norm * norm;
        double window = (arg >= 0.0) ? (besselI0(beta * std::sqrt(arg)) / i0Beta) : 0.0;

        protoFilter[i] = sinc * window;
        sumGain += protoFilter[i];
    }

    // 0dB (Unity Gain) 正規化: 各サブバンドの通過ゲインを 1.0 にスケーリング
    double scale = static_cast<double>(factor) / sumGain;

    polyCoeffs_.resize(factor);
    for (int p = 0; p < factor; ++p) {
        polyCoeffs_[p].resize(tapsPerPhase_);
        for (int k = 0; k < tapsPerPhase_; ++k) {
            int protoIdx = k * factor + p;
            if (protoIdx < totalTaps) {
                polyCoeffs_[p][k] = static_cast<float>(protoFilter[protoIdx] * scale);
            } else {
                polyCoeffs_[p][k] = 0.0f;
            }
        }
    }

    historyLen_ = tapsPerPhase_ * 4;
    historyL_.assign(historyLen_, 0.0f);
    historyR_.assign(historyLen_, 0.0f);
    historyWritePos_ = tapsPerPhase_ - 1;
}

void DspUpsampler::configure(int factor) {
    if (factor_ == factor && !polyCoeffs_.empty()) return;
    factor_ = (factor == 2 || factor == 4 || factor == 8) ? factor : 1;
    generateFilterCoefficients(factor_);
    reset();
}

void DspUpsampler::reset() {
    if (!historyL_.empty()) {
        std::fill(historyL_.begin(), historyL_.end(), 0.0f);
        std::fill(historyR_.begin(), historyR_.end(), 0.0f);
        historyWritePos_ = tapsPerPhase_ - 1;
    }
}

size_t DspUpsampler::process(
    const uint8_t* inPcm,
    size_t inBytes,
    const char* inBitMode,
    const char* outBitMode,
    std::vector<uint8_t>& outBuffer
) {
    if (!inPcm || inBytes == 0) return 0;

    int inBytesPerSample = 2;
    if (strcmp(inBitMode, "32bit") == 0) inBytesPerSample = 4;
    else if (strcmp(inBitMode, "24bit") == 0) inBytesPerSample = 3;

    int inBytesPerFrame = inBytesPerSample * 2; // Stereo
    size_t numInFrames = inBytes / inBytesPerFrame;
    if (numInFrames == 0) return 0;

    // 1. 入力PCMをFloat [-1.0, 1.0] にアンパック
    tempInL_.resize(numInFrames);
    tempInR_.resize(numInFrames);

    if (inBytesPerSample == 4) { // 32-bit Int
        const auto* src32 = reinterpret_cast<const int32_t*>(inPcm);
        for (size_t i = 0; i < numInFrames; ++i) {
            tempInL_[i] = static_cast<float>(src32[i * 2]) / 2147483648.0f;
            tempInR_[i] = static_cast<float>(src32[i * 2 + 1]) / 2147483648.0f;
        }
    } else if (inBytesPerSample == 3) { // 24-bit Packed
        for (size_t i = 0; i < numInFrames; ++i) {
            size_t base = i * 6;
            int32_t valL = static_cast<int32_t>((inPcm[base]) | (inPcm[base + 1] << 8) | (inPcm[base + 2] << 16));
            if (valL & 0x800000) valL |= 0xFF000000;
            int32_t valR = static_cast<int32_t>((inPcm[base + 3]) | (inPcm[base + 4] << 8) | (inPcm[base + 5] << 16));
            if (valR & 0x800000) valR |= 0xFF000000;

            tempInL_[i] = static_cast<float>(valL) / 8388608.0f;
            tempInR_[i] = static_cast<float>(valR) / 8388608.0f;
        }
    } else { // 16-bit Int
        const auto* src16 = reinterpret_cast<const int16_t*>(inPcm);
        for (size_t i = 0; i < numInFrames; ++i) {
            tempInL_[i] = static_cast<float>(src16[i * 2]) / 32768.0f;
            tempInR_[i] = static_cast<float>(src16[i * 2 + 1]) / 32768.0f;
        }
    }

    size_t numOutFrames = numInFrames * factor_;
    tempOutL_.resize(numOutFrames);
    tempOutR_.resize(numOutFrames);

    // 2. アップサンプリング（1x の場合はバイパスコピー、2x/4x/8x は NEON ポリフェーズ補間）
    if (factor_ <= 1) {
        std::memcpy(tempOutL_.data(), tempInL_.data(), numInFrames * sizeof(float));
        std::memcpy(tempOutR_.data(), tempInR_.data(), numInFrames * sizeof(float));
    } else {
        const int subTaps = tapsPerPhase_;

        for (size_t i = 0; i < numInFrames; ++i) {
            // ヒストリバッファに最新サンプルを書き込み
            historyL_[historyWritePos_] = tempInL_[i];
            historyR_[historyWritePos_] = tempInR_[i];

            for (int p = 0; p < factor_; ++p) {
                const float* coeffPtr = polyCoeffs_[p].data();
                const float* histPtrL = &historyL_[historyWritePos_ - (subTaps - 1)];
                const float* histPtrR = &historyR_[historyWritePos_ - (subTaps - 1)];

                float sumL = 0.0f;
                float sumR = 0.0f;

#if USE_ARM_NEON
                float32x4_t accL = vdupq_n_f32(0.0f);
                float32x4_t accR = vdupq_n_f32(0.0f);

                for (int k = 0; k < subTaps; k += 4) {
                    float32x4_t c = vld1q_f32(coeffPtr + k);
                    float32x4_t xL = vld1q_f32(histPtrL + k);
                    float32x4_t xR = vld1q_f32(histPtrR + k);
                    accL = vmlaq_f32(accL, c, xL);
                    accR = vmlaq_f32(accR, c, xR);
                }

                // 水平加算
#if defined(__aarch64__)
                sumL = vaddvq_f32(accL);
                sumR = vaddvq_f32(accR);
#else
                float32x2_t rL = vadd_f32(vget_low_f32(accL), vget_high_f32(accL));
                float32x2_t rR = vadd_f32(vget_low_f32(accR), vget_high_f32(accR));
                sumL = vget_lane_f32(vpadd_f32(rL, rL), 0);
                sumR = vget_lane_f32(vpadd_f32(rR, rR), 0);
#endif

#else
                for (int k = 0; k < subTaps; ++k) {
                    sumL += coeffPtr[k] * histPtrL[k];
                    sumR += coeffPtr[k] * histPtrR[k];
                }
#endif
                tempOutL_[i * factor_ + p] = sumL;
                tempOutR_[i * factor_ + p] = sumR;
            }

            historyWritePos_++;
            // バッファ末端に達したら、最新の (subTaps - 1) サンプルを先頭へシフト
            if (historyWritePos_ >= historyLen_ - 1) {
                int overlap = subTaps - 1;
                std::memmove(&historyL_[0], &historyL_[historyWritePos_ - overlap], overlap * sizeof(float));
                std::memmove(&historyR_[0], &historyR_[historyR_.size() - overlap], overlap * sizeof(float));
                historyWritePos_ = overlap;
            }
        }
    }

    // 3. 出力PCMフォーマットへパック (16bit / 24bit Packed / 32bit Int)
    int outBytesPerSample = 2;
    if (strcmp(outBitMode, "32bit") == 0) outBytesPerSample = 4;
    else if (strcmp(outBitMode, "24bit") == 0) outBytesPerSample = 3;

    size_t outTotalBytes = numOutFrames * outBytesPerSample * 2;
    outBuffer.resize(outTotalBytes);
    uint8_t* dst = outBuffer.data();

    if (outBytesPerSample == 4) { // 32-bit Int
        auto* dst32 = reinterpret_cast<int32_t*>(dst);
        for (size_t i = 0; i < numOutFrames; ++i) {
            float l = std::clamp(tempOutL_[i], -1.0f, 1.0f);
            float r = std::clamp(tempOutR_[i], -1.0f, 1.0f);
            dst32[i * 2]     = static_cast<int32_t>(l >= 0.0f ? (l * 2147483647.0f) : (l * 2147483648.0f));
            dst32[i * 2 + 1] = static_cast<int32_t>(r >= 0.0f ? (r * 2147483647.0f) : (r * 2147483648.0f));
        }
    } else if (outBytesPerSample == 3) { // 24-bit Packed
        for (size_t i = 0; i < numOutFrames; ++i) {
            float l = std::clamp(tempOutL_[i], -1.0f, 1.0f);
            float r = std::clamp(tempOutR_[i], -1.0f, 1.0f);
            int32_t intL = static_cast<int32_t>(l >= 0.0f ? (l * 8388607.0f) : (l * 8388608.0f));
            int32_t intR = static_cast<int32_t>(r >= 0.0f ? (r * 8388607.0f) : (r * 8388608.0f));
            if (intL < 0) intL = 0x1000000 + intL;
            if (intR < 0) intR = 0x1000000 + intR;

            size_t base = i * 6;
            dst[base]     = intL & 0xFF;
            dst[base + 1] = (intL >> 8) & 0xFF;
            dst[base + 2] = (intL >> 16) & 0xFF;
            dst[base + 3] = intR & 0xFF;
            dst[base + 4] = (intR >> 8) & 0xFF;
            dst[base + 5] = (intR >> 16) & 0xFF;
        }
    } else { // 16-bit Int
        auto* dst16 = reinterpret_cast<int16_t*>(dst);
        for (size_t i = 0; i < numOutFrames; ++i) {
            float l = std::clamp(tempOutL_[i], -1.0f, 1.0f);
            float r = std::clamp(tempOutR_[i], -1.0f, 1.0f);
            dst16[i * 2]     = static_cast<int16_t>(l >= 0.0f ? (l * 32767.0f) : (l * 32768.0f));
            dst16[i * 2 + 1] = static_cast<int16_t>(r >= 0.0f ? (r * 32767.0f) : (r * 32768.0f));
        }
    }

    return outTotalBytes;
}
