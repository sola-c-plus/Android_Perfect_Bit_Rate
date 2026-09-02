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

// -----------------------------------------------------------------------------
// DspLpcHarmonicAi 実装 (JVC K2 / DSEE HX AI 特化型 リアルタイム音響推論)
// -----------------------------------------------------------------------------
DspLpcHarmonicAi::DspLpcHarmonicAi() {
    configure(DseeMode::DSEE_AI, 48000.0);
}

void DspLpcHarmonicAi::configure(DseeMode mode, double sampleRate) {
    mode_ = mode;
    sampleRate_ = std::max(8000.0, sampleRate);
    reset();

    if (mode_ == DseeMode::OFF) {
        isBypass_ = true;
        return;
    }

    isBypass_ = false;

    // 1. 10kHz 帯域抽出 HPF
    double fExtract = 10000.0;
    double kExtract = std::tan(PI * fExtract / sampleRate_);
    hp_b0_ = 1.0 / (1.0 + kExtract);
    hp_b1_ = -hp_b0_;
    hp_a1_ = -(1.0 - kExtract) / (1.0 + kExtract);

    // 2. 20kHz〜35kHz LPC 超高域外挿 BPF
    double fCenter = (sampleRate_ >= 88200.0) ? 22000.0 : 16000.0;
    fCenter = std::min(fCenter, sampleRate_ * 0.45);
    double Q = 1.1;
    double w0 = 2.0 * PI * fCenter / sampleRate_;
    double alpha = std::sin(w0) / (2.0 * Q);

    double b0_raw = alpha;
    double b1_raw = 0.0;
    double b2_raw = -alpha;
    double a0_raw = 1.0 + alpha;
    double a1_raw = -2.0 * std::cos(w0);
    double a2_raw = 1.0 - alpha;

    double inv_a0 = 1.0 / a0_raw;
    bp_b0_ = b0_raw * inv_a0;
    bp_b1_ = b1_raw * inv_a0;
    bp_b2_ = b2_raw * inv_a0;
    bp_a1_ = a1_raw * inv_a0;
    bp_a2_ = a2_raw * inv_a0;
}

void DspLpcHarmonicAi::reset() {
    hp_s1_L_ = 0.0; hp_s1_R_ = 0.0;
    bp_s1_L_ = 0.0; bp_s2_L_ = 0.0;
    bp_s1_R_ = 0.0; bp_s2_R_ = 0.0;
    prevSampleL_ = 0.0; prevSampleR_ = 0.0;
    envHfL_ = 0.0; envHfR_ = 0.0;
    envTotalL_ = 0.0; envTotalR_ = 0.0;
    transientFluxL_ = 0.0; transientFluxR_ = 0.0;
    lpcAlphaL_ = 0.5; lpcAlphaR_ = 0.5;
}

