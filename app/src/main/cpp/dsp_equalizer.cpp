#include "dsp_equalizer.h"
#include <algorithm>

constexpr float PI = 3.14159265358979323846f;
constexpr float DEFAULT_Q = 1.41421356f; // 1オクターブ帯域幅

void DspEqualizer::Biquad::update(float f0, float gainDb, float q, float fs) {
    if (std::abs(gainDb) < 0.05f || f0 >= fs * 0.48f) {
        b0 = 1.0f; b1 = 0.0f; b2 = 0.0f;
        a1 = 0.0f; a2 = 0.0f;
        isBypass = true;
        return;
    }

    isBypass = false;
    float A = std::pow(10.0f, gainDb / 40.0f);
    float w0 = 2.0f * PI * f0 / fs;
    float alpha = std::sin(w0) / (2.0f * q);
    float cosw = std::cos(w0);

    float b0_raw = 1.0f + alpha * A;
    float b1_raw = -2.0f * cosw;
    float b2_raw = 1.0f - alpha * A;
    float a0_raw = 1.0f + alpha / A;
    float a1_raw = -2.0f * cosw;
    float a2_raw = 1.0f - alpha / A;

    float inv_a0 = 1.0f / a0_raw;
    b0 = b0_raw * inv_a0;
    b1 = b1_raw * inv_a0;
    b2 = b2_raw * inv_a0;
    a1 = a1_raw * inv_a0;
    a2 = a2_raw * inv_a0;
}

void DspEqualizer::Biquad::resetState() {
    s1_L = 0.0f; s2_L = 0.0f;
    s1_R = 0.0f; s2_R = 0.0f;
}

DspEqualizer::DspEqualizer() {
    gainsDb_.fill(0.0f);
    setSampleRate(48000.0f);
}

void DspEqualizer::setSampleRate(float sampleRate) {
    sampleRate_ = std::max(8000.0f, sampleRate);
    for (int i = 0; i < NUM_BANDS; ++i) {
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], DEFAULT_Q, sampleRate_);
    }
}

void DspEqualizer::setBandGain(int band, float gainDb) {
    if (band < 0 || band >= NUM_BANDS) return;
    gainsDb_[band] = std::clamp(gainDb, -10.0f, 10.0f);
    filters_[band].update(FREQUENCIES[band], gainsDb_[band], DEFAULT_Q, sampleRate_);
}

void DspEqualizer::setAllGains(const float* gains) {
    if (!gains) return;
    for (int i = 0; i < NUM_BANDS; ++i) {
        gainsDb_[i] = std::clamp(gains[i], -10.0f, 10.0f);
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], DEFAULT_Q, sampleRate_);
    }
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
        float l = left[i];
        float r = right[i];
        for (int b = 0; b < NUM_BANDS; ++b) {
            filters_[b].process(l, r);
        }
        left[i] = l;
        right[i] = r;
    }
}
