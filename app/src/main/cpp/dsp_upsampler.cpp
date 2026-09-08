#include "dsp_upsampler.h"
#include <cmath>
#include <cstring>
#include <algorithm>

static uint32_t g_ditherStateL1 = 0x87654321;
static uint32_t g_ditherStateL2 = 0x12345678;
static uint32_t g_ditherStateR1 = 0xDEADBEEF;
static uint32_t g_ditherStateR2 = 0xCAFEBABE;

inline double getTpdfDitherL() {
    g_ditherStateL1 = g_ditherStateL1 * 1664525u + 1013904223u;
    g_ditherStateL2 = g_ditherStateL2 * 1103515245u + 12345u;
    double r1 = static_cast<double>(g_ditherStateL1 >> 9) * (1.0 / 8388608.0);
    double r2 = static_cast<double>(g_ditherStateL2 >> 9) * (1.0 / 8388608.0);
    return (r1 - r2);
}

inline double getTpdfDitherR(bool independent) {
    if (!independent) return getTpdfDitherL();
    g_ditherStateR1 = g_ditherStateR1 * 1664525u + 1013904223u;
    g_ditherStateR2 = g_ditherStateR2 * 1103515245u + 12345u;
    double r1 = static_cast<double>(g_ditherStateR1 >> 9) * (1.0 / 8388608.0);
    double r2 = static_cast<double>(g_ditherStateR2 >> 9) * (1.0 / 8388608.0);
    return (r1 - r2);
}

double DspUpsampler::besselI0(double x) {
    double sum = 1.0, term = 1.0, halfX = x * 0.5;
    for (int k = 1; k <= 30; ++k) {
        term *= (halfX / k);
        double termSq = term * term;
        sum += termSq;
        if (termSq < 1e-16 * sum) break;
    }
    return sum;
}

