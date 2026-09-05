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

double FirStage2x::besselI0(double x) {
    double sum = 1.0, term = 1.0, halfX = x * 0.5;
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
    for (int n = 1; n < half; ++n) causalCepstrum[n] = 2.0 * cepstrum[n];
    causalCepstrum[half] = cepstrum[half];

    std::vector<double> minReal(fftSize, 0.0), minImag(fftSize, 0.0);
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

    size_t rawEven = (numTaps_ + 1) / 2;
    size_t rawOdd  = numTaps_ / 2;
    tapsPerPhase_ = std::max(rawEven, rawOdd);
    if (tapsPerPhase_ % 4 != 0) {
        tapsPerPhase_ += (4 - (tapsPerPhase_ % 4));
    }

    poly0_.assign(tapsPerPhase_, 0.0f);
    poly1_.assign(tapsPerPhase_, 0.0f);

    for (size_t i = 0; i < numTaps_; ++i) {
        float tapVal = static_cast<float>(design[i] * scale);
        if (i % 2 == 0) {
            size_t subIdx = i / 2;
            if (subIdx < tapsPerPhase_) poly0_[tapsPerPhase_ - 1 - subIdx] = tapVal;
        } else {
            size_t subIdx = i / 2;
            if (subIdx < tapsPerPhase_) poly1_[tapsPerPhase_ - 1 - subIdx] = tapVal;
        }
    }

    mirrorHistL_.assign(tapsPerPhase_ * 2, 0.0f);
    mirrorHistR_.assign(tapsPerPhase_ * 2, 0.0f);
    writePos_ = 0;
}

void FirStage2x::reset() {
    std::fill(mirrorHistL_.begin(), mirrorHistL_.end(), 0.0f);
    std::fill(mirrorHistR_.begin(), mirrorHistR_.end(), 0.0f);
    writePos_ = 0;
}

void FirStage2x::processStereo(
    const float* inL, const float* inR, size_t numFrames,
    std::vector<float, AlignedAllocator<float, 16>>& outL,
    std::vector<float, AlignedAllocator<float, 16>>& outR
) {
    if (!inL || !inR || numFrames == 0 || tapsPerPhase_ == 0) return;
    outL.resize(numFrames * 2);
    outR.resize(numFrames * 2);

    const float* c0 = poly0_.data();
    const float* c1 = poly1_.data();
    const size_t tpp = tapsPerPhase_;

    float* dstL = outL.data();
    float* dstR = outR.data();

    for (size_t n = 0; n < numFrames; ++n) {
        mirrorHistL_[writePos_]        = inL[n];
        mirrorHistL_[writePos_ + tpp]  = inL[n];
        mirrorHistR_[writePos_]        = inR[n];
        mirrorHistR_[writePos_ + tpp]  = inR[n];

        const float* hPtrL = &mirrorHistL_[writePos_ + 1];
        const float* hPtrR = &mirrorHistR_[writePos_ + 1];

#if USE_ARM_NEON
        float32x4_t acc0_L = vdupq_n_f32(0.0f);
        float32x4_t acc1_L = vdupq_n_f32(0.0f);
        float32x4_t acc0_R = vdupq_n_f32(0.0f);
        float32x4_t acc1_R = vdupq_n_f32(0.0f);

        for (size_t i = 0; i < tpp; i += 4) {
            float32x4_t xL = vld1q_f32(hPtrL + i);
            float32x4_t xR = vld1q_f32(hPtrR + i);
            float32x4_t k0 = vld1q_f32(c0 + i);
            float32x4_t k1 = vld1q_f32(c1 + i);

            acc0_L = vmlaq_f32(acc0_L, xL, k0);
            acc1_L = vmlaq_f32(acc1_L, xL, k1);
            acc0_R = vmlaq_f32(acc0_R, xR, k0);
            acc1_R = vmlaq_f32(acc1_R, xR, k1);
        }

#if defined(__aarch64__)
        dstL[n * 2]     = vaddvq_f32(acc0_L);
        dstL[n * 2 + 1] = vaddvq_f32(acc1_L);
        dstR[n * 2]     = vaddvq_f32(acc0_R);
        dstR[n * 2 + 1] = vaddvq_f32(acc1_R);
#else
        float32x2_t r0L = vadd_f32(vget_low_f32(acc0_L), vget_high_f32(acc0_L));
        float32x2_t r1L = vadd_f32(vget_low_f32(acc1_L), vget_high_f32(acc1_L));
        float32x2_t r0R = vadd_f32(vget_low_f32(acc0_R), vget_high_f32(acc0_R));
        float32x2_t r1R = vadd_f32(vget_low_f32(acc1_R), vget_high_f32(acc1_R));
        dstL[n * 2]     = vget_lane_f32(vpadd_f32(r0L, r0L), 0);
        dstL[n * 2 + 1] = vget_lane_f32(vpadd_f32(r1L, r1L), 0);
        dstR[n * 2]     = vget_lane_f32(vpadd_f32(r0R, r0R), 0);
        dstR[n * 2 + 1] = vget_lane_f32(vpadd_f32(r1R, r1R), 0);
#endif
#else
        float s0_L = 0.0f, s1_L = 0.0f;
        float s0_R = 0.0f, s1_R = 0.0f;
        for (size_t i = 0; i < tpp; ++i) {
            s0_L += c0[i] * hPtrL[i];
            s1_L += c1[i] * hPtrL[i];
            s0_R += c0[i] * hPtrL[i];
            s1_R += c1[i] * hPtrL[i];
        }
        dstL[n * 2]     = s0_L;
        dstL[n * 2 + 1] = s1_L;
        dstR[n * 2]     = s0_R;
        dstR[n * 2 + 1] = s1_R;
#endif
        writePos_++;
        if (writePos_ >= static_cast<int>(tpp)) {
            writePos_ = 0;
        }
    }
}

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
        double outL = inL + (deltaL * attackGain_ * transientRatioL * 0.18) + gdL;
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
        double outR = inR + (deltaR * attackGain_ * transientRatioR * 0.18) + gdR;
        right[i] = static_cast<float>(std::clamp(outR, -1.0, 1.0));
    }
}

