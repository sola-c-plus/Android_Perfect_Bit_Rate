#include "dsp_fir_stage.h"
#include <algorithm>
#include <cstring>

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

    for (size_t i = 0; i < numTaps_; ++i) {
        double offset = static_cast<double>(i) - center;
        double sincVal = (std::abs(offset) < 1e-12) ? 1.0 : (std::sin(DSP_PI * 2.0 * normalizedCutoff * offset) / (DSP_PI * 2.0 * normalizedCutoff * offset));
        double rel = offset / center;
        double arg = std::max(0.0, 1.0 - rel * rel);
        double window = besselI0(beta * std::sqrt(arg)) / i0Beta;
        design[i] = 2.0 * normalizedCutoff * sincVal * window;
    }

    double origSum = 0.0;
    for (double d : design) origSum += d;

    if (filterType == FirFilterType::MINIMUM_PHASE_SHARP || filterType == FirFilterType::MINIMUM_PHASE_SLOW) {
        convertToMinimumPhase(design, static_cast<int>(numTaps_));
    }

    double sum = 0.0;
    for (double d : design) sum += d;

    // ★ 爆音クリップ防止: DC総和が小さすぎる場合は変換前のゲインを採用し、scale に安全リミッター (0.5〜4.0) を適用
    double effectiveSum = (std::abs(sum) > 0.1) ? sum : (std::abs(origSum) > 0.1 ? origSum : 1.0);
    double scale = 2.0 / effectiveSum;
    scale = std::clamp(scale, 0.5, 4.0);

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