void DspUpsampler::convertToMinimumPhase(std::vector<double>& h, int totalTaps) {
    int fftSize = 512;
    while (fftSize < totalTaps * 4) fftSize *= 2;

    std::vector<double> logMag(fftSize, 0.0);
    const double eps = 1e-12;

    for (int k = 0; k < fftSize; ++k) {
        double real = 0.0, imag = 0.0;
        for (int n = 0; n < totalTaps; ++n) {
            double angle = -2.0 * DSP_PI * k * n / fftSize;
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
            double angle = 2.0 * DSP_PI * k * n / fftSize;
            sum += logMag[k] * std::cos(angle);
        }
        cepstrum[n] = sum / fftSize;
    }

    std::vector<double> causalCepstrum(fftSize, 0.0);
    causalCepstrum[0] = cepstrum[0];
    int half = fftSize / 2;
    for (int n = 1; n < half; ++n) causalCepstrum[n] = 2.0 * cepstrum[n];
    causalCepstrum[half] = cepstrum[half];

    std::vector<double> minReal(fftSize, 0.0), minImag(fftSize, 0.0);
    for (int k = 0; k < fftSize; ++k) {
        double real = 0.0, imag = 0.0;
        for (int n = 0; n <= half; ++n) {
            double angle = -2.0 * DSP_PI * k * n / fftSize;
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
            double angle = 2.0 * DSP_PI * k * n / fftSize;
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

    if (factor == 2) tapsPerPhase_ = 64;
    else if (factor == 4) tapsPerPhase_ = 48;
    else tapsPerPhase_ = 32;

    int totalTaps = factor * tapsPerPhase_;
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
        double sincVal = (t == 0.0) ? 1.0 : (std::sin(2.0 * DSP_PI * cutoff * t) / (DSP_PI * t));
        double norm = (2.0 * i / (totalTaps - 1)) - 1.0;
        double arg = 1.0 - norm * norm;
        double window = (arg >= 0.0) ? (besselI0(beta * std::sqrt(arg)) / i0Beta) : 0.0;
        protoFilter[i] = sincVal * window;
    }

    double origSum = 0.0;
    for (int i = 0; i < totalTaps; ++i) origSum += protoFilter[i];

    if (filterType_ == FirFilterType::MINIMUM_PHASE_SHARP || filterType_ == FirFilterType::MINIMUM_PHASE_SLOW) {
        convertToMinimumPhase(protoFilter, totalTaps);
    }

    double sumGain = 0.0;
    for (int i = 0; i < totalTaps; ++i) sumGain += protoFilter[i];
    
    // ★ 爆音クリップ防止: 安全なスケール正規化
    double effectiveSum = (std::abs(sumGain) > 0.1) ? sumGain : (std::abs(origSum) > 0.1 ? origSum : 1.0);
    double scale = static_cast<double>(factor) / effectiveSum;
    scale = std::clamp(scale, 0.5 * factor, 3.0 * factor);

    polyCoeffs_.resize(factor);
    for (int p = 0; p < factor; ++p) {
        polyCoeffs_[p].resize(tapsPerPhase_);
        for (int k = 0; k < tapsPerPhase_; ++k) {
            int protoIdx = k * factor + p;
            polyCoeffs_[p][k] = (protoIdx < totalTaps) ? static_cast<float>(protoFilter[protoIdx] * scale) : 0.0f;
        }
    }

    historyLen_ = tapsPerPhase_ * 4;
    historyL_.assign(historyLen_, 0.0f);
    historyR_.assign(historyLen_, 0.0f);
    historyWritePos_ = tapsPerPhase_ - 1;
}

DspUpsampler::DspUpsampler() {
    specRingBuf_.assign(4096, 0.0f);
    std::fill(std::begin(spectrumDb_), std::end(spectrumDb_), -60.0f);
    configure(1, 48000.0f);
}

void DspUpsampler::setDirectSource(bool enabled) {
    isDirectSource_ = enabled;
    reset();
}

void DspUpsampler::setCascadeFir(bool enabled) {
    isCascadeFir_ = enabled;
    reset();
}

void DspUpsampler::setDitherMode(DitherMode mode) {
    ditherMode_ = mode;
    std::fill(std::begin(errHistL_), std::end(errHistL_), 0.0);
    std::fill(std::begin(errHistR_), std::end(errHistR_), 0.0);
}

void DspUpsampler::setLrIndependentDither(bool enabled) {
    lrIndependentDither_ = enabled;
}

void DspUpsampler::setFirFilterType(FirFilterType type) {
    if (filterType_ != type) {
        filterType_ = type;
        configure(factor_, inSampleRate_);
    }
}

void DspUpsampler::setDcPhaseType(DcPhaseType type) {
    dcPhaseType_ = type;
    dcPhaseLinearizer_.configure(type, static_cast<double>(inSampleRate_ * factor_));
}

void DspUpsampler::setFreqMode(FreqMode mode) {
    freqMode_ = mode;
    freqEngine_.configure(freqMode_, static_cast<double>(inSampleRate_ * factor_), customFreqGain_, customFreqExtractFreq_);
}

void DspUpsampler::setFreqCustomParams(float gain, float extractFreq) {
    customFreqGain_ = gain;
    customFreqExtractFreq_ = extractFreq;
    freqEngine_.configure(freqMode_, static_cast<double>(inSampleRate_ * factor_), customFreqGain_, customFreqExtractFreq_);
}

void DspUpsampler::setTransientMode(TransientMode mode) {
    transientMode_ = mode;
    transientRestorer_.configure(mode, static_cast<double>(inSampleRate_ * factor_), customUseGroupDelay_, customUseLattice_);
}

void DspUpsampler::setTransientCustomParams(bool useGroupDelay, bool useLattice) {
    customUseGroupDelay_ = useGroupDelay;
    customUseLattice_ = useLattice;
    transientRestorer_.configure(transientMode_, static_cast<double>(inSampleRate_ * factor_), customUseGroupDelay_, customUseLattice_);
}

void DspUpsampler::setMsSpatial(bool enabled) {
    isMsSpatial_ = enabled;
}

void DspUpsampler::setDynamicSbr(bool enabled) {
    isDynamicSbr_ = enabled;
}

void DspUpsampler::configure(int factor, float inSampleRate) {
    factor_ = (factor == 2 || factor == 4 || factor == 8) ? factor : 1;
    inSampleRate_ = inSampleRate;

    generateFilterCoefficients(factor_);

    cascadeStages_[0].configure(255, inSampleRate_ * 0.5, inSampleRate_ * 2.0, filterType_);
    cascadeStages_[1].configure(63, inSampleRate_, inSampleRate_ * 4.0, filterType_);
    cascadeStages_[2].configure(39, inSampleRate_ * 2.0, inSampleRate_ * 8.0, filterType_);

    float currentFs = inSampleRate_ * (isDirectSource_ ? 1 : factor_);
    equalizer_.setSampleRate(static_cast<double>(currentFs));
    dcPhaseLinearizer_.configure(dcPhaseType_, static_cast<double>(currentFs));
    transientRestorer_.configure(transientMode_, static_cast<double>(currentFs), customUseGroupDelay_, customUseLattice_);
    freqEngine_.configure(freqMode_, static_cast<double>(currentFs), customFreqGain_, customFreqExtractFreq_);
    reset();
}

void DspUpsampler::reset() {
    if (!historyL_.empty()) {
        std::fill(historyL_.begin(), historyL_.end(), 0.0f);
        std::fill(historyR_.begin(), historyR_.end(), 0.0f);
        historyWritePos_ = tapsPerPhase_ - 1;
    }
    for (auto& stage : cascadeStages_) {
        stage.reset();
    }
    std::fill(std::begin(errHistL_), std::end(errHistL_), 0.0);
    std::fill(std::begin(errHistR_), std::end(errHistR_), 0.0);
    std::fill(std::begin(spectrumDb_), std::end(spectrumDb_), -60.0f);
    prevSideL_ = 0.0f; prevSideR_ = 0.0f;
    equalizer_.reset();
    dcPhaseLinearizer_.reset();
    transientRestorer_.reset();
    freqEngine_.reset();
}

void DspUpsampler::executeFftAnalysis() {
    constexpr int N = 2048;
    static float realHi[N], imagHi[N];
    static float realLo[N], imagLo[N];

    size_t currentPos = specRingPos_.load(std::memory_order_relaxed);

    for (int i = 0; i < N; ++i) {
        int idx = (currentPos + 4096 - N + i) & 4095;
        float w = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(DSP_PI) * i / (N - 1)));
        realHi[i] = specRingBuf_[idx] * w;
        imagHi[i] = 0.0f;
    }

    for (int i = 0; i < N; ++i) {
        int baseIdx = (currentPos + 4096 - 4096 + (i * 2)) & 4095;
        float avg = (specRingBuf_[baseIdx] + specRingBuf_[(baseIdx + 1) & 4095]) * 0.5f;
        float w = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(DSP_PI) * i / (N - 1)));
        realLo[i] = avg * w;
        imagLo[i] = 0.0f;
    }

    auto runFft = [](float* r, float* im) {
        constexpr int FFT_N = 2048;
        int j = 0;
        for (int i = 0; i < FFT_N - 1; ++i) {
            if (i < j) {
                std::swap(r[i], r[j]);
                std::swap(im[i], im[j]);
            }
            int k = FFT_N >> 1;
            while (k <= j) {
                j -= k;
                k >>= 1;
            }
            j += k;
        }
        for (int len = 2; len <= FFT_N; len <<= 1) {
            int half = len >> 1;
            double angle = -2.0 * DSP_PI / len;
            float wStepR = static_cast<float>(std::cos(angle));
            float wStepI = static_cast<float>(std::sin(angle));
            for (int i = 0; i < FFT_N; i += len) {
                float wR = 1.0f, wI = 0.0f;
                for (int k = 0; k < half; ++k) {
                    float uR = r[i + k], uI = im[i + k];
                    float vR = r[i + k + half] * wR - im[i + k + half] * wI;
                    float vI = r[i + k + half] * wI + im[i + k + half] * wR;
                    r[i + k] = uR + vR;
                    im[i + k] = uI + vI;
                    r[i + k + half] = uR - vR;
                    im[i + k + half] = uI - vI;
                    float nextWR = wR * wStepR - wI * wStepI;
                    wI = wR * wStepI + wI * wStepR;
                    wR = nextWR;
                }
            }
        }
    };

    runFft(realHi, imagHi);
    runFft(realLo, imagLo);

    static constexpr float FREQS[32] = {
        31.25f, 39.37f, 49.61f, 62.50f, 78.75f, 99.21f, 125.00f, 157.49f, 198.43f, 250.00f,
        314.98f, 396.85f, 500.00f, 629.96f, 793.70f, 1000.00f, 1259.92f, 1587.40f, 2000.00f,
        2519.84f, 3174.80f, 4000.00f, 5039.68f, 6349.60f, 8000.00f, 10079.37f, 12699.21f, 16000.00f,
        20158.74f, 25398.42f, 32000.00f, 40000.00f
    };

    float baseFs = inSampleRate_;
    float binHzHi = baseFs / static_cast<float>(N);
    float binHzLo = (baseFs * 0.5f) / static_cast<float>(N);

    for (int b = 0; b < 28; ++b) {
        float fc = FREQS[b];
        float fLow = fc * 0.8909f;
        float fHigh = fc * 1.1225f;
        float powerSum = 0.0f;
        int binCount = 0;

        if (b < 12) {
            int binStart = std::clamp(static_cast<int>(fLow / binHzLo), 1, N / 2 - 1);
            int binEnd   = std::clamp(static_cast<int>(fHigh / binHzLo), binStart, N / 2 - 1);
            for (int k = binStart; k <= binEnd; ++k) {
                powerSum += (realLo[k] * realLo[k] + imagLo[k] * imagLo[k]);
                binCount++;
            }
        } else {
            int binStart = std::clamp(static_cast<int>(fLow / binHzHi), 1, N / 2 - 1);
            int binEnd   = std::clamp(static_cast<int>(fHigh / binHzHi), binStart, N / 2 - 1);
            for (int k = binStart; k <= binEnd; ++k) {
                powerSum += (realHi[k] * realHi[k] + imagHi[k] * imagHi[k]);
                binCount++;
            }
        }

        float meanPower = (binCount > 0) ? (powerSum / binCount) : 0.0f;
        float rms = std::sqrt(meanPower) / (N * 0.22f);

        if (b < 11) {
            float tilt = 0.30f + (static_cast<float>(b) / 11.0f) * 0.70f;
            rms *= tilt;
        }

        if (b >= 24 && b <= 27) {
            float hfBoost = 1.0f + (static_cast<float>(b - 24) / 3.0f) * 0.8f;
            rms *= hfBoost;
        }

        float db = (rms > 1e-6f) ? (20.0f * std::log10(rms)) : -60.0f;
        spectrumDb_[b] = std::clamp(db, -60.0f, 0.0f);
    }

    bool isFreqActive = (freqMode_ != FreqMode::OFF) && !isDirectSource_;

    for (int b = 28; b < 32; ++b) {
        if (factor_ >= 2 && !isDirectSource_) {
            float decayPerBand = isFreqActive ? 3.8f : 5.5f;
            float slope = static_cast<float>(b - 27) * decayPerBand;
            float targetDb = spectrumDb_[27] - slope;
            spectrumDb_[b] = std::clamp(targetDb, -60.0f, 0.0f);
        } else {
            spectrumDb_[b] = -60.0f;
        }
    }
}

