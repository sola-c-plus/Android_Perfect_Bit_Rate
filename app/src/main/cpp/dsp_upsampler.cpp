#include "dsp_upsampler.h"
#include <cmath>
#include <cstring>
#include <algorithm>

constexpr double PI = 3.14159265358979323846;

static uint32_t g_ditherState1 = 0x87654321;
static uint32_t g_ditherState2 = 0x12345678;

inline double getTpdfDither() {
    g_ditherState1 = g_ditherState1 * 1664525u + 1013904223u;
    g_ditherState2 = g_ditherState2 * 1103515245u + 12345u;
    double r1 = static_cast<double>(g_ditherState1 >> 9) * (1.0 / 8388608.0);
    double r2 = static_cast<double>(g_ditherState2 >> 9) * (1.0 / 8388608.0);
    return (r1 - r2);
}

DspUpsampler::DspUpsampler() {
    configure(1, 48000.0f);
}

void DspUpsampler::setDitherMode(DitherMode mode) {
    ditherMode_ = mode;
    std::fill(std::begin(errHistL_), std::end(errHistL_), 0.0);
    std::fill(std::begin(errHistR_), std::end(errHistR_), 0.0);
}

void DspUpsampler::setFirFilterType(FirFilterType type) {
    if (filterType_ != type) {
        filterType_ = type;
        generateFilterCoefficients(factor_);
        reset();
    }
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

// ★ ヒルベルト/ケプストラム法による完全な最小位相化（プリリンギングゼロ変換）
void DspUpsampler::convertToMinimumPhase(std::vector<double>& h, int totalTaps) {
    int fftSize = 512;
    while (fftSize < totalTaps * 2) fftSize *= 2;

    std::vector<double> logMag(fftSize, 0.0);
    const double eps = 1e-12;

    for (int k = 0; k < fftSize; ++k) {
        double real = 0.0, imag = 0.0;
        for (int n = 0; n < totalTaps; ++n) {
            double angle = -2.0 * PI * k * n / fftSize;
            real += h[n] * std::cos(angle);
            imag += h[n] * std::sin(angle);
        }
        double magSq = real * real + imag * imag;
        logMag[k] = 0.5 * std::log(std::max(magSq, eps));
    }

    std::vector<double> cepstrum(fftSize, 0.0);
    for (int n = 0; n < fftSize; ++n) {
        double sum = 0.0;
        for (int k = 0; k < fftSize; ++k) {
            double angle = 2.0 * PI * k * n / fftSize;
            sum += logMag[k] * std::cos(angle);
        }
        cepstrum[n] = sum / fftSize;
    }

    std::vector<double> causalCepstrum(fftSize, 0.0);
    causalCepstrum[0] = cepstrum[0];
    int half = fftSize / 2;
    for (int n = 1; n < half; ++n) {
        causalCepstrum[n] = 2.0 * cepstrum[n];
    }
    causalCepstrum[half] = cepstrum[half];

    std::vector<double> minReal(fftSize, 0.0);
    std::vector<double> minImag(fftSize, 0.0);
    for (int k = 0; k < fftSize; ++k) {
        double real = 0.0, imag = 0.0;
        for (int n = 0; n < fftSize; ++n) {
            double angle = -2.0 * PI * k * n / fftSize;
            real += causalCepstrum[n] * std::cos(angle);
            imag += causalCepstrum[n] * std::sin(angle);
        }
        double expReal = std::exp(real);
        minReal[k] = expReal * std::cos(imag);
        minImag[k] = expReal * std::sin(imag);
    }

    for (int n = 0; n < totalTaps; ++n) {
        double sum = 0.0;
        for (int k = 0; k < fftSize; ++k) {
            double angle = 2.0 * PI * k * n / fftSize;
            sum += minReal[k] * std::cos(angle) - minImag[k] * std::sin(angle);
        }
        h[n] = sum / fftSize;
    }
}

void DspUpsampler::generateFilterCoefficients(int factor) {
    if (factor <= 1) {
        polyCoeffs_.clear();
        return;
    }

    if (factor == 2) {
        tapsPerPhase_ = 64;
    } else if (factor == 4) {
        tapsPerPhase_ = 48;
    } else {
        tapsPerPhase_ = 32;
    }

    int totalTaps = factor * tapsPerPhase_;
    
    // ★ フィルタータイプ別のパラメータ設定
    double cutoff = 0.94 / (2.0 * factor);
    double beta = 10.5;

    if (filterType_ == FirFilterType::LINEAR_PHASE_SLOW || filterType_ == FirFilterType::MINIMUM_PHASE_SLOW) {
        cutoff = 0.80 / (2.0 * factor);
        beta = 6.0;
    }

    double i0Beta = besselI0(beta);
    double center = (totalTaps - 1) * 0.5;

    std::vector<double> protoFilter(totalTaps);

    for (int i = 0; i < totalTaps; ++i) {
        double t = i - center;
        double sinc = (t == 0.0) ? 1.0 : (std::sin(2.0 * PI * cutoff * t) / (PI * t));
        double norm = (2.0 * i / (totalTaps - 1)) - 1.0;
        double arg = 1.0 - norm * norm;
        double window = (arg >= 0.0) ? (besselI0(beta * std::sqrt(arg)) / i0Beta) : 0.0;
        protoFilter[i] = sinc * window;
    }

    // ★ 最小位相化の適用 (Minimum Phase Sharp / Slow)
    if (filterType_ == FirFilterType::MINIMUM_PHASE_SHARP || filterType_ == FirFilterType::MINIMUM_PHASE_SLOW) {
        convertToMinimumPhase(protoFilter, totalTaps);
    }

    double sumGain = 0.0;
    for (int i = 0; i < totalTaps; ++i) {
        sumGain += protoFilter[i];
    }
    double scale = static_cast<double>(factor) / (sumGain != 0.0 ? sumGain : 1.0);

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

void DspUpsampler::configure(int factor, float inSampleRate) {
    factor_ = (factor == 2 || factor == 4 || factor == 8) ? factor : 1;
    inSampleRate_ = inSampleRate;
    generateFilterCoefficients(factor_);
    equalizer_.setSampleRate(static_cast<double>(inSampleRate_ * factor_));
    reset();
}

void DspUpsampler::reset() {
    if (!historyL_.empty()) {
        std::fill(historyL_.begin(), historyL_.end(), 0.0f);
        std::fill(historyR_.begin(), historyR_.end(), 0.0f);
        historyWritePos_ = tapsPerPhase_ - 1;
    }
    std::fill(std::begin(errHistL_), std::end(errHistL_), 0.0);
    std::fill(std::begin(errHistR_), std::end(errHistR_), 0.0);
    equalizer_.reset();
}

size_t DspUpsampler::process(
    const uint8_t* inPcm,
    size_t inBytes,
    const char* inBitMode,
    const char* outBitMode,
    std::vector<uint8_t>& outBuffer
) {
    if (!inPcm || inBytes == 0) return 0;

    size_t numInFrames = 0;

    if (strcmp(inBitMode, "float32") == 0) {
        numInFrames = inBytes / (sizeof(float) * 2);
        if (numInFrames == 0) return 0;
        tempInL_.resize(numInFrames);
        tempInR_.resize(numInFrames);
        const auto* srcFloat = reinterpret_cast<const float*>(inPcm);
        for (size_t i = 0; i < numInFrames; ++i) {
            tempInL_[i] = srcFloat[i * 2];
            tempInR_[i] = srcFloat[i * 2 + 1];
        }
    } else if (strcmp(inBitMode, "32bit") == 0) {
        numInFrames = inBytes / 8;
        if (numInFrames == 0) return 0;
        tempInL_.resize(numInFrames);
        tempInR_.resize(numInFrames);
        const auto* src32 = reinterpret_cast<const int32_t*>(inPcm);
        for (size_t i = 0; i < numInFrames; ++i) {
            tempInL_[i] = static_cast<float>(src32[i * 2]) / 2147483648.0f;
            tempInR_[i] = static_cast<float>(src32[i * 2 + 1]) / 2147483648.0f;
        }
    } else if (strcmp(inBitMode, "24bit") == 0) {
        numInFrames = inBytes / 6;
        if (numInFrames == 0) return 0;
        tempInL_.resize(numInFrames);
        tempInR_.resize(numInFrames);
        for (size_t i = 0; i < numInFrames; ++i) {
            size_t base = i * 6;
            int32_t valL = static_cast<int32_t>((inPcm[base]) | (inPcm[base + 1] << 8) | (inPcm[base + 2] << 16));
            if (valL & 0x800000) valL |= 0xFF000000;
            int32_t valR = static_cast<int32_t>((inPcm[base + 3]) | (inPcm[base + 4] << 8) | (inPcm[base + 5] << 16));
            if (valR & 0x800000) valR |= 0xFF000000;

            tempInL_[i] = static_cast<float>(valL) / 8388608.0f;
            tempInR_[i] = static_cast<float>(valR) / 8388608.0f;
        }
    } else {
        numInFrames = inBytes / 4;
        if (numInFrames == 0) return 0;
        tempInL_.resize(numInFrames);
        tempInR_.resize(numInFrames);
        const auto* src16 = reinterpret_cast<const int16_t*>(inPcm);
        for (size_t i = 0; i < numInFrames; ++i) {
            tempInL_[i] = static_cast<float>(src16[i * 2]) / 32768.0f;
            tempInR_[i] = static_cast<float>(src16[i * 2 + 1]) / 32768.0f;
        }
    }

    size_t numOutFrames = numInFrames * factor_;
    tempOutL_.resize(numOutFrames);
    tempOutR_.resize(numOutFrames);

    if (factor_ <= 1) {
        std::memcpy(tempOutL_.data(), tempInL_.data(), numInFrames * sizeof(float));
        std::memcpy(tempOutR_.data(), tempInR_.data(), numInFrames * sizeof(float));
    } else {
        const int subTaps = tapsPerPhase_;

        for (size_t i = 0; i < numInFrames; ++i) {
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
            if (historyWritePos_ >= historyLen_ - 1) {
                int overlap = subTaps - 1;
                std::memmove(&historyL_[0], &historyL_[historyWritePos_ - overlap], overlap * sizeof(float));
                std::memmove(&historyR_[0], &historyR_[historyWritePos_ - overlap], overlap * sizeof(float));
                historyWritePos_ = overlap;
            }
        }
    }

    equalizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);

    int outBytesPerSample = 2;
    if (strcmp(outBitMode, "32bit") == 0) outBytesPerSample = 4;
    else if (strcmp(outBitMode, "24bit") == 0) outBytesPerSample = 3;

    size_t outTotalBytes = numOutFrames * outBytesPerSample * 2;
    outBuffer.resize(outTotalBytes);
    uint8_t* dst = outBuffer.data();

    if (outBytesPerSample == 4) {
        auto* dst32 = reinterpret_cast<int32_t*>(dst);
        for (size_t i = 0; i < numOutFrames; ++i) {
            float l = std::clamp(tempOutL_[i], -1.0f, 1.0f);
            float r = std::clamp(tempOutR_[i], -1.0f, 1.0f);
            dst32[i * 2]     = static_cast<int32_t>(l >= 0.0f ? (l * 2147483647.0f) : (l * 2147483648.0f));
            dst32[i * 2 + 1] = static_cast<int32_t>(r >= 0.0f ? (r * 2147483647.0f) : (r * 2147483648.0f));
        }
    } else if (outBytesPerSample == 3) {
        const double scale = 8388607.0;

        for (size_t i = 0; i < numOutFrames; ++i) {
            double rawL = static_cast<double>(tempOutL_[i]) * scale;
            double rawR = static_cast<double>(tempOutR_[i]) * scale;

            double shapedL = rawL;
            double shapedR = rawR;

            if (ditherMode_ == DitherMode::TPDF) {
                shapedL += getTpdfDither();
                shapedR += getTpdfDither();
            } else if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED) {
                shapedL += (1.5 * errHistL_[0] - 0.6 * errHistL_[1]) + getTpdfDither();
                shapedR += (1.5 * errHistR_[0] - 0.6 * errHistR_[1]) + getTpdfDither();
            } else if (ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                shapedL += (2.033 * errHistL_[0] - 2.165 * errHistL_[1] + 1.959 * errHistL_[2] - 0.827 * errHistL_[3]) + getTpdfDither();
                shapedR += (2.033 * errHistR_[0] - 2.165 * errHistR_[1] + 1.959 * errHistR_[2] - 0.827 * errHistR_[3]) + getTpdfDither();
            }

            int32_t intL = static_cast<int32_t>(std::clamp(std::round(shapedL), -8388608.0, 8388607.0));
            int32_t intR = static_cast<int32_t>(std::clamp(std::round(shapedR), -8388608.0, 8388607.0));

            if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED || ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                double eL = std::clamp(shapedL - intL, -2.0, 2.0);
                double eR = std::clamp(shapedR - intR, -2.0, 2.0);
                errHistL_[3] = errHistL_[2]; errHistL_[2] = errHistL_[1]; errHistL_[1] = errHistL_[0]; errHistL_[0] = eL;
                errHistR_[3] = errHistR_[2]; errHistR_[2] = errHistR_[1]; errHistR_[1] = errHistR_[0]; errHistR_[0] = eR;
            }

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
    } else {
        const double scale = 32767.0;

        for (size_t i = 0; i < numOutFrames; ++i) {
            double rawL = static_cast<double>(tempOutL_[i]) * scale;
            double rawR = static_cast<double>(tempOutR_[i]) * scale;

            double shapedL = rawL;
            double shapedR = rawR;

            if (ditherMode_ == DitherMode::TPDF) {
                shapedL += getTpdfDither();
                shapedR += getTpdfDither();
            } else if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED) {
                shapedL += (1.5 * errHistL_[0] - 0.6 * errHistL_[1]) + getTpdfDither();
                shapedR += (1.5 * errHistR_[0] - 0.6 * errHistR_[1]) + getTpdfDither();
            } else if (ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                shapedL += (2.033 * errHistL_[0] - 2.165 * errHistL_[1] + 1.959 * errHistL_[2] - 0.827 * errHistL_[3]) + getTpdfDither();
                shapedR += (2.033 * errHistR_[0] - 2.165 * errHistR_[1] + 1.959 * errHistR_[2] - 0.827 * errHistR_[3]) + getTpdfDither();
            }

            int32_t intL = static_cast<int32_t>(std::clamp(std::round(shapedL), -32768.0, 32767.0));
            int32_t intR = static_cast<int32_t>(std::clamp(std::round(shapedR), -32768.0, 32767.0));

            if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED || ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                double eL = std::clamp(shapedL - intL, -2.0, 2.0);
                double eR = std::clamp(shapedR - intR, -2.0, 2.0);
                errHistL_[3] = errHistL_[2]; errHistL_[2] = errHistL_[1]; errHistL_[1] = errHistL_[0]; errHistL_[0] = eL;
                errHistR_[3] = errHistR_[2]; errHistR_[2] = errHistR_[1]; errHistR_[1] = errHistR_[0]; errHistR_[0] = eR;
            }

            dst[i * 4]     = intL & 0xFF;
            dst[i * 4 + 1] = (intL >> 8) & 0xFF;
            dst[i * 4 + 2] = intR & 0xFF;
            dst[i * 4 + 3] = (intR >> 8) & 0xFF;
        }
    }

    return outTotalBytes;
}