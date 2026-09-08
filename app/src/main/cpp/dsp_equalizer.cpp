#include "dsp_equalizer.h"
#include <algorithm>

constexpr double PI = 3.14159265358979323846;

static inline double getBandQ(int band) {
    switch (band) {
        case 0: return 0.85;
        case 1: return 0.95;
        case 2: return 1.05;
        default: return 1.15;
    }
}

void DspEqualizer::Biquad64::update(double f0, double gainDb, double q, double fs) {
    if (std::abs(gainDb) < 0.02 || f0 >= fs * 0.48) {
        b0 = 1.0; b1 = 0.0; b2 = 0.0;
        a1 = 0.0; a2 = 0.0;
        isBypass = true;
        return;
    }

    isBypass = false;
    double A = std::pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * PI * f0 / fs;
    double alpha = std::sin(w0) / (2.0 * q);
    double cosw = std::cos(w0);

    double b0_raw = 1.0 + alpha * A;
    double b1_raw = -2.0 * cosw;
    double b2_raw = 1.0 - alpha * A;
    double a0_raw = 1.0 + alpha / A;
    double a1_raw = -2.0 * cosw;
    double a2_raw = 1.0 - alpha / A;

    double inv_a0 = 1.0 / a0_raw;
    b0 = b0_raw * inv_a0;
    b1 = b1_raw * inv_a0;
    b2 = b2_raw * inv_a0;
    a1 = a1_raw * inv_a0;
    a2 = a2_raw * inv_a0;
}

void DspEqualizer::Biquad64::resetState() {
    s1_L = 0.0; s2_L = 0.0;
    s1_R = 0.0; s2_R = 0.0;
}

DspEqualizer::DspEqualizer() {
    gainsDb_.fill(0.0f);
    delayBufL_.assign(MAX_LOOKAHEAD, 0.0);
    delayBufR_.assign(MAX_LOOKAHEAD, 0.0);
    delayGainBuf_.assign(MAX_LOOKAHEAD, 1.0);
    setSampleRate(48000.0);
}

void DspEqualizer::updateAdaptiveHeadroom() {
    float maxBoost = 0.0f;
    for (float g : gainsDb_) {
        if (g > maxBoost) maxBoost = g;
    }
    if (maxBoost > 0.5f) {
        double safeDb = maxBoost * 0.35;
        adaptiveHeadroomGain_ = std::pow(10.0, -safeDb / 20.0);
    } else {
        adaptiveHeadroomGain_ = 1.0;
    }
}

void DspEqualizer::setSampleRate(double sampleRate) {
    sampleRate_ = std::max(8000.0, sampleRate);
    for (int i = 0; i < NUM_BANDS; ++i) {
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], getBandQ(i), sampleRate_);
    }

    lookaheadFrames_ = std::min(static_cast<size_t>(sampleRate_ * 0.0035), MAX_LOOKAHEAD - 1);
    releaseCoeff_ = std::exp(-1.0 / (0.085 * sampleRate_));
    hpCoeff_ = std::exp(-2.0 * PI * 18.0 / sampleRate_);

    updateAdaptiveHeadroom();
    reset();
}

void DspEqualizer::setBandGain(int band, float gainDb) {
    if (band < 0 || band >= NUM_BANDS) return;
    gainsDb_[band] = std::clamp(gainDb, -10.0f, 10.0f);
    filters_[band].update(FREQUENCIES[band], gainsDb_[band], getBandQ(band), sampleRate_);
    updateAdaptiveHeadroom();
}

void DspEqualizer::setAllGains(const float* gains) {
    if (!gains) return;
    for (int i = 0; i < NUM_BANDS; ++i) {
        gainsDb_[i] = std::clamp(gains[i], -10.0f, 10.0f);
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], getBandQ(i), sampleRate_);
    }
    updateAdaptiveHeadroom();
}

void DspEqualizer::setEnabled(bool enabled) {
    enabled_ = enabled;
    if (!enabled) {
        reset();
    }
}

void DspEqualizer::reset() {
    for (int i = 0; i < NUM_BANDS; ++i) {
        filters_[i].resetState();
    }
    std::fill(delayBufL_.begin(), delayBufL_.end(), 0.0);
    std::fill(delayBufR_.begin(), delayBufR_.end(), 0.0);
    std::fill(delayGainBuf_.begin(), delayGainBuf_.end(), 1.0);
    bufWritePos_ = 0;
    bufReadPos_ = 0;
    isPrimed_ = false;
    peakEnv_ = 0.0;
    hp_xL_ = 0.0; hp_yL_ = 0.0;
    hp_xR_ = 0.0; hp_yR_ = 0.0;
}

void DspEqualizer::processStereo(float* left, float* right, size_t numFrames) {
    if (!enabled_ || !left || !right || numFrames == 0) return;

    const size_t la = lookaheadFrames_;
    const size_t cap = MAX_LOOKAHEAD;
    const double hrGain = adaptiveHeadroomGain_;
    const double threshold = 0.985;

    for (size_t i = 0; i < numFrames; ++i) {
        double l = static_cast<double>(left[i]);
        double r = static_cast<double>(right[i]);

        // 1. サブソニック 18Hz ハイパス
        double yL = l - hp_xL_ + hpCoeff_ * hp_yL_;
        hp_xL_ = l; hp_yL_ = yL; l = yL;

        double yR = r - hp_xR_ + hpCoeff_ * hp_yR_;
        hp_xR_ = r; hp_yR_ = yR; r = yR;

        // 2. 10-Band 倍精度 IIR
        for (int b = 0; b < NUM_BANDS; ++b) {
            filters_[b].process(l, r);
        }

        l *= hrGain;
        r *= hrGain;

        // ★ 真の先読みリミッター: 入力時点 (未来) のピークから前もって減衰カーブを算出
        double instantPeak = std::max(std::abs(l), std::abs(r));
        if (instantPeak > peakEnv_) {
            peakEnv_ = instantPeak;
        } else {
            peakEnv_ = instantPeak + releaseCoeff_ * (peakEnv_ - instantPeak);
        }

        double targetGain = 1.0;
        if (peakEnv_ > threshold) {
            targetGain = threshold / peakEnv_;
        }

        delayBufL_[bufWritePos_] = l;
        delayBufR_[bufWritePos_] = r;
        delayGainBuf_[bufWritePos_] = targetGain;
        bufWritePos_ = (bufWritePos_ + 1) % cap;

        if (!isPrimed_) {
            if (bufWritePos_ >= la) isPrimed_ = true;
            left[i] = 0.0f;
            right[i] = 0.0f;
            continue;
        }

        // 遅延バッファから出てくるサンプルに対し、先読み計算されたリミッターゲインを適用
        double delayedL = delayBufL_[bufReadPos_];
        double delayedR = delayBufR_[bufReadPos_];
        double applyGain = delayGainBuf_[bufReadPos_];
        bufReadPos_ = (bufReadPos_ + 1) % cap;

        delayedL *= applyGain;
        delayedR *= applyGain;

        left[i] = static_cast<float>(std::clamp(delayedL, -1.0, 1.0));
        right[i] = static_cast<float>(std::clamp(delayedR, -1.0, 1.0));
    }
}