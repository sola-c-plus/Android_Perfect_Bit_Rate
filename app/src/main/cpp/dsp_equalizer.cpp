#include "dsp_equalizer.h"
#include <algorithm>

constexpr double PI = 3.14159265358979323846;
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
    delayBufL_.assign(MAX_LOOKAHEAD, 0.0);
    delayBufR_.assign(MAX_LOOKAHEAD, 0.0);
    setSampleRate(48000.0);
}

void DspEqualizer::setSampleRate(double sampleRate) {
    sampleRate_ = std::max(8000.0, sampleRate);
    for (int i = 0; i < NUM_BANDS; ++i) {
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], OPTIMIZED_Q, sampleRate_);
    }

    lookaheadFrames_ = std::min(static_cast<size_t>(sampleRate_ * 0.0035), MAX_LOOKAHEAD - 1);
    // 先読みがあるため、アタックは極めて滑らかな 3.5ms に設定可能 (AM変調歪み・ざらつきを排除)
    attackCoeff_ = std::exp(-1.0 / (0.0035 * sampleRate_));
    // リリースは低域のポンピングを防ぐ 80ms
    releaseCoeff_ = std::exp(-1.0 / (0.080 * sampleRate_));

    reset();
}

void DspEqualizer::setBandGain(int band, float gainDb) {
    if (band < 0 || band >= NUM_BANDS) return;
    gainsDb_[band] = std::clamp(gainDb, -10.0f, 10.0f);
    filters_[band].update(FREQUENCIES[band], gainsDb_[band], OPTIMIZED_Q, sampleRate_);
}

void DspEqualizer::setAllGains(const float* gains) {
    if (!gains) return;
    for (int i = 0; i < NUM_BANDS; ++i) {
        gainsDb_[i] = std::clamp(gains[i], -10.0f, 10.0f);
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], OPTIMIZED_Q, sampleRate_);
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
    std::fill(delayBufL_.begin(), delayBufL_.end(), 0.0);
    std::fill(delayBufR_.begin(), delayBufR_.end(), 0.0);
    bufWritePos_ = 0;
    bufReadPos_ = 0;
    isPrimed_ = false;
    env_ = 0.0;
    currentGain_ = 1.0;
}

void DspEqualizer::processStereo(float* left, float* right, size_t numFrames) {
    if (!enabled_ || !left || !right || numFrames == 0) return;

    const size_t la = lookaheadFrames_;
    const size_t cap = MAX_LOOKAHEAD;

    for (size_t i = 0; i < numFrames; ++i) {
        double l = static_cast<double>(left[i]);
        double r = static_cast<double>(right[i]);

        // 1. 10-Band 倍精度 IIR フィルター処理
        for (int b = 0; b < NUM_BANDS; ++b) {
            filters_[b].process(l, r);
        }

        // 2. 先行ピーク検知 (未来の波形を事前に監視)
        double futurePeak = std::max(std::abs(l), std::abs(r));
        if (futurePeak > env_) {
            env_ = futurePeak + attackCoeff_ * (env_ - futurePeak);
        } else {
            env_ = futurePeak + releaseCoeff_ * (env_ - futurePeak);
        }

        // 3. ルックアヘッド遅延バッファへの書き込み
        delayBufL_[bufWritePos_] = l;
        delayBufR_[bufWritePos_] = r;
        bufWritePos_ = (bufWritePos_ + 1) % cap;

        if (!isPrimed_) {
            if (bufWritePos_ >= la) isPrimed_ = true;
            left[i] = 0.0f;
            right[i] = 0.0f;
            continue;
        }

        // 4. ピークが到達する直前のサンプルを読み出す
        double delayedL = delayBufL_[bufReadPos_];
        double delayedR = delayBufR_[bufReadPos_];
        bufReadPos_ = (bufReadPos_ + 1) % cap;

        // 5. 目標ゲインの計算 (0.95 以下の通常音量は完全 1.0倍リニア)
        double targetGain = 1.0;
        if (env_ > 0.95) {
            targetGain = 0.95 / env_;
        }

        // ゲイン変化自体のスムージング (クリックノイズ・相互変調歪みの根絶)
        if (targetGain < currentGain_) {
            currentGain_ += (targetGain - currentGain_) * 0.15;
        } else {
            currentGain_ += (targetGain - currentGain_) * 0.005;
        }

        delayedL *= currentGain_;
        delayedR *= currentGain_;

        // 6. 3次エルミートスプラインによるソフトリミット (角折れによる方形波歪みを遮断)
        delayedL = hermiteSmooth(delayedL);
        delayedR = hermiteSmooth(delayedR);

        left[i] = static_cast<float>(delayedL);
        right[i] = static_cast<float>(delayedR);
    }
}