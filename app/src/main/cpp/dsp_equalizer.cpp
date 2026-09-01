#include "dsp_equalizer.h"
#include <algorithm>

constexpr double PI = 3.14159265358979323846;
// ★ Walkman 1Z 最適化 Q 値 (帯域の重なりによる濁りを排除)
constexpr double OPTIMIZED_Q = 1.15;

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
    setSampleRate(48000.0);
}

void DspEqualizer::recalculateHeadroom() {
    float maxBoost = 0.0f;
    for (float g : gainsDb_) {
        if (g > maxBoost) maxBoost = g;
    }
    // ブースト量に応じて自動アッテネーションを計算（歪みをゼロ化）
    if (maxBoost > 0.05f) {
        headRoomGain_ = std::pow(10.0, -maxBoost / 20.0);
    } else {
        headRoomGain_ = 1.0;
    }
}

void DspEqualizer::setSampleRate(double sampleRate) {
    sampleRate_ = std::max(8000.0, sampleRate);
    for (int i = 0; i < NUM_BANDS; ++i) {
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], OPTIMIZED_Q, sampleRate_);
    }
    recalculateHeadroom();
}

void DspEqualizer::setBandGain(int band, float gainDb) {
    if (band < 0 || band >= NUM_BANDS) return;
    gainsDb_[band] = std::clamp(gainDb, -10.0f, 10.0f);
    filters_[band].update(FREQUENCIES[band], gainsDb_[band], OPTIMIZED_Q, sampleRate_);
    recalculateHeadroom();
}

void DspEqualizer::setAllGains(const float* gains) {
    if (!gains) return;
    for (int i = 0; i < NUM_BANDS; ++i) {
        gainsDb_[i] = std::clamp(gains[i], -10.0f, 10.0f);
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], OPTIMIZED_Q, sampleRate_);
    }
    recalculateHeadroom();
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
}

void DspEqualizer::processStereo(float* left, float* right, size_t numFrames) {
    if (!enabled_ || !left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        double l = static_cast<double>(left[i]);
        double r = static_cast<double>(right[i]);

        for (int b = 0; b < NUM_BANDS; ++b) {
            filters_[b].process(l, r);
        }

        // オートヘッドルーム補正 (クリップ防止)
        l *= headRoomGain_;
        r *= headRoomGain_;

        left[i] = static_cast<float>(l);
        right[i] = static_cast<float>(r);
    }
}
