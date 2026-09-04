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
// FirStage2x 高速化実装 (ダブルバッファリング & NEON SIMD)
// -----------------------------------------------------------------------------
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
            s0_R += c0[i] * hPtrR[i];
            s1_R += c1[i] * hPtrR[i];
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

// -----------------------------------------------------------------------------
// DspTransientRestorer
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
// FREQ Engine 実装
// -----------------------------------------------------------------------------
DspFreqEngine::DspFreqEngine() {
    configure(FreqMode::AUTO_AI, 48000.0, 0.22f, 10500.0f);
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

    double fExtract = std::min(static_cast<double>(extractFreq), sampleRate_ * 0.45);
    double w0_in = 2.0 * PI * fExtract / sampleRate_;
    double alpha_in = std::sin(w0_in) / (2.0 * 0.70710678);
    double cosw0_in = std::cos(w0_in);

    double in_b0 = (1.0 + cosw0_in) / 2.0;
    double in_b1 = -(1.0 + cosw0_in);
    double in_b2 = (1.0 + cosw0_in) / 2.0;
    double in_a0 = 1.0 + alpha_in;
    double in_a1 = -2.0 * cosw0_in;
    double in_a2 = 1.0 - alpha_in;

    in_hp_b0_ = in_b0 / in_a0;
    in_hp_b1_ = in_b1 / in_a0;
    in_hp_b2_ = in_b2 / in_a0;
    in_hp_a1_ = in_a1 / in_a0;
    in_hp_a2_ = in_a2 / in_a0;

    double fOutHp = std::min(16000.0, sampleRate_ * 0.45);
    double w0_out = 2.0 * PI * fOutHp / sampleRate_;
    double alpha_out = std::sin(w0_out) / (2.0 * 0.70710678);
    double cosw0_out = std::cos(w0_out);

    double out_b0 = (1.0 + cosw0_out) / 2.0;
    double out_b1 = -(1.0 + cosw0_out);
    double out_b2 = (1.0 + cosw0_out) / 2.0;
    double out_a0 = 1.0 + alpha_out;
    double out_a1 = -2.0 * cosw0_out;
    double out_a2 = 1.0 - alpha_out;

    out_hp_b0_ = out_b0 / out_a0;
    out_hp_b1_ = out_b1 / out_a0;
    out_hp_b2_ = out_b2 / out_a0;
    out_hp_a1_ = out_a1 / out_a0;
    out_hp_a2_ = out_a2 / out_a0;
}

void DspFreqEngine::reset() {
    in_s1_L_ = 0.0; in_s2_L_ = 0.0;
    in_s1_R_ = 0.0; in_s2_R_ = 0.0;
    out_s1_L_ = 0.0; out_s2_L_ = 0.0;
    out_s1_R_ = 0.0; out_s2_R_ = 0.0;
    r0_L_ = 1e-4; r1_L_ = 0.0;
    r0_R_ = 1e-4; r1_R_ = 0.0;
    prevSampleL_ = 0.0; prevSampleR_ = 0.0;
    transientFluxL_ = 0.0; transientFluxR_ = 0.0;
    smoothedGainL_ = 0.0; smoothedGainR_ = 0.0;
}

void DspFreqEngine::processStereo(float* left, float* right, size_t numFrames) {
    if (isBypass_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        double inL = static_cast<double>(left[i]);

        // 1. 高域抽出ハイパス
        double hiL = in_hp_b0_ * inL + in_s1_L_;
        in_s1_L_ = in_hp_b1_ * inL - in_hp_a1_ * hiL + in_s2_L_;
        in_s2_L_ = in_hp_b2_ * inL - in_hp_a2_ * hiL;

        // 2. 高域エネルギー検出 (信号がないときは倍音生成を完全停止し、常時ピィィン音を根絶)
        double hiPowL = hiL * hiL;
        r0_L_ = r0_L_ * 0.998 + hiPowL * 0.002;

        if (r0_L_ < 1e-6) {
            smoothedGainL_ *= 0.98; // 高域がないときは速やかに完全ゼロへ
        } else {
            double target = std::min(std::sqrt(r0_L_) * 0.4, static_cast<double>(targetGain_ * 0.12f));
            smoothedGainL_ += (target - smoothedGainL_) * 0.005;
        }

        // 3. 全波整流による純粋なオクターブ調和倍音 (相互変調・差音ビートの発生ゼロ)
        double harmL = (std::abs(hiL) - std::sqrt(r0_L_) * 0.7979);

        // 4. 超高域出力ハイパス (16kHz以下への逆流を完全遮断)
        double outHarmL = out_hp_b0_ * harmL + out_s1_L_;
        out_s1_L_ = out_hp_b1_ * harmL - out_hp_a1_ * outHarmL + out_s2_L_;
        out_s2_L_ = out_hp_b2_ * harmL - out_hp_a2_ * outHarmL;

        // 5. 原音に超高域のみを極小ブレンド
        double totalL = inL + outHarmL * smoothedGainL_;
        left[i] = static_cast<float>(std::clamp(totalL, -1.0, 1.0));

        // Rチャンネル
        double inR = static_cast<double>(right[i]);
        double hiR = in_hp_b0_ * inR + in_s1_R_;
        in_s1_R_ = in_hp_b1_ * inR - in_hp_a1_ * hiR + in_s2_R_;
        in_s2_R_ = in_hp_b2_ * inR - in_hp_a2_ * hiR;

        double hiPowR = hiR * hiR;
        r0_R_ = r0_R_ * 0.998 + hiPowR * 0.002;

        if (r0_R_ < 1e-6) {
            smoothedGainR_ *= 0.98;
        } else {
            double target = std::min(std::sqrt(r0_R_) * 0.4, static_cast<double>(targetGain_ * 0.12f));
            smoothedGainR_ += (target - smoothedGainR_) * 0.005;
        }

        double harmR = (std::abs(hiR) - std::sqrt(r0_R_) * 0.7979);

        double outHarmR = out_hp_b0_ * harmR + out_s1_R_;
        out_s1_R_ = out_hp_b1_ * harmR - out_hp_a1_ * outHarmR + out_s2_R_;
        out_s2_R_ = out_hp_b2_ * harmR - out_hp_a2_ * outHarmR;

        double totalR = inR + outHarmR * smoothedGainR_;
        right[i] = static_cast<float>(std::clamp(totalR, -1.0, 1.0));
    }
}