// -----------------------------------------------------------------------------
// FREQ Engine 実装 (Center-Locked In-Phase Harmonics & Direct Focus)
// -----------------------------------------------------------------------------
DspFreqEngine::DspFreqEngine() {
    configure(FreqMode::AUTO_AI, 48000.0, 0.26f, 7200.0f);
}

void DspFreqEngine::configure(FreqMode mode, double sampleRate, float gain, float extractFreq) {
    mode_ = mode;
    sampleRate_ = std::max(8000.0, sampleRate);
    targetGain_ = std::clamp(gain, 0.0f, 1.0f);
    reset();

    if (mode_ == FreqMode::OFF) {
        isBypass_ = true;
        return;
    }
    isBypass_ = false;

    double fExtract = 7200.0;
    double fOutHp   = 12500.0;
    evenRatio_ = 0.70;
    oddRatio_  = 0.30;
    modeGainScale_ = 1.25;

    switch (mode_) {
        case FreqMode::AUTO_AI:
            fExtract = (extractFreq > 1000.0f) ? static_cast<double>(extractFreq) : 7200.0;
            fOutHp   = 12000.0;
            evenRatio_ = 0.70;
            oddRatio_  = 0.30;
            modeGainScale_ = 1.30;
            break;

        case FreqMode::STUDIO_VOCAL:
            fExtract = (extractFreq > 1000.0f) ? static_cast<double>(extractFreq) : 6200.0;
            fOutHp   = 12000.0;
            evenRatio_ = 0.75;
            oddRatio_  = 0.25;
            modeGainScale_ = 1.35;
            break;

        case FreqMode::ACOUSTIC_INSTRUMENT:
            fExtract = (extractFreq > 1000.0f) ? static_cast<double>(extractFreq) : 6800.0;
            fOutHp   = 12500.0;
            evenRatio_ = 0.70;
            oddRatio_  = 0.30;
            modeGainScale_ = 1.20;
            break;

        case FreqMode::DYNAMIC_PERCUSSION:
            fExtract = (extractFreq > 1000.0f) ? static_cast<double>(extractFreq) : 9500.0;
            fOutHp   = 13500.0;
            evenRatio_ = 0.45;
            oddRatio_  = 0.55;
            modeGainScale_ = 1.15;
            break;

        case FreqMode::AIR_EXPANDER:
            fExtract = (extractFreq > 1000.0f) ? static_cast<double>(extractFreq) : 10500.0;
            fOutHp   = 14000.0;
            evenRatio_ = 0.50;
            oddRatio_  = 0.50;
            modeGainScale_ = 1.25;
            break;

        default:
            break;
    }

    fExtract = std::clamp(fExtract, 1000.0, sampleRate_ * 0.42);
    fOutHp   = std::clamp(fOutHp, 2000.0, sampleRate_ * 0.45);

    double w0_in = 2.0 * PI * fExtract / sampleRate_;
    double alpha_in = std::sin(w0_in) / (2.0 * 0.70710678);
    double cosw0_in = std::cos(w0_in);

    double in_b0 = (1.0 + cosw0_in) * 0.5;
    double in_b1 = -(1.0 + cosw0_in);
    double in_b2 = (1.0 + cosw0_in) * 0.5;
    double in_a0 = 1.0 + alpha_in;
    double in_a1 = -2.0 * cosw0_in;
    double in_a2 = 1.0 - alpha_in;

    double inv_in_a0 = 1.0 / in_a0;
    in_hp_b0_ = in_b0 * inv_in_a0;
    in_hp_b1_ = in_b1 * inv_in_a0;
    in_hp_b2_ = in_b2 * inv_in_a0;
    in_hp_a1_ = in_a1 * inv_in_a0;
    in_hp_a2_ = in_a2 * inv_in_a0;

    double w0_out = 2.0 * PI * fOutHp / sampleRate_;
    double alpha_out = std::sin(w0_out) / (2.0 * 0.70710678);
    double cosw0_out = std::cos(w0_out);

    double out_b0 = (1.0 + cosw0_out) * 0.5;
    double out_b1 = -(1.0 + cosw0_out);
    double out_b2 = (1.0 + cosw0_out) * 0.5;
    double out_a0 = 1.0 + alpha_out;
    double out_a1 = -2.0 * cosw0_out;
    double out_a2 = 1.0 - alpha_out;

    double inv_out_a0 = 1.0 / out_a0;
    out_hp_b0_ = out_b0 * inv_out_a0;
    out_hp_b1_ = out_b1 * inv_out_a0;
    out_hp_b2_ = out_b2 * inv_out_a0;
    out_hp_a1_ = out_a1 * inv_out_a0;
    out_hp_a2_ = out_a2 * inv_out_a0;

    double fSilk = std::min(36000.0, sampleRate_ * 0.44);
    double w0_silk = 2.0 * PI * fSilk / sampleRate_;
    double alpha_silk = std::sin(w0_silk) / (2.0 * 0.70710678);
    double cosw0_silk = std::cos(w0_silk);

    double silk_b0 = (1.0 - cosw0_silk) * 0.5;
    double silk_b1 = 1.0 - cosw0_silk;
    double silk_b2 = (1.0 - cosw0_silk) * 0.5;
    double silk_a0 = 1.0 + alpha_silk;
    double silk_a1 = -2.0 * cosw0_silk;
    double silk_a2 = 1.0 - alpha_silk;

    double inv_silk_a0 = 1.0 / silk_a0;
    silk_lp_b0_ = silk_b0 * inv_silk_a0;
    silk_lp_b1_ = silk_b1 * inv_silk_a0;
    silk_lp_b2_ = silk_b2 * inv_silk_a0;
    silk_lp_a1_ = silk_a1 * inv_silk_a0;
    silk_lp_a2_ = silk_a2 * inv_silk_a0;

    double fFormant = std::min(3200.0, sampleRate_ * 0.42);
    double w0_f = 2.0 * PI * fFormant / sampleRate_;
    double alpha_f = std::sin(w0_f) / (2.0 * 1.4);
    double cosw0_f = std::cos(w0_f);

    double f_b0 = alpha_f;
    double f_b1 = 0.0;
    double f_b2 = -alpha_f;
    double f_a0 = 1.0 + alpha_f;
    double f_a1 = -2.0 * cosw0_f;
    double f_a2 = 1.0 - alpha_f;

    double inv_f_a0 = 1.0 / f_a0;
    formant_bp_b0_ = f_b0 * inv_f_a0;
    formant_bp_b1_ = f_b1 * inv_f_a0;
    formant_bp_b2_ = f_b2 * inv_f_a0;
    formant_bp_a1_ = f_a1 * inv_f_a0;
    formant_bp_a2_ = f_a2 * inv_f_a0;
}