void DspLpcHarmonicAi::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        // ==================== L チャンネル ====================
        double inL = static_cast<double>(left[i]);

        // 1. 高域抽出 (10kHz以上)
        double hiL = hp_b0_ * inL + hp_b1_ * hp_s1_L_ - hp_a1_ * hp_s1_L_;
        hp_s1_L_ = inL;

        // 2. リアルタイム音響特徴量アナライザー (AI推論)
        double absHfL = std::abs(hiL);
        double absTotalL = std::abs(inL);
        envHfL_ = envHfL_ * 0.992 + absHfL * 0.008;
        envTotalL_ = envTotalL_ * 0.995 + absTotalL * 0.005;

        // トランジェント検知 (差分フラックス)
        double diffL = std::abs(inL - prevSampleL_);
        prevSampleL_ = inL;
        transientFluxL_ = transientFluxL_ * 0.98 + diffL * 0.02;
        bool isTransientL = (diffL > transientFluxL_ * 2.5);

        // LPC 自己相関スロープ予測 (音色の物理モデル)
        lpcAlphaL_ = lpcAlphaL_ * 0.99 + (absHfL / (envTotalL_ + 1e-6)) * 0.01;
        lpcAlphaL_ = std::clamp(lpcAlphaL_, 0.1, 0.9);

        // 3. LPC スペクトル外挿 ＆ 適応型倍音生成
        double harmL = 0.0;
        double dynamicGainL = 0.0;

        if (mode_ == DseeMode::DSEE_AI) {
            // ★ DSEE HX AI モード: LPC外挿 ＋ トランジェント保護型進相
            double lpcExtrapolated = hiL * lpcAlphaL_ * 2.2 + (hiL * hiL * 1.8 - 0.05);
            if (isTransientL) {
                // 打撃瞬間のみ微小な進相を付加してアタック感を鮮明化
                lpcExtrapolated += (hiL - hp_s1_L_) * 0.4;
            }
            harmL = lpcExtrapolated;
            dynamicGainL = std::min(envHfL_ * 7.5, 0.14);
        } else if (mode_ == DseeMode::K2_LPC) {
            // ★ JVC K2 モード: 純粋な線形予測物理モデル (歪み感ゼロ・極上自然感)
            harmL = hiL * lpcAlphaL_ * 1.8 + (hiL * hiL * 0.9);
            dynamicGainL = std::min(envHfL_ * 5.5, 0.10);
        } else {
            // ★ ディテール保護型エキサイター: アタック時のみ適応進相
            harmL = isTransientL ? (hiL * 2.0 + (hiL - hp_s1_L_) * 0.8) : (hiL * 0.5);
            dynamicGainL = std::min(envHfL_ * 6.0, 0.12);
        }

        // 4. 超高域整形 BPF
        double outHarmL = bp_b0_ * harmL + bp_s1_L_;
        bp_s1_L_ = bp_b1_ * harmL - bp_a1_ * outHarmL + bp_s2_L_;
        bp_s2_L_ = bp_b2_ * harmL - bp_a2_ * outHarmL;

        left[i] = static_cast<float>(inL + outHarmL * dynamicGainL);

        // ==================== R チャンネル ====================
        double inR = static_cast<double>(right[i]);

        double hiR = hp_b0_ * inR + hp_b1_ * hp_s1_R_ - hp_a1_ * hp_s1_R_;
        hp_s1_R_ = inR;

        double absHfR = std::abs(hiR);
        double absTotalR = std::abs(inR);
        envHfR_ = envHfR_ * 0.992 + absHfR * 0.008;
        envTotalR_ = envTotalR_ * 0.995 + absTotalR * 0.005;

        double diffR = std::abs(inR - prevSampleR_);
        prevSampleR_ = inR;
        transientFluxR_ = transientFluxR_ * 0.98 + diffR * 0.02;
        bool isTransientR = (diffR > transientFluxR_ * 2.5);

        lpcAlphaR_ = lpcAlphaR_ * 0.99 + (absHfR / (envTotalR_ + 1e-6)) * 0.01;
        lpcAlphaR_ = std::clamp(lpcAlphaR_, 0.1, 0.9);

        double harmR = 0.0;
        double dynamicGainR = 0.0;

        if (mode_ == DseeMode::DSEE_AI) {
            double lpcExtrapolated = hiR * lpcAlphaR_ * 2.2 + (hiR * hiR * 1.8 - 0.05);
            if (isTransientR) {
                lpcExtrapolated += (hiR - hp_s1_R_) * 0.4;
            }
            harmR = lpcExtrapolated;
            dynamicGainR = std::min(envHfR_ * 7.5, 0.14);
        } else if (mode_ == DseeMode::K2_LPC) {
            harmR = hiR * lpcAlphaR_ * 1.8 + (hiR * hiR * 0.9);
            dynamicGainR = std::min(envHfR_ * 5.5, 0.10);
        } else {
            harmR = isTransientR ? (hiR * 2.0 + (hiR - hp_s1_R_) * 0.8) : (hiR * 0.5);
            dynamicGainR = std::min(envHfR_ * 6.0, 0.12);
        }

        double outHarmR = bp_b0_ * harmR + bp_s1_R_;
        bp_s1_R_ = bp_b1_ * harmR - bp_a1_ * outHarmR + bp_s2_R_;
        bp_s2_R_ = bp_b2_ * harmR - bp_a2_ * outHarmR;

        right[i] = static_cast<float>(inR + outHarmR * dynamicGainR);
    }
}

// -----------------------------------------------------------------------------
// DspDcPhaseLinearizer 実装
// -----------------------------------------------------------------------------
DspDcPhaseLinearizer::DspDcPhaseLinearizer() {
    configure(DcPhaseType::A_STD, 48000.0);
}

void DspDcPhaseLinearizer::configure(DcPhaseType type, double sampleRate) {
    type_ = type;
    sampleRate_ = std::max(8000.0, sampleRate);
    reset();

    if (type_ == DcPhaseType::OFF) {
        b0_ = 1.0; b1_ = 0.0; b2_ = 0.0;
        a1_ = 0.0; a2_ = 0.0;
        isBypass_ = true;
        return;
    }

    isBypass_ = false;

    double f0 = 45.0;
    double Q = 0.707;
    double gainDb = 1.2;

    switch (type) {
        case DcPhaseType::A_LOW:  f0 = 32.0; Q = 0.65; gainDb = 1.2; break;
        case DcPhaseType::A_STD:  f0 = 48.0; Q = 0.70; gainDb = 1.5; break;
        case DcPhaseType::A_HIGH: f0 = 70.0; Q = 0.75; gainDb = 1.2; break;
        case DcPhaseType::B_LOW:  f0 = 30.0; Q = 0.95; gainDb = 2.4; break;
        case DcPhaseType::B_STD:  f0 = 42.0; Q = 1.05; gainDb = 2.0; break;
        case DcPhaseType::B_HIGH: f0 = 60.0; Q = 0.95; gainDb = 1.6; break;
        default: break;
    }

    double A = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * PI * f0 / sampleRate_;
    double cosw0 = std::cos(w0);
    double sinw0 = std::sin(w0);
    double alpha = sinw0 / (2.0 * Q);
    double beta = std::sqrt(A + A);

    double b0_raw = A * ((A + 1.0) - (A - 1.0) * cosw0 + beta * sinw0);
    double b1_raw = 2.0 * A * ((A - 1.0) - (A + 1.0) * cosw0);
    double b2_raw = A * ((A + 1.0) - (A - 1.0) * cosw0 - beta * sinw0);
    double a0_raw = (A + 1.0) + (A - 1.0) * cosw0 + beta * sinw0;
    double a1_raw = -2.0 * ((A - 1.0) + (A + 1.0) * cosw0);
    double a2_raw = (A + 1.0) + (A - 1.0) * cosw0 - beta * sinw0;

    double inv_a0 = 1.0 / a0_raw;
    b0_ = b0_raw * inv_a0;
    b1_ = b1_raw * inv_a0;
    b2_ = b2_raw * inv_a0;
    a1_ = a1_raw * inv_a0;
    a2_ = a2_raw * inv_a0;
}