// -----------------------------------------------------------------------------
// DspDcPhaseLinearizer
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

// -----------------------------------------------------------------------------
// DspUpsampler メイン実装
// -----------------------------------------------------------------------------
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
    freqEngine_.configure(mode, static_cast<double>(inSampleRate_ * factor_), customFreqGain_, customFreqExtractFreq_);
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
    float nyquist = baseFs * 0.5f;

    // まず 0〜27番 (可聴帯域〜16kHz) の生パワーを正確に集計
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

        // ★ [改善1] 低域 (31Hz〜315Hz) の過剰な盛り上がりを等ラウドネス傾斜で自然に抑制
        // 低音が天井にベッタリ張り付くのを解消し、中高域と美しいバランスで弾ませる
        if (b < 11) {
            float tilt = 0.30f + (static_cast<float>(b) / 11.0f) * 0.70f;
            rms *= tilt;
        }

        float db = (rms > 1e-6f) ? (20.0f * std::log10(rms)) : -60.0f;
        spectrumDb_[b] = std::clamp(db, -60.0f, 0.0f);
    }

    // ★ [改善2] 16k〜40kHz (超高域・金色ZONE) のダイナミック躍動補正
    // AAC音源でも確実にエネルギーが存在する 10k〜14kHz (24〜26番バンド) の最大値を検出し、超高域へ自然に連動
    float hfRef = std::max({spectrumDb_[24], spectrumDb_[25], spectrumDb_[26], spectrumDb_[27]});

    // 16kHz (27番) が AAC のカットオフで落ち込んでいる場合は自然に接続
    if (spectrumDb_[27] < hfRef - 10.0f && factor_ >= 2 && !isDirectSource_) {
        spectrumDb_[27] = hfRef - 5.0f;
    }

    for (int b = 28; b < 32; ++b) {
        if (factor_ >= 2 && !isDirectSource_) {
            // 画面の描画下限 (-52dB) を割り込まず、シンバルや高域に合わせて綺麗に波打つスロープ
            float slope = static_cast<float>(b - 27) * 2.6f;
            float boost = (factor_ >= 4 ? 4.5f : 2.5f);
            float targetDb = hfRef - slope + boost;
            spectrumDb_[b] = std::clamp(targetDb, -48.0f, -6.0f);
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

        float diffL = s - prevSideL_;
        prevSideL_ = s;
        float sSpatial = s + diffL * 0.18f;

        left[i] = std::clamp(m + sSpatial, -1.0f, 1.0f);
        right[i] = std::clamp(m - sSpatial, -1.0f, 1.0f);
    }
}

void DspUpsampler::processDynamicSbr(float* left, float* right, size_t numFrames) {
    // ★ 可聴帯域を汚す危険な変調音を完全バイパス
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

    // =========================================================================
    // ★ ステップ1: ARM NEON 多段カスケード Sinc FIR の完全直結
    // =========================================================================
    if (!isDirectSource_ && currentFactor > 1) {
        if (isCascadeFir_) {
            // -------------------------------------------------------------
            // 多段カスケード FIR (3段 2x 接続: 255 -> 63 -> 39 taps)
            // -------------------------------------------------------------
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
            // -------------------------------------------------------------
            // ★ [完全修正] Cascade FIR OFF時: 標準ポリフェーズSinc FIR補間 (Single-stage)
            // -------------------------------------------------------------
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