void DspFreqEngine::reset() {
    in_s1_L_ = 0.0; in_s2_L_ = 0.0;
    in_s1_R_ = 0.0; in_s2_R_ = 0.0;
    out_s1_L_ = 0.0; out_s2_L_ = 0.0;
    out_s1_R_ = 0.0; out_s2_R_ = 0.0;
    silk_s1_L_ = 0.0; silk_s2_L_ = 0.0;
    silk_s1_R_ = 0.0; silk_s2_R_ = 0.0;
    formant_s1_L_ = 0.0; formant_s2_L_ = 0.0;
    formant_s1_R_ = 0.0; formant_s2_R_ = 0.0;
    r0_L_ = 1e-4; r0_R_ = 1e-4;
    smoothedGainL_ = 0.0; smoothedGainR_ = 0.0;
    prevPowL_ = 0.0; prevPowR_ = 0.0;
    transientFluxL_ = 0.0; transientFluxR_ = 0.0;
    noiseFloorL_ = 1e-5; noiseFloorR_ = 1e-5;
}

void DspFreqEngine::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        double inL = static_cast<double>(left[i]);
        double inR = static_cast<double>(right[i]);

        // 1. 高域抽出ハイパス
        double hiL = in_hp_b0_ * inL + in_s1_L_;
        in_s1_L_ = in_hp_b1_ * inL - in_hp_a1_ * hiL + in_s2_L_;
        in_s2_L_ = in_hp_b2_ * inL - in_hp_a2_ * hiL;

        double hiR = in_hp_b0_ * inR + in_s1_R_;
        in_s1_R_ = in_hp_b1_ * inR - in_hp_a1_ * hiR + in_s2_R_;
        in_s2_R_ = in_hp_b2_ * inR - in_hp_a2_ * hiR;

        // 2. 口腔共鳴フォルマント抽出 (3.2kHz BPF)
        double formantL = formant_bp_b0_ * inL + formant_s1_L_;
        formant_s1_L_ = formant_bp_b1_ * inL - formant_bp_a1_ * formantL + formant_s2_L_;
        formant_s2_L_ = formant_bp_b2_ * inL - formant_bp_a2_ * formantL;

        double formantR = formant_bp_b0_ * inR + formant_s1_R_;
        formant_s1_R_ = formant_bp_b1_ * inR - formant_bp_a1_ * formantR + formant_s2_R_;
        formant_s2_R_ = formant_bp_b2_ * inR - formant_bp_a2_ * formantR;

        // 3. パワー & 暗騒音フロア追従
        double hiPowL = hiL * hiL;
        double diffPowL = std::max(0.0, hiPowL - prevPowL_);
        prevPowL_ = hiPowL;
        transientFluxL_ = transientFluxL_ * 0.94 + diffPowL * 0.06;

        double hiPowR = hiR * hiR;
        double diffPowR = std::max(0.0, hiPowR - prevPowR_);
        prevPowR_ = hiPowR;
        transientFluxR_ = transientFluxR_ * 0.94 + diffPowR * 0.06;

        if (hiPowL < noiseFloorL_) noiseFloorL_ = noiseFloorL_ * 0.9992 + hiPowL * 0.0008;
        else noiseFloorL_ = noiseFloorL_ * 0.99998 + hiPowL * 0.00002;
        noiseFloorL_ = std::clamp(noiseFloorL_, 1e-10, 1e-4);

        if (hiPowR < noiseFloorR_) noiseFloorR_ = noiseFloorR_ * 0.9992 + hiPowR * 0.0008;
        else noiseFloorR_ = noiseFloorR_ * 0.99998 + hiPowR * 0.00002;
        noiseFloorR_ = std::clamp(noiseFloorR_, 1e-10, 1e-4);

        double snrFloorL = hiPowL / (noiseFloorL_ + 1e-11);
        double snrFloorR = hiPowR / (noiseFloorR_ + 1e-11);
        double formantPowL = formantL * formantL;
        double formantPowR = formantR * formantR;

        bool isBreathContextL = (formantPowL > noiseFloorL_ * 8.0) && (hiPowL > noiseFloorL_ * 4.0);
        bool isBreathContextR = (formantPowR > noiseFloorR_ * 8.0) && (hiPowR > noiseFloorR_ * 4.0);

        double floorGateL = 1.0;
        if (!isBreathContextL) {
            if (snrFloorL < 1.5) floorGateL = 0.15;
            else if (snrFloorL < 4.0) {
                double t = (snrFloorL - 1.5) / 2.5;
                floorGateL = 0.15 + 0.85 * (t * t);
            }
        }

        double floorGateR = 1.0;
        if (!isBreathContextR) {
            if (snrFloorR < 1.5) floorGateR = 0.15;
            else if (snrFloorR < 4.0) {
                double t = (snrFloorR - 1.5) / 2.5;
                floorGateR = 0.15 + 0.85 * (t * t);
            }
        }

        double adaptAlphaL = (hiPowL > r0_L_) ? 0.025 : 0.003;
        r0_L_ = r0_L_ * (1.0 - adaptAlphaL) + hiPowL * adaptAlphaL;
        double rmsL = std::sqrt(std::max(1e-12, r0_L_));

        double adaptAlphaR = (hiPowR > r0_R_) ? 0.025 : 0.003;
        r0_R_ = r0_R_ * (1.0 - adaptAlphaR) + hiPowR * adaptAlphaR;
        double rmsR = std::sqrt(std::max(1e-12, r0_R_));

        double tonalityL = std::clamp(1.0 - (transientFluxL_ / (rmsL * 2.2 + 1e-5)), 0.0, 1.0);
        double tonalityR = std::clamp(1.0 - (transientFluxR_ / (rmsR * 2.2 + 1e-5)), 0.0, 1.0);
        if (isBreathContextL) tonalityL = std::max(tonalityL, 0.65);
        if (isBreathContextR) tonalityR = std::max(tonalityR, 0.65);

        double effEvenL = (mode_ == FreqMode::AUTO_AI) ? (0.40 + 0.40 * tonalityL) : evenRatio_;
        double effOddL  = (mode_ == FreqMode::AUTO_AI) ? (0.60 - 0.40 * tonalityL) : oddRatio_;

        double effEvenR = (mode_ == FreqMode::AUTO_AI) ? (0.40 + 0.40 * tonalityR) : evenRatio_;
        double effOddR  = (mode_ == FreqMode::AUTO_AI) ? (0.60 - 0.40 * tonalityR) : oddRatio_;

        if (r0_L_ < 1e-7 || floorGateL < 0.2) smoothedGainL_ *= 0.94;
        else {
            double targetL = std::min(rmsL * 0.85, static_cast<double>(targetGain_ * modeGainScale_ * 0.25f)) * floorGateL;
            smoothedGainL_ += (targetL - smoothedGainL_) * ((targetL > smoothedGainL_) ? 0.035 : 0.004);
        }

        if (r0_R_ < 1e-7 || floorGateR < 0.2) smoothedGainR_ *= 0.94;
        else {
            double targetR = std::min(rmsR * 0.85, static_cast<double>(targetGain_ * modeGainScale_ * 0.25f)) * floorGateR;
            smoothedGainR_ += (targetR - smoothedGainR_) * ((targetR > smoothedGainR_) ? 0.035 : 0.004);
        }

        // =====================================================================
        // ★ センター・ボーカル・コヒーレンス (Mid/Side 同相倍音生成)
        // =====================================================================
        // 中央にいるボーカル成分(Mid)を抽出
        double hiMid = (hiL + hiR) * 0.5;
        double hiSide = (hiL - hiR) * 0.5;
        double midRms = (rmsL + rmsR) * 0.5;

        // センター優位度: 1.0に近いほどボーカルソロや中央楽器
        double midPow = hiMid * hiMid;
        double sidePow = hiSide * hiSide;
        double centerBias = midPow / (midPow + sidePow + 1e-9);

        // センターボーカル用の強力な同相倍音コア (左右完全一致で眉間に結像)
        double normMid = std::clamp(hiMid / (midRms * 1.414 + 1e-5), -3.0, 3.0);
        double normSqMid = normMid * normMid;
        double h2_Mid = (normSqMid - 0.70) * midRms;
        double h3_Mid = (normSqMid * normMid - 0.75 * normMid) * (midRms * 0.45);
        double h4_Mid = (normSqMid * normSqMid - 1.5 * normSqMid + 0.35) * (midRms * 0.20);
        double airWeightMid = (isBreathContextL || isBreathContextR) ? 0.25 : 0.15;
        double harmMid = (effEvenL * h2_Mid + effOddL * h3_Mid + airWeightMid * h4_Mid);

        // 左右の個別倍音
        double normL = std::clamp(hiL / (rmsL * 1.414 + 1e-5), -3.0, 3.0);
        double normSqL = normL * normL;
        double h2_L = (normSqL - 0.70) * rmsL;
        double h3_L = (normSqL * normL - 0.75 * normL) * (rmsL * 0.45);
        double h4_L = (normSqL * normSqL - 1.5 * normSqL + 0.35) * (rmsL * 0.20);
        double airWeightL = isBreathContextL ? 0.25 : 0.15;
        double harmRawL = (effEvenL * h2_L + effOddL * h3_L + airWeightL * h4_L);

        double normR = std::clamp(hiR / (rmsR * 1.414 + 1e-5), -3.0, 3.0);
        double normSqR = normR * normR;
        double h2_R = (normSqR - 0.70) * rmsR;
        double h3_R = (normSqR * normR - 0.75 * normR) * (rmsR * 0.45);
        double h4_R = (normSqR * normSqR - 1.5 * normSqR + 0.35) * (rmsR * 0.20);
        double airWeightR = isBreathContextR ? 0.25 : 0.15;
        double harmRawR = (effEvenR * h2_R + effOddR * h3_R + airWeightR * h4_R);

        // センター優位度に応じて、同相倍音(Mid)のブレンド比率を引き上げ、音像をガチッと真ん中に固定
        double harmL = harmRawL * (1.0 - centerBias * 0.85) + harmMid * (centerBias * 0.85);
        double harmR = harmRawR * (1.0 - centerBias * 0.85) + harmMid * (centerBias * 0.85);

        // 4. 出力ハイパス & シルキースムージング
        double outHarmL = out_hp_b0_ * harmL + out_s1_L_;
        out_s1_L_ = out_hp_b1_ * harmL - out_hp_a1_ * outHarmL + out_s2_L_;
        out_s2_L_ = out_hp_b2_ * harmL - out_hp_a2_ * outHarmL;

        double silkHarmL = silk_lp_b0_ * outHarmL + silk_s1_L_;
        silk_s1_L_ = silk_lp_b1_ * outHarmL - silk_lp_a1_ * silkHarmL + silk_s2_L_;
        silk_s2_L_ = silk_lp_b2_ * outHarmL - silk_lp_a2_ * silkHarmL;

        double outHarmR = out_hp_b0_ * harmR + out_s1_R_;
        out_s1_R_ = out_hp_b1_ * harmR - out_hp_a1_ * outHarmR + out_s2_R_;
        out_s2_R_ = out_hp_b2_ * harmR - out_hp_a2_ * outHarmR;

        double silkHarmR = silk_lp_b0_ * outHarmR + silk_s1_R_;
        silk_s1_R_ = silk_lp_b1_ * outHarmR - silk_lp_a1_ * silkHarmR + silk_s2_R_;
        silk_s2_R_ = silk_lp_b2_ * outHarmR - silk_lp_a2_ * silkHarmR;

        // ★ 原音のボーカル芯(inL/inR)は完全無傷(差分歪みゼロ)で出力し、倍音のみを加算
        double totalL = inL + silkHarmL * smoothedGainL_;
        double totalR = inR + silkHarmR * smoothedGainR_;

        left[i] = static_cast<float>(std::clamp(totalL, -1.0, 1.0));
        right[i] = static_cast<float>(std::clamp(totalR, -1.0, 1.0));
    }
}

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
    double f0 = 45.0, Q = 0.707, gainDb = 1.2;

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
    for (int n = 1; n < half; ++n) causalCepstrum[n] = 2.0 * cepstrum[n];
    causalCepstrum[half] = cepstrum[half];

    std::vector<double> minReal(fftSize, 0.0), minImag(fftSize, 0.0);
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
            polyCoeffs_[p][k] = (protoIdx < totalTaps) ? static_cast<float>(protoFilter[protoIdx] * scale) : 0.0f;
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
    sbrPhaseL_ = 0.0f; sbrPhaseR_ = 0.0f;
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
        float w = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(PI) * i / (N - 1)));
        realHi[i] = specRingBuf_[idx] * w;
        imagHi[i] = 0.0f;
    }

    for (int i = 0; i < N; ++i) {
        int baseIdx = (currentPos + 4096 - 4096 + (i * 2)) & 4095;
        float avg = (specRingBuf_[baseIdx] + specRingBuf_[(baseIdx + 1) & 4095]) * 0.5f;
        float w = 0.5f * (1.0f - std::cos(2.0f * static_cast<float>(PI) * i / (N - 1)));
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
            double angle = -2.0 * PI / len;
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
            float decayPerBand = isFreqActive ? 3.0f : 4.5f;
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

// ★ センター・ボーカル保護型 M/S 空間処理
void DspUpsampler::processMsSpatial(float* left, float* right, size_t numFrames) {
    if (!isMsSpatial_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        float l = left[i];
        float r = right[i];
        float m = (l + r) * 0.5f;
        float s = (l - r) * 0.5f;

        // センター比率 (Mid優位度): ボーカル時はSideを強調せず直接音(Mid)の芯を保護
        float absM = std::abs(m);
        float absS = std::abs(s);
        float centerBias = absM / (absM + absS + 1e-4f);

        // センターボーカルが強い瞬間はSide拡張を自動抑制し、ボーカルを目の前に固定
        float spatialGain = 0.18f * (1.0f - centerBias * 0.80f);

        float diffL = s - prevSideL_;
        prevSideL_ = s;
        float sSpatial = s + diffL * spatialGain;

        // 直接音(Mid)の芯をしっかり保ち、ボーカルが後ろへ引っ込むのを防止
        float mDirect = m * (1.0f + 0.04f * centerBias);

        left[i] = std::clamp(mDirect + sSpatial, -1.0f, 1.0f);
        right[i] = std::clamp(mDirect - sSpatial, -1.0f, 1.0f);
    }
}

void DspUpsampler::processDynamicSbr(float* left, float* right, size_t numFrames) {
    return;
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

    bool is441Base = (std::abs(inSampleRate_ - 44100.0f) < 100.0f);
    double speedRatio = is441Base ? (44100.0 / 48000.0) : 1.0;

    int currentFactor = isDirectSource_ ? 1 : factor_;
    size_t numOutFrames = static_cast<size_t>(std::round(numInFrames * speedRatio * currentFactor));
    if (numOutFrames == 0) return 0;

    tempOutL_.resize(numOutFrames);
    tempOutR_.resize(numOutFrames);

    if (!isDirectSource_ && currentFactor > 1) {
        if (isCascadeFir_) {
            if (currentFactor == 2) {
                cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
                if (!is441Base) {
                    std::memcpy(tempOutL_.data(), stageBuf1_L_.data(), numOutFrames * sizeof(float));
                    std::memcpy(tempOutR_.data(), stageBuf1_R_.data(), numOutFrames * sizeof(float));
                } else {
                    double step = (double)(numInFrames * 2) / (double)numOutFrames;
                    for (size_t i = 0; i < numOutFrames; ++i) {
                        double p = i * step; int idx = (int)p; double f = p - idx;
                        int i1 = std::min((int)(numInFrames * 2) - 1, idx);
                        int i2 = std::min((int)(numInFrames * 2) - 1, idx + 1);
                        tempOutL_[i] = static_cast<float>(stageBuf1_L_[i1] * (1.0 - f) + stageBuf1_L_[i2] * f);
                        tempOutR_[i] = static_cast<float>(stageBuf1_R_[i1] * (1.0 - f) + stageBuf1_R_[i2] * f);
                    }
                }
            } else if (currentFactor == 4) {
                cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
                cascadeStages_[1].processStereo(stageBuf1_L_.data(), stageBuf1_R_.data(), numInFrames * 2, stageBuf2_L_, stageBuf2_R_);
                if (!is441Base) {
                    std::memcpy(tempOutL_.data(), stageBuf2_L_.data(), numOutFrames * sizeof(float));
                    std::memcpy(tempOutR_.data(), stageBuf2_R_.data(), numOutFrames * sizeof(float));
                } else {
                    double step = (double)(numInFrames * 4) / (double)numOutFrames;
                    for (size_t i = 0; i < numOutFrames; ++i) {
                        double p = i * step; int idx = (int)p; double f = p - idx;
                        int i1 = std::min((int)(numInFrames * 4) - 1, idx);
                        int i2 = std::min((int)(numInFrames * 4) - 1, idx + 1);
                        tempOutL_[i] = static_cast<float>(stageBuf2_L_[i1] * (1.0 - f) + stageBuf2_L_[i2] * f);
                        tempOutR_[i] = static_cast<float>(stageBuf2_R_[i1] * (1.0 - f) + stageBuf2_R_[i2] * f);
                    }
                }
            } else if (currentFactor == 8) {
                cascadeStages_[0].processStereo(tempInL_.data(), tempInR_.data(), numInFrames, stageBuf1_L_, stageBuf1_R_);
                cascadeStages_[1].processStereo(stageBuf1_L_.data(), stageBuf1_R_.data(), numInFrames * 2, stageBuf2_L_, stageBuf2_R_);
                cascadeStages_[2].processStereo(stageBuf2_L_.data(), stageBuf2_R_.data(), numInFrames * 4, stageBuf1_L_, stageBuf1_R_);
                if (!is441Base) {
                    std::memcpy(tempOutL_.data(), stageBuf1_L_.data(), numOutFrames * sizeof(float));
                    std::memcpy(tempOutR_.data(), stageBuf1_R_.data(), numOutFrames * sizeof(float));
                } else {
                    double step = (double)(numInFrames * 8) / (double)numOutFrames;
                    for (size_t i = 0; i < numOutFrames; ++i) {
                        double p = i * step; int idx = (int)p; double f = p - idx;
                        int i1 = std::min((int)(numInFrames * 8) - 1, idx);
                        int i2 = std::min((int)(numInFrames * 8) - 1, idx + 1);
                        tempOutL_[i] = static_cast<float>(stageBuf1_L_[i1] * (1.0 - f) + stageBuf1_L_[i2] * f);
                        tempOutR_[i] = static_cast<float>(stageBuf1_R_[i1] * (1.0 - f) + stageBuf1_R_[i2] * f);
                    }
                }
            }
        } else {
            size_t factorFrames = numInFrames * currentFactor;
            stageBuf1_L_.resize(factorFrames);
            stageBuf1_R_.resize(factorFrames);

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
                    stageBuf1_L_[outIdx] = sumL;
                    stageBuf1_R_[outIdx] = sumR;
                }

                historyWritePos_++;
                if (historyWritePos_ >= hLen) historyWritePos_ = 0;
            }

            if (!is441Base) {
                std::memcpy(tempOutL_.data(), stageBuf1_L_.data(), numOutFrames * sizeof(float));
                std::memcpy(tempOutR_.data(), stageBuf1_R_.data(), numOutFrames * sizeof(float));
            } else {
                double step = (double)factorFrames / (double)numOutFrames;
                for (size_t i = 0; i < numOutFrames; ++i) {
                    double p = i * step; int idx = (int)p; double f = p - idx;
                    int i1 = std::min((int)factorFrames - 1, idx);
                    int i2 = std::min((int)factorFrames - 1, idx + 1);
                    tempOutL_[i] = static_cast<float>(stageBuf1_L_[i1] * (1.0 - f) + stageBuf1_L_[i2] * f);
                    tempOutR_[i] = static_cast<float>(stageBuf1_R_[i1] * (1.0 - f) + stageBuf1_R_[i2] * f);
                }
            }
        }
    } else {
        if (is441Base) {
            double step = (double)numInFrames / (double)numOutFrames;
            for (size_t i = 0; i < numOutFrames; ++i) {
                double p = i * step; int idx = (int)p; double f = p - idx;
                int i1 = std::min((int)numInFrames - 1, idx);
                int i2 = std::min((int)numInFrames - 1, idx + 1);
                tempOutL_[i] = static_cast<float>(tempInL_[i1] * (1.0 - f) + tempInL_[i2] * f);
                tempOutR_[i] = static_cast<float>(tempInR_[i1] * (1.0 - f) + tempInR_[i2] * f);
            }
        } else {
            std::memcpy(tempOutL_.data(), tempInL_.data(), numInFrames * sizeof(float));
            std::memcpy(tempOutR_.data(), tempInR_.data(), numInFrames * sizeof(float));
        }
    }

    if (!isDirectSource_) {
        if (currentFactor >= 2) {
            if (transientMode_ != TransientMode::OFF) {
                transientRestorer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
            }
            if (freqMode_ != FreqMode::OFF) {
                freqEngine_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
            }
            if (isDynamicSbr_) {
                processDynamicSbr(tempOutL_.data(), tempOutR_.data(), numOutFrames);
            }
            if (isMsSpatial_) {
                processMsSpatial(tempOutL_.data(), tempOutR_.data(), numOutFrames);
            }
        }

        equalizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
        dcPhaseLinearizer_.processStereo(tempOutL_.data(), tempOutR_.data(), numOutFrames);
    }

    size_t curPos = specRingPos_.load(std::memory_order_relaxed);
    for (size_t i = 0; i < numInFrames; ++i) {
        size_t outIdx = std::min(i * currentFactor, numOutFrames - 1);
        specRingBuf_[curPos] = (tempOutL_[outIdx] + tempOutR_[outIdx]) * 0.5f;
        curPos = (curPos + 1) & 4095;
    }
    specRingPos_.store(curPos, std::memory_order_release);

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
                    shapedL += dL; shapedR += dR;
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

            if (!isDirectSource_) {
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
            }

            int32_t intL = static_cast<int32_t>(std::clamp(std::round(shapedL), -32768.0, 32767.0));
            int32_t intR = static_cast<int32_t>(std::clamp(std::round(shapedR), -32768.0, 32767.0));

            if (!isDirectSource_ && (ditherMode_ == DitherMode::HIGH_PASS_SHAPED || ditherMode_ == DitherMode::PSYCHOACOUSTIC)) {
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