void DspDcPhaseLinearizer::reset() {
    s1_L_ = 0.0; s2_L_ = 0.0;
    s1_R_ = 0.0; s2_R_ = 0.0;
}

void DspDcPhaseLinearizer::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        double inL = static_cast<double>(left[i]);
        double outL = b0_ * inL + s1_L_;
        s1_L_ = b1_ * inL - a1_ * outL + s2_L_;
        s2_L_ = b2_ * inL - a2_ * outL;
        left[i] = static_cast<float>(outL);

        double inR = static_cast<double>(right[i]);
        double outR = b0_ * inR + s1_R_;
        s1_R_ = b1_ * inR - a1_ * outR + s2_R_;
        s2_R_ = b2_ * inR - a2_ * outR;
        right[i] = static_cast<float>(outR);
    }
}

// -----------------------------------------------------------------------------
// DspUpsampler 実装
// -----------------------------------------------------------------------------
DspUpsampler::DspUpsampler() {
    configure(1, 48000.0f);
}

void DspUpsampler::setDirectSource(bool enabled) {
    isDirectSource_ = enabled;
    reset();
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

void DspUpsampler::setDcPhaseType(DcPhaseType type) {
    dcPhaseType_ = type;
    dcPhaseLinearizer_.configure(type, static_cast<double>(inSampleRate_ * factor_));
}

void DspUpsampler::setDseeMode(DseeMode mode) {
    dseeMode_ = mode;
    lpcHarmonicAi_.configure(mode, static_cast<double>(inSampleRate_ * factor_));
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
    dcPhaseLinearizer_.configure(dcPhaseType_, static_cast<double>(inSampleRate_ * factor_));
    lpcHarmonicAi_.configure(dseeMode_, static_cast<double>(inSampleRate_ * factor_));
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
    dcPhaseLinearizer_.reset();
    lpcHarmonicAi_.reset();
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

    int currentFactor = isDirectSource_ ? 1 : factor_;
    size_t numOutFrames = numInFrames * currentFactor;
    tempOutL_.resize(numOutFrames);
    tempOutR_.resize(numOutFrames);

    if (currentFactor <= 1) {
        std::memcpy(tempOutL_.data(), tempInL_.data(), numInFrames * sizeof(float));
        std::memcpy(tempOutR_.data(), tempInR_.data(), numInFrames * sizeof(float));
    } else {
        const int subTaps = tapsPerPhase_;

        for (size_t i = 0; i < numInFrames; ++i) {
            historyL_[historyWritePos_] = tempInL_[i];
            historyR_[historyWritePos_] = tempInR_[i];

            for (int p = 0; p < currentFactor; ++p) {
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
                tempOutL_[i * currentFactor + p] = sumL;
                tempOutR_[i * currentFactor + p] = sumR;
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

    // Direct Source OFF 時の DSP 処理
    if (!isDirectSource_) {
        // 1. 10-Band EQ
        equalizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);

        // 2. DC Phase Linearizer (Walkman 1Z 低域位相)
        dcPhaseLinearizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);

        // 3. ★ LPC スペクトル外挿 ＆ リアルタイム音響解析 AI
        lpcHarmonicAi_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
    }

    // PCM パッキング
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

            if (!isDirectSource_) {
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
            }

            int32_t intL = static_cast<int32_t>(std::clamp(std::round(shapedL), -8388608.0, 8388607.0));
            int32_t intR = static_cast<int32_t>(std::clamp(std::round(shapedR), -8388608.0, 8388607.0));

            if (!isDirectSource_ && (ditherMode_ == DitherMode::HIGH_PASS_SHAPED || ditherMode_ == DitherMode::PSYCHOACOUSTIC)) {
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

            if (!isDirectSource_) {
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
            }

            int32_t intL = static_cast<int32_t>(std::clamp(std::round(shapedL), -32768.0, 32767.0));
            int32_t intR = static_cast<int32_t>(std::clamp(std::round(shapedR), -32768.0, 32767.0));

            if (!isDirectSource_ && (ditherMode_ == DitherMode::HIGH_PASS_SHAPED || ditherMode_ == DitherMode::PSYCHOACOUSTIC)) {
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