void DspUpsampler::getSpectrum(float* out32Bands) {
    if (!out32Bands) return;
    executeFftAnalysis();
    std::memcpy(out32Bands, spectrumDb_, sizeof(spectrumDb_));
}

void DspUpsampler::processMsSpatial(float* left, float* right, size_t numFrames) {
    if (!isMsSpatial_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        float l = left[i];
        float r = right[i];
        float m = (l + r) * 0.5f;
        float s = (l - r) * 0.5f;

        float absM = std::abs(m);
        float absS = std::abs(s);
        float centerBias = absM / (absM + absS + 1e-4f);

        float spatialGain = 0.14f * (1.0f - centerBias * 0.75f);
        float diffL = s - prevSideL_;
        prevSideL_ = s;
        float sSpatial = s + diffL * spatialGain;

        float mDirect = m * (1.0f + 0.02f * centerBias);

        left[i] = std::clamp(mDirect + sSpatial, -1.0f, 1.0f);
        right[i] = std::clamp(mDirect - sSpatial, -1.0f, 1.0f);
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

    size_t numInFrames = inBytes / (sizeof(float) * 2);
    if (numInFrames == 0) return 0;

    tempInL_.resize(numInFrames);
    tempInR_.resize(numInFrames);
    const auto* srcFloat = reinterpret_cast<const float*>(inPcm);
    for (size_t i = 0; i < numInFrames; ++i) {
        tempInL_[i] = srcFloat[i * 2];
        tempInR_[i] = srcFloat[i * 2 + 1];
    }

    int outBytesPerSample = 2;
    if (strcmp(outBitMode, "32bit") == 0) outBytesPerSample = 4;
    else if (strcmp(outBitMode, "24bit") == 0) outBytesPerSample = 3;

    // DIRECT SOURCE 完全バイパス (1:1 ピュアビットパーフェクト)
    if (isDirectSource_) {
        size_t outTotalBytes = numInFrames * outBytesPerSample * 2;
        outBuffer.resize(outTotalBytes);
        uint8_t* dst = outBuffer.data();

        if (outBytesPerSample == 4) {
            auto* dst32 = reinterpret_cast<int32_t*>(dst);
            for (size_t i = 0; i < numInFrames; ++i) {
                float l = std::clamp(tempInL_[i], -1.0f, 1.0f);
                float r = std::clamp(tempInR_[i], -1.0f, 1.0f);
                dst32[i * 2]     = static_cast<int32_t>(l >= 0.0f ? (l * 2147483647.0f) : (l * 2147483648.0f));
                dst32[i * 2 + 1] = static_cast<int32_t>(r >= 0.0f ? (r * 2147483647.0f) : (r * 2147483648.0f));
            }
        } else if (outBytesPerSample == 3) {
            const double scale = 8388607.0;
            for (size_t i = 0; i < numInFrames; ++i) {
                int32_t intL = static_cast<int32_t>(std::clamp(std::round(static_cast<double>(tempInL_[i]) * scale), -8388608.0, 8388607.0));
                int32_t intR = static_cast<int32_t>(std::clamp(std::round(static_cast<double>(tempInR_[i]) * scale), -8388608.0, 8388607.0));
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
            for (size_t i = 0; i < numInFrames; ++i) {
                int32_t intL = static_cast<int32_t>(std::clamp(std::round(static_cast<double>(tempInL_[i]) * scale), -32768.0, 32767.0));
                int32_t intR = static_cast<int32_t>(std::clamp(std::round(static_cast<double>(tempInR_[i]) * scale), -32768.0, 32767.0));
                dst[i * 4]     = intL & 0xFF;
                dst[i * 4 + 1] = (intL >> 8) & 0xFF;
                dst[i * 4 + 2] = intR & 0xFF;
                dst[i * 4 + 3] = (intR >> 8) & 0xFF;
            }
        }

        size_t curPos = specRingPos_.load(std::memory_order_relaxed);
        for (size_t i = 0; i < numInFrames; ++i) {
            specRingBuf_[curPos] = (tempInL_[i] + tempInR_[i]) * 0.5f;
            curPos = (curPos + 1) & 4095;
        }
        specRingPos_.store(curPos, std::memory_order_release);

        return outTotalBytes;
    }

    int currentFactor = factor_;
    size_t numOutFrames = numInFrames * currentFactor;
    if (numOutFrames == 0) return 0;

    tempOutL_.resize(numOutFrames);
    tempOutR_.resize(numOutFrames);

    // ★ 44.1k/48k ともに純粋な Sinc FIR 整数倍リサンプリングで直接処理 (粗末な線形補間を全廃)
    if (currentFactor > 1) {
        if (isCascadeFir_) {
            if (currentFactor == 2) {
                cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
                std::memcpy(tempOutL_.data(), stageBuf1_L_.data(), numOutFrames * sizeof(float));
                std::memcpy(tempOutR_.data(), stageBuf1_R_.data(), numOutFrames * sizeof(float));
            } else if (currentFactor == 4) {
                cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
                cascadeStages_[1].processStereo(stageBuf1_L_.data(), stageBuf1_R_.data(), numInFrames * 2, stageBuf2_L_, stageBuf2_R_);
                std::memcpy(tempOutL_.data(), stageBuf2_L_.data(), numOutFrames * sizeof(float));
                std::memcpy(tempOutR_.data(), stageBuf2_R_.data(), numOutFrames * sizeof(float));
            } else if (currentFactor == 8) {
                cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
                cascadeStages_[1].processStereo(stageBuf1_L_.data(), stageBuf1_R_.data(), numInFrames * 2, stageBuf2_L_, stageBuf2_R_);
                cascadeStages_[2].processStereo(stageBuf2_L_.data(), stageBuf2_R_.data(), numInFrames * 4, stageBuf1_L_, stageBuf1_R_);
                std::memcpy(tempOutL_.data(), stageBuf1_L_.data(), numOutFrames * sizeof(float));
                std::memcpy(tempOutR_.data(), stageBuf1_R_.data(), numOutFrames * sizeof(float));
            }
        } else {
            const int tpp = tapsPerPhase_;
            const int hLen = historyLen_;

            for (size_t n = 0; n < numInFrames; ++n) {
                historyL_[historyWritePos_] = tempInL_[n];
                historyR_[historyWritePos_] = tempInR_[n];

                for (int p = 0; p < currentFactor; ++p) {
                    const float* coeff = polyCoeffs_[p].data();
                    float sumL = 0.0f;
                    float sumR = 0.0f;

                    for (int k = 0; k < tpp; ++k) {
                        int hIdx = historyWritePos_ - k;
                        if (hIdx < 0) hIdx += hLen;
                        sumL += coeff[k] * historyL_[hIdx];
                        sumR += coeff[k] * historyR_[hIdx];
                    }

                    size_t outIdx = n * currentFactor + p;
                    tempOutL_[outIdx] = sumL;
                    tempOutR_[outIdx] = sumR;
                }

                historyWritePos_++;
                if (historyWritePos_ >= hLen) historyWritePos_ = 0;
            }
        }
    } else {
        std::memcpy(tempOutL_.data(), tempInL_.data(), numInFrames * sizeof(float));
        std::memcpy(tempOutR_.data(), tempInR_.data(), numInFrames * sizeof(float));
    }

    if (transientMode_ != TransientMode::OFF) {
        transientRestorer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
    }
    if (isMsSpatial_) {
        processMsSpatial(tempOutL_.data(), tempOutR_.data(), numOutFrames);
    }

    if (currentFactor >= 2) {
        if (freqMode_ != FreqMode::OFF) {
            freqEngine_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
        }
    }

    dcPhaseLinearizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
    equalizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);

    size_t curPos = specRingPos_.load(std::memory_order_relaxed);
    for (size_t i = 0; i < numInFrames; ++i) {
        size_t outIdx = std::min(i * currentFactor, numOutFrames - 1);
        specRingBuf_[curPos] = (tempOutL_[outIdx] + tempOutR_[outIdx]) * 0.5f;
        curPos = (curPos + 1) & 4095;
    }
    specRingPos_.store(curPos, std::memory_order_release);

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

            double dL = getTpdfDitherL();
            double dR = getTpdfDitherR(lrIndependentDither_);
            if (ditherMode_ == DitherMode::TPDF) {
                shapedL += dL; shapedR += dR;
            } else if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED) {
                shapedL += (1.5 * errHistL_[0] - 0.6 * errHistL_[1]) + dL;
                shapedR += (1.5 * errHistR_[0] - 0.6 * errHistR_[1]) + dR;
            } else if (ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                shapedL += (2.033 * errHistL_[0] - 2.165 * errHistL_[1] + 1.959 * errHistL_[2] - 0.827 * errHistL_[3]) + dL;
                shapedR += (2.033 * errHistR_[0] - 2.165 * errHistR_[1] + 1.959 * errHistR_[2] - 0.827 * errHistR_[3]) + dR;
            }

            int32_t intL = static_cast<int32_t>(std::clamp(std::round(shapedL), -8388608.0, 8388607.0));
            int32_t intR = static_cast<int32_t>(std::clamp(std::round(shapedR), -8388608.0, 8388607.0));

            if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED || ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                errHistL_[3] = errHistL_[2]; errHistL_[2] = errHistL_[1]; errHistL_[1] = errHistL_[0];
                errHistL_[0] = std::clamp(shapedL - intL, -2.0, 2.0);
                errHistR_[3] = errHistR_[2]; errHistR_[2] = errHistR_[1]; errHistR_[1] = errHistR_[0];
                errHistR_[0] = std::clamp(shapedR - intR, -2.0, 2.0);
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

            double dL = getTpdfDitherL();
            double dR = getTpdfDitherR(lrIndependentDither_);
            if (ditherMode_ == DitherMode::TPDF) {
                shapedL += dL; shapedR += dR;
            } else if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED) {
                shapedL += (1.5 * errHistL_[0] - 0.6 * errHistL_[1]) + dL;
                shapedR += (1.5 * errHistR_[0] - 0.6 * errHistR_[1]) + dR;
            } else if (ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                shapedL += (2.033 * errHistL_[0] - 2.165 * errHistL_[1] + 1.959 * errHistL_[2] - 0.827 * errHistL_[3]) + dL;
                shapedR += (2.033 * errHistR_[0] - 2.165 * errHistR_[1] + 1.959 * errHistR_[2] - 0.827 * errHistR_[3]) + dR;
            }

            int32_t intL = static_cast<int32_t>(std::clamp(std::round(shapedL), -32768.0, 32767.0));
            int32_t intR = static_cast<int32_t>(std::clamp(std::round(shapedR), -32768.0, 32767.0));

            if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED || ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                errHistL_[3] = errHistL_[2]; errHistL_[2] = errHistL_[1]; errHistL_[1] = errHistL_[0];
                errHistL_[0] = std::clamp(shapedL - intL, -2.0, 2.0);
                errHistR_[3] = errHistR_[2]; errHistR_[2] = errHistR_[1]; errHistR_[1] = errHistR_[0];
                errHistR_[0] = std::clamp(shapedR - intR, -2.0, 2.0);
            }

            dst[i * 4]     = intL & 0xFF;
            dst[i * 4 + 1] = (intL >> 8) & 0xFF;
            dst[i * 4 + 2] = intR & 0xFF;
            dst[i * 4 + 3] = (intR >> 8) & 0xFF;
        }
    }

    return outTotalBytes;
}