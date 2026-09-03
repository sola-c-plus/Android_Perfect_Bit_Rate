#include "dsp_upsampler.h"
#include <cmath>
#include <cstring>
#include <algorithm>

constexpr double PI = 3.14159265358979323846;

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

// -----------------------------------------------------------------------------
// FirStage2x 実装
// -----------------------------------------------------------------------------
double FirStage2x::besselI0(double x) {
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

void FirStage2x::convertToMinimumPhase(std::vector<double>& h, int totalTaps) {
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
        for (int n = 0; n < totalTaps; ++n) {
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

void FirStage2x::configure(size_t numTaps, double cutoffHz, double outputRateHz, FirFilterType filterType) {
    numTaps_ = (numTaps % 2 == 0) ? numTaps + 1 : numTaps;
    double normalizedCutoff = std::clamp(cutoffHz / outputRateHz, 0.001, 0.249);
    double beta = 16.0;
    if (filterType == FirFilterType::LINEAR_PHASE_SLOW || filterType == FirFilterType::MINIMUM_PHASE_SLOW) {
        normalizedCutoff *= 0.90;
        beta = 10.0;
    }

    double i0Beta = besselI0(beta);
    double center = static_cast<double>(numTaps_ - 1) * 0.5;
    std::vector<double> design(numTaps_, 0.0);
    double sum = 0.0;

    for (size_t i = 0; i < numTaps_; ++i) {
        double offset = static_cast<double>(i) - center;
        double sincVal = (std::abs(offset) < 1e-12) ? 1.0 : (std::sin(PI * 2.0 * normalizedCutoff * offset) / (PI * 2.0 * normalizedCutoff * offset));
        double rel = offset / center;
        double arg = std::max(0.0, 1.0 - rel * rel);
        double window = besselI0(beta * std::sqrt(arg)) / i0Beta;
        design[i] = 2.0 * normalizedCutoff * sincVal * window;
        sum += design[i];
    }

    if (filterType == FirFilterType::MINIMUM_PHASE_SHARP || filterType == FirFilterType::MINIMUM_PHASE_SLOW) {
        convertToMinimumPhase(design, static_cast<int>(numTaps_));
        sum = 0.0;
        for (double d : design) sum += d;
    }

    double scale = 2.0 / (std::abs(sum) > 1e-12 ? sum : 1.0);

    poly0_.clear();
    poly1_.clear();
    poly0_.reserve((numTaps_ + 1) / 2);
    poly1_.reserve(numTaps_ / 2);

    for (size_t i = 0; i < numTaps_; ++i) {
        float tapVal = static_cast<float>(design[i] * scale);
        if (i % 2 == 0) poly0_.push_back(tapVal);
        else poly1_.push_back(tapVal);
    }

    tapsPerPhase_ = std::max(poly0_.size(), poly1_.size());
    histLen_ = static_cast<int>(tapsPerPhase_);
    histL_.assign(histLen_, 0.0f);
    histR_.assign(histLen_, 0.0f);
    histWritePos_ = (histLen_ == 0) ? 0 : (histLen_ - 1);
}

void FirStage2x::reset() {
    if (!histL_.empty()) {
        std::fill(histL_.begin(), histL_.end(), 0.0f);
        std::fill(histR_.begin(), histR_.end(), 0.0f);
        histWritePos_ = (histLen_ == 0) ? 0 : (histLen_ - 1);
    }
}

void FirStage2x::processStereo(
    const float* inL, const float* inR, size_t numFrames,
    std::vector<float, AlignedAllocator<float, 16>>& outL,
    std::vector<float, AlignedAllocator<float, 16>>& outR
) {
    if (!inL || !inR || numFrames == 0) return;
    outL.resize(numFrames * 2);
    outR.resize(numFrames * 2);

    const int hLen = histLen_;
    if (hLen <= 0) return;

    const size_t evenSize = poly0_.size();
    const size_t oddSize = poly1_.size();
    const float* c0 = poly0_.data();
    const float* c1 = poly1_.data();

    float* dstL = outL.data();
    float* dstR = outR.data();

    for (size_t n = 0; n < numFrames; ++n) {
        histWritePos_ = (histWritePos_ + 1) % hLen;
        histL_[histWritePos_] = inL[n];
        histR_[histWritePos_] = inR[n];

        float s0_L = 0.0f, s0_R = 0.0f;
        int idx = histWritePos_;
        for (size_t i = 0; i < evenSize; ++i) {
            float c = c0[i];
            s0_L += c * histL_[idx];
            s0_R += c * histR_[idx];
            idx = (idx == 0) ? (hLen - 1) : (idx - 1);
        }

        float s1_L = 0.0f, s1_R = 0.0f;
        idx = histWritePos_;
        for (size_t i = 0; i < oddSize; ++i) {
            float c = c1[i];
            s1_L += c * histL_[idx];
            s1_R += c * histR_[idx];
            idx = (idx == 0) ? (hLen - 1) : (idx - 1);
        }

        dstL[n * 2]     = s0_L;
        dstL[n * 2 + 1] = s1_L;
        dstR[n * 2]     = s0_R;
        dstR[n * 2 + 1] = s1_R;
    }
}

// -----------------------------------------------------------------------------
// DspLpcHarmonicAi 実装
// -----------------------------------------------------------------------------
DspLpcHarmonicAi::DspLpcHarmonicAi() {
    configure(DseeMode::AUTO_AI, 48000.0, 1, 0.16f, 10500.0f, true);
}

void DspLpcHarmonicAi::configure(DseeMode mode, double sampleRate, int lpcAlgo, float gain, float extractFreq, bool useQmf) {
    mode_ = mode;
    sampleRate_ = std::max(8000.0, sampleRate);
    lpcAlgo_ = lpcAlgo;
    targetGain_ = static_cast<double>(gain) * 1.6;
    useQmf_ = useQmf;
    reset();

    if (mode_ == DseeMode::OFF) {
        isBypass_ = true;
        return;
    }

    isBypass_ = false;

    double fExtract = static_cast<double>(extractFreq);
    double kExtract = std::tan(PI * fExtract / sampleRate_);
    double a0 = 1.0 + kExtract;
    hp_b0_ = 1.0 / a0;
    hp_b1_ = -1.0 / a0;
    hp_a1_ = (kExtract - 1.0) / a0;

    double fOutHp = std::min(18500.0, sampleRate_ * 0.45);
    double w0 = 2.0 * PI * fOutHp / sampleRate_;
    double alpha = std::sin(w0) / (2.0 * 0.70710678);
    double cosw0 = std::cos(w0);

    double b0_raw = (1.0 + cosw0) / 2.0;
    double b1_raw = -(1.0 + cosw0);
    double b2_raw = (1.0 + cosw0) / 2.0;
    double a0_raw = 1.0 + alpha;
    double a1_raw = -2.0 * cosw0;
    double a2_raw = 1.0 - alpha;

    double inv_a0 = 1.0 / a0_raw;
    out_hp_b0_ = b0_raw * inv_a0;
    out_hp_b1_ = b1_raw * inv_a0;
    out_hp_b2_ = b2_raw * inv_a0;
    out_hp_a1_ = a1_raw * inv_a0;
    out_hp_a2_ = a2_raw * inv_a0;
}

void DspLpcHarmonicAi::reset() {
    hp_s1_L_ = 0.0; hp_s1_R_ = 0.0;
    out_s1_L_ = 0.0; out_s2_L_ = 0.0;
    out_s1_R_ = 0.0; out_s2_R_ = 0.0;
    dcL_ = 0.0; dcR_ = 0.0;
    prevSampleL_ = 0.0; prevSampleR_ = 0.0;
    envHfL_ = 0.0; envHfR_ = 0.0;
    envTotalL_ = 0.0; envTotalR_ = 0.0;
    transientFluxL_ = 0.0; transientFluxR_ = 0.0;
    smoothedGainL_ = 0.0; smoothedGainR_ = 0.0;
}

void DspLpcHarmonicAi::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    const double maxSlewPerSample = 0.001;

    for (size_t i = 0; i < numFrames; ++i) {
        // Left
        double inL = static_cast<double>(left[i]);
        double hiL = hp_b0_ * inL + hp_s1_L_;
        hp_s1_L_ = hp_b1_ * inL - hp_a1_ * hiL;

        double absHfL = std::abs(hiL);
        double absTotalL = std::abs(inL);
        envHfL_ = envHfL_ * 0.992 + absHfL * 0.008;
        envTotalL_ = envTotalL_ * 0.995 + absTotalL * 0.005;

        double diffL = std::abs(inL - prevSampleL_);
        prevSampleL_ = inL;
        transientFluxL_ = transientFluxL_ * 0.98 + diffL * 0.02;
        double attackRatioL = std::clamp((diffL - transientFluxL_ * 1.1) / (transientFluxL_ + 1e-4), 0.0, 1.0);

        double absSmoothL = std::sqrt(hiL * hiL + 1e-7);
        dcL_ = dcL_ * 0.995 + absSmoothL * 0.005;
        double evenHarmL = (absSmoothL - dcL_) * 2.4;

        double driveL = hiL * 3.8;
        double oddHarmL = (std::tanh(driveL) - (hiL * 0.8)) * 1.5;

        double rawHarmL = 0.0;
        if (mode_ == DseeMode::AUTO_AI) {
            double evenW = 0.70 - attackRatioL * 0.25;
            double oddW  = 0.30 + attackRatioL * 0.25;
            rawHarmL = evenHarmL * evenW + oddHarmL * oddW;
        } else {
            if (lpcAlgo_ == 1) {
                rawHarmL = evenHarmL * 0.65 + oddHarmL * 0.35;
            } else if (lpcAlgo_ == 2) {
                rawHarmL = evenHarmL * 0.82 + oddHarmL * 0.18;
            } else {
                rawHarmL = evenHarmL * 0.45 + oddHarmL * 0.55;
            }
        }

        double outHarmL = out_hp_b0_ * rawHarmL + out_s1_L_;
        out_s1_L_ = out_hp_b1_ * rawHarmL - out_hp_a1_ * outHarmL + out_s2_L_;
        out_s2_L_ = out_hp_b2_ * rawHarmL - out_hp_a2_ * outHarmL;

        double targetDynGainL = std::min(envHfL_ * 10.0, targetGain_);
        double gainDiffL = targetDynGainL - smoothedGainL_;
        smoothedGainL_ += std::clamp(gainDiffL, -maxSlewPerSample, maxSlewPerSample);

        double totalL = inL + outHarmL * smoothedGainL_;
        if (totalL > 0.96) {
            totalL = 0.96 + 0.04 * std::tanh((totalL - 0.96) / 0.04);
        } else if (totalL < -0.96) {
            totalL = -0.96 + 0.04 * std::tanh((totalL + 0.96) / 0.04);
        }
        left[i] = static_cast<float>(totalL);

        // Right
        double inR = static_cast<double>(right[i]);
        double hiR = hp_b0_ * inR + hp_s1_R_;
        hp_s1_R_ = hp_b1_ * inR - hp_a1_ * hiR;

        double absHfR = std::abs(hiR);
        double absTotalR = std::abs(inR);
        envHfR_ = envHfR_ * 0.992 + absHfR * 0.008;
        envTotalR_ = envTotalR_ * 0.995 + absTotalR * 0.005;

        double diffR = std::abs(inR - prevSampleR_);
        prevSampleR_ = inR;
        transientFluxR_ = transientFluxR_ * 0.98 + diffR * 0.02;
        double attackRatioR = std::clamp((diffR - transientFluxR_ * 1.1) / (transientFluxR_ + 1e-4), 0.0, 1.0);

        double absSmoothR = std::sqrt(hiR * hiR + 1e-7);
        dcR_ = dcR_ * 0.995 + absSmoothR * 0.005;
        double evenHarmR = (absSmoothR - dcR_) * 2.4;

        double driveR = hiR * 3.8;
        double oddHarmR = (std::tanh(driveR) - (hiR * 0.8)) * 1.5;

        double rawHarmR = 0.0;
        if (mode_ == DseeMode::AUTO_AI) {
            double evenW = 0.70 - attackRatioR * 0.25;
            double oddW  = 0.30 + attackRatioR * 0.25;
            rawHarmR = evenHarmR * evenW + oddHarmR * oddW;
        } else {
            if (lpcAlgo_ == 1) {
                rawHarmR = evenHarmR * 0.65 + oddHarmR * 0.35;
            } else if (lpcAlgo_ == 2) {
                rawHarmR = evenHarmR * 0.82 + oddHarmR * 0.18;
            } else {
                rawHarmR = evenHarmR * 0.45 + oddHarmR * 0.55;
            }
        }

        double outHarmR = out_hp_b0_ * rawHarmR + out_s1_R_;
        out_s1_R_ = out_hp_b1_ * rawHarmR - out_hp_a1_ * outHarmR + out_s2_R_;
        out_s2_R_ = out_hp_b2_ * rawHarmR - out_hp_a2_ * outHarmR;

        double targetDynGainR = std::min(envHfR_ * 10.0, targetGain_);
        double gainDiffR = targetDynGainR - smoothedGainR_;
        smoothedGainR_ += std::clamp(gainDiffR, -maxSlewPerSample, maxSlewPerSample);

        double totalR = inR + outHarmR * smoothedGainR_;
        if (totalR > 0.96) {
            totalR = 0.96 + 0.04 * std::tanh((totalR - 0.96) / 0.04);
        } else if (totalR < -0.96) {
            totalR = -0.96 + 0.04 * std::tanh((totalR + 0.96) / 0.04);
        }
        right[i] = static_cast<float>(totalR);
    }
}

// -----------------------------------------------------------------------------
// DspTransientRestorer 実装
// -----------------------------------------------------------------------------
DspTransientRestorer::DspTransientRestorer() {
    configure(TransientMode::ACOUSTIC, 48000.0, true, false);
}

void DspTransientRestorer::configure(TransientMode mode, double sampleRate, bool useGroupDelay, bool useLattice) {
    mode_ = mode;
    sampleRate_ = std::max(8000.0, sampleRate);
    useGroupDelay_ = useGroupDelay;
    useLattice_ = useLattice;
    reset();

    if (mode_ == TransientMode::OFF) {
        isBypass_ = true;
        return;
    }

    isBypass_ = false;
    double timeScale = 48000.0 / sampleRate_;

    switch (mode_) {
        case TransientMode::NATURAL:
            attackGain_ = 1.2;
            fastAlpha_ = std::clamp(0.04 * timeScale, 0.005, 0.2);
            slowAlpha_ = std::clamp(0.002 * timeScale, 0.0002, 0.02);
            break;
        case TransientMode::PUNCH:
            attackGain_ = 1.8;
            fastAlpha_ = std::clamp(0.06 * timeScale, 0.008, 0.25);
            slowAlpha_ = std::clamp(0.0015 * timeScale, 0.0001, 0.015);
            break;
        case TransientMode::ACOUSTIC:
            attackGain_ = 1.4;
            fastAlpha_ = std::clamp(0.08 * timeScale, 0.01, 0.3);
            slowAlpha_ = std::clamp(0.003 * timeScale, 0.0003, 0.03);
            break;
        default:
            attackGain_ = 1.2;
            fastAlpha_ = 0.04;
            slowAlpha_ = 0.002;
            break;
    }
}

void DspTransientRestorer::reset() {
    envFastL_ = 0.0; envSlowL_ = 0.0;
    envFastR_ = 0.0; envSlowR_ = 0.0;
    prevSampleL_ = 0.0; prevSampleR_ = 0.0;
    latK1_L_ = 0.0; latK2_L_ = 0.0;
    latK1_R_ = 0.0; latK2_R_ = 0.0;
    latB1_L_ = 0.0; latB2_L_ = 0.0;
    latB1_R_ = 0.0; latB2_R_ = 0.0;
}

void DspTransientRestorer::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        double inL = static_cast<double>(left[i]);
        double absInL = std::abs(inL);
        envFastL_ = envFastL_ * (1.0 - fastAlpha_) + absInL * fastAlpha_;
        envSlowL_ = envSlowL_ * (1.0 - slowAlpha_) + absInL * slowAlpha_;

        double diffL = std::max(0.0, envFastL_ - envSlowL_);
        double transientRatioL = std::min(diffL / (envSlowL_ + 1e-4), 1.8);

        double predL = inL;
        if (useLattice_) {
            double f1 = inL - latK1_L_ * latB1_L_;
            double b1 = latB1_L_ - latK1_L_ * inL;
            latB1_L_ = inL;
            latK1_L_ = std::clamp(latK1_L_ * 0.995 + (f1 * b1) * 0.005, -0.9, 0.9);
            predL = inL + f1 * 0.3;
        }

        double deltaL = predL - prevSampleL_;
        prevSampleL_ = inL;

        double gdL = useGroupDelay_ ? (deltaL * 0.15) : 0.0;
        double outL = inL + (deltaL * attackGain_ * transientRatioL * 0.3) + gdL;
        left[i] = static_cast<float>(std::clamp(outL, -1.0, 1.0));

        double inR = static_cast<double>(right[i]);
        double absInR = std::abs(inR);
        envFastR_ = envFastR_ * (1.0 - fastAlpha_) + absInR * fastAlpha_;
        envSlowR_ = envSlowR_ * (1.0 - slowAlpha_) + absInR * slowAlpha_;

        double diffR = std::max(0.0, envFastR_ - envSlowR_);
        double transientRatioR = std::min(diffR / (envSlowR_ + 1e-4), 1.8);

        double predR = inR;
        if (useLattice_) {
            double f1 = inR - latK1_R_ * latB1_R_;
            double b1 = latB1_R_ - latK1_R_ * inR;
            latB1_R_ = inR;
            latK1_R_ = std::clamp(latK1_R_ * 0.995 + (f1 * b1) * 0.005, -0.9, 0.9);
            predR = inR + f1 * 0.3;
        }

        double deltaR = predR - prevSampleR_;
        prevSampleR_ = inR;

        double gdR = useGroupDelay_ ? (deltaR * 0.15) : 0.0;
        double outR = inR + (deltaR * attackGain_ * transientRatioR * 0.3) + gdR;
        right[i] = static_cast<float>(std::clamp(outR, -1.0, 1.0));
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
// ★ 32バンド 2次 IIR フィルタバンク (極限の軽さ ＆ 完璧な低域分離) 実装
// -----------------------------------------------------------------------------
void DspUpsampler::SpecBiquad::initBandpass(float f0, float Q, float fs) {
    s1 = 0.0f;
    s2 = 0.0f;
    env = 0.0f;

    if (f0 >= fs * 0.48f) {
        active = false;
        return;
    }
    active = true;

    double w0 = 2.0 * PI * f0 / fs;
    double sinw0 = std::sin(w0);
    double cosw0 = std::cos(w0);
    double alpha = sinw0 / (2.0 * Q);

    double b0_raw = alpha;
    double b1_raw = 0.0;
    double b2_raw = -alpha;
    double a0_raw = 1.0 + alpha;
    double a1_raw = -2.0 * cosw0;
    double a2_raw = 1.0 - alpha;

    double inv_a0 = 1.0 / a0_raw;
    b0 = static_cast<float>(b0_raw * inv_a0);
    b1 = static_cast<float>(b1_raw * inv_a0);
    b2 = static_cast<float>(b2_raw * inv_a0);
    a1 = static_cast<float>(a1_raw * inv_a0);
    a2 = static_cast<float>(a2_raw * inv_a0);
}

void DspUpsampler::initSpectrumFilterBank(float fs) {
    static constexpr float FREQS[32] = {
        25.0f, 31.5f, 40.0f, 50.0f, 63.0f, 80.0f, 100.0f, 125.0f, 160.0f, 200.0f,
        250.0f, 315.0f, 400.0f, 500.0f, 630.0f, 800.0f, 1000.0f, 1250.0f, 1600.0f, 2000.0f,
        2500.0f, 3150.0f, 4000.0f, 5000.0f, 6300.0f, 8000.0f, 10000.0f, 12500.0f, 16000.0f, 20000.0f,
        28000.0f, 40000.0f
    };

    for (int b = 0; b < 32; ++b) {
        // 低域は選択度をややシャープ(Q=2.2)に、高域は広帯域(Q=1.6)に設定
        float Q = (b < 12) ? 2.2f : 1.6f;
        specFilters_[b].initBandpass(FREQS[b], Q, fs);
    }
}

void DspUpsampler::processSpectrumFilterBank(const float* l, const float* r, size_t numFrames) {
    if (!l || !r || numFrames == 0) return;

    // 処理負荷を極限まで抑えるため 8サンプルごとにデシメーション追従
    constexpr size_t step = 8;
    for (size_t i = 0; i < numFrames; i += step) {
        float mono = (l[i] + r[i]) * 0.5f;

        for (int b = 0; b < 32; ++b) {
            auto& f = specFilters_[b];
            if (!f.active) continue;

            float y = f.processSample(mono);
            float absY = std::abs(y);

            // アタック: 超高速 (1ms), リリース: 滑らか (45ms)
            if (absY > f.env) {
                f.env = f.env * 0.70f + absY * 0.30f;
            } else {
                f.env = f.env * 0.985f;
            }
        }
    }

    // 各バンドの dBFS 算出 (感度重み付け)
    for (int b = 0; b < 32; ++b) {
        auto& f = specFilters_[b];
        if (!f.active) {
            spectrumDb_[b] = -60.0f;
            continue;
        }

        // 超高域の視覚感度を補正
        float gainFactor = (b >= 28) ? 3.0f : ((b >= 20) ? 1.8f : 1.2f);
        float val = f.env * gainFactor;
        float db = (val > 1e-5f) ? (20.0f * std::log10(val)) : -60.0f;
        spectrumDb_[b] = std::clamp(db, -60.0f, 0.0f);
    }
}

// -----------------------------------------------------------------------------
// DspUpsampler 実装
// -----------------------------------------------------------------------------
DspUpsampler::DspUpsampler() {
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

void DspUpsampler::setDseeMode(DseeMode mode) {
    dseeMode_ = mode;
    lpcHarmonicAi_.configure(dseeMode_, static_cast<double>(inSampleRate_ * factor_), customLpcAlgo_, customGain_, customExtractFreq_, customUseQmf_);
}

void DspUpsampler::setDseeCustomParams(int lpcAlgo, float gain, float extractFreq, bool useQmf) {
    customLpcAlgo_ = lpcAlgo;
    customGain_ = gain;
    customExtractFreq_ = extractFreq;
    customUseQmf_ = useQmf;
    lpcHarmonicAi_.configure(dseeMode_, static_cast<double>(inSampleRate_ * factor_), customLpcAlgo_, customGain_, customExtractFreq_, customUseQmf_);
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
        for (int n = 0; n < totalTaps; ++n) {
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
        double sincVal = (t == 0.0) ? 1.0 : (std::sin(2.0 * PI * cutoff * t) / (PI * t));
        double norm = (2.0 * i / (totalTaps - 1)) - 1.0;
        double arg = 1.0 - norm * norm;
        double window = (arg >= 0.0) ? (besselI0(beta * std::sqrt(arg)) / i0Beta) : 0.0;
        protoFilter[i] = sincVal * window;
    }

    if (filterType_ == FirFilterType::MINIMUM_PHASE_SHARP || filterType_ == FirFilterType::MINIMUM_PHASE_SLOW) {
        convertToMinimumPhase(protoFilter, totalTaps);
    }

    double sumGain = 0.0;
    for (int i = 0; i < totalTaps; ++i) sumGain += protoFilter[i];
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

    cascadeStages_[0].configure(255, inSampleRate_ * 0.5, inSampleRate_ * 2.0, filterType_);
    cascadeStages_[1].configure(63, inSampleRate_, inSampleRate_ * 4.0, filterType_);
    cascadeStages_[2].configure(39, inSampleRate_ * 2.0, inSampleRate_ * 8.0, filterType_);

    float currentFs = inSampleRate_ * (isDirectSource_ ? 1 : factor_);
    equalizer_.setSampleRate(static_cast<double>(currentFs));
    dcPhaseLinearizer_.configure(dcPhaseType_, static_cast<double>(currentFs));
    transientRestorer_.configure(transientMode_, static_cast<double>(currentFs), customUseGroupDelay_, customUseLattice_);
    lpcHarmonicAi_.configure(dseeMode_, static_cast<double>(currentFs), customLpcAlgo_, customGain_, customExtractFreq_, customUseQmf_);

    // ★ フィルタバンクの周波数初期化
    initSpectrumFilterBank(currentFs);
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
    for (auto& f : specFilters_) {
        f.s1 = 0.0f; f.s2 = 0.0f; f.env = 0.0f;
    }
    equalizer_.reset();
    dcPhaseLinearizer_.reset();
    transientRestorer_.reset();
    lpcHarmonicAi_.reset();
}

void DspUpsampler::getSpectrum(float* out32Bands) {
    if (!out32Bands) return;
    std::memcpy(out32Bands, spectrumDb_, sizeof(spectrumDb_));
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

    // [Step 1] アップサンプリング
    if (currentFactor <= 1) {
        std::memcpy(tempOutL_.data(), tempInL_.data(), numInFrames * sizeof(float));
        std::memcpy(tempOutR_.data(), tempInR_.data(), numInFrames * sizeof(float));
    } else if (isCascadeFir_) {
        if (currentFactor == 2) {
            cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, tempOutL_, tempOutR_);
        } else if (currentFactor == 4) {
            cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
            cascadeStages_[1].processStereo(stageBuf1_L_.data(), stageBuf1_R_.data(), numInFrames * 2, tempOutL_, tempOutR_);
        } else if (currentFactor == 8) {
            cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
            cascadeStages_[1].processStereo(stageBuf1_L_.data(), stageBuf1_R_.data(), numInFrames * 2, stageBuf2_L_, stageBuf2_R_);
            cascadeStages_[2].processStereo(stageBuf2_L_.data(), stageBuf2_R_.data(), numInFrames * 4, tempOutL_, tempOutR_);
        }
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

    // [Step 2 & Step 3] DSEE 超高域倍音復元ステージ
    if (!isDirectSource_) {
        if (currentFactor >= 2 && dseeMode_ != DseeMode::OFF) {
            transientRestorer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
            lpcHarmonicAi_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
        }

        // [Step 4] 64-bit 10-Band EQ
        equalizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);

        // [Step 5] DC Phase Linearizer
        dcPhaseLinearizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
    }

    // ★ 32バンド 2次 IIR フィルタバンクによる超軽量・超高精度スペクトル追従
    processSpectrumFilterBank(tempOutL_.data(), tempOutR_.data(), numOutFrames);

    // [Step 6] ディザリング ＆ PCM パッキング
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
                double dL = getTpdfDitherL();
                double dR = getTpdfDitherR(lrIndependentDither_);

                if (ditherMode_ == DitherMode::TPDF) {
                    shapedL += dL;
                    shapedR += dR;
                } else if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED) {
                    shapedL += (1.5 * errHistL_[0] - 0.6 * errHistL_[1]) + dL;
                    shapedR += (1.5 * errHistR_[0] - 0.6 * errHistR_[1]) + dR;
                } else if (ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                    shapedL += (2.033 * errHistL_[0] - 2.165 * errHistL_[1] + 1.959 * errHistL_[2] - 0.827 * errHistL_[3]) + dL;
                    shapedR += (2.033 * errHistR_[0] - 2.165 * errHistR_[1] + 1.959 * errHistR_[2] - 0.827 * errHistR_[3]) + dR;
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
                double dL = getTpdfDitherL();
                double dR = getTpdfDitherR(lrIndependentDither_);

                if (ditherMode_ == DitherMode::TPDF) {
                    shapedL += dL;
                    shapedR += dR;
                } else if (ditherMode_ == DitherMode::HIGH_PASS_SHAPED) {
                    shapedL += (1.5 * errHistL_[0] - 0.6 * errHistL_[1]) + dL;
                    shapedR += (1.5 * errHistR_[0] - 0.6 * errHistR_[1]) + dR;
                } else if (ditherMode_ == DitherMode::PSYCHOACOUSTIC) {
                    shapedL += (2.033 * errHistL_[0] - 2.165 * errHistL_[1] + 1.959 * errHistL_[2] - 0.827 * errHistL_[3]) + dL;
                    shapedR += (2.033 * errHistR_[0] - 2.165 * errHistR_[1] + 1.959 * errHistR_[2] - 0.827 * errHistR_[3]) + dR;
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