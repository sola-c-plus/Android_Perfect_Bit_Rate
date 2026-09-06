#include "dsp_equalizer.h"
#include <algorithm>

constexpr double PI = 3.14159265358979323846;

// ★ Walkman 1Z 最適化 Q 値 (低音バンドの重なりによる異常ゲイン爆発を抑制)
static inline double getBandQ(int band) {
    switch (band) {
        case 0: return 0.85; // 31.25Hz: タイトでどこまでも深く沈む低域
        case 1: return 0.95; // 62.5Hz:  パンチと明瞭感
        case 2: return 1.05; // 125Hz:   ベースラインの輪郭
        default: return 1.15; // 中高域
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
    setSampleRate(48000.0);
}

void DspEqualizer::setSampleRate(double sampleRate) {
    sampleRate_ = std::max(8000.0, sampleRate);
    for (int i = 0; i < NUM_BANDS; ++i) {
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], getBandQ(i), sampleRate_);
    }

    lookaheadFrames_ = std::min(static_cast<size_t>(sampleRate_ * 0.0040), MAX_LOOKAHEAD - 1);
    // 自然にゲインが復帰するスムーズリリース (85ms)
    releaseCoeff_ = std::exp(-1.0 / (0.085 * sampleRate_));
    // 18Hz ハイパス係数
    hpCoeff_ = std::exp(-2.0 * PI * 18.0 / sampleRate_);

    reset();
}

void DspEqualizer::setBandGain(int band, float gainDb) {
    if (band < 0 || band >= NUM_BANDS) return;
    gainsDb_[band] = std::clamp(gainDb, -10.0f, 10.0f);
    filters_[band].update(FREQUENCIES[band], gainsDb_[band], getBandQ(band), sampleRate_);
}

void DspEqualizer::setAllGains(const float* gains) {
    if (!gains) return;
    for (int i = 0; i < NUM_BANDS; ++i) {
        gainsDb_[i] = std::clamp(gains[i], -10.0f, 10.0f);
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], getBandQ(i), sampleRate_);
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
    peakEnv_ = 0.0;
    gain_ = 1.0;
    hp_xL_ = 0.0; hp_yL_ = 0.0;
    hp_xR_ = 0.0; hp_yR_ = 0.0;
}

void DspEqualizer::processStereo(float* left, float* right, size_t numFrames) {
    if (!enabled_ || !left || !right || numFrames == 0) return;

    const size_t la = lookaheadFrames_;
    const size_t cap = MAX_LOOKAHEAD;

    for (size_t i = 0; i < numFrames; ++i) {
        double l = static_cast<double>(left[i]);
        double r = static_cast<double>(right[i]);

        // 1. サブソニック 18Hz ハイパス (低音のヘッドルームを確保)
        double yL = l - hp_xL_ + hpCoeff_ * hp_yL_;
        hp_xL_ = l; hp_yL_ = yL; l = yL;

        double yR = r - hp_xR_ + hpCoeff_ * hp_yR_;
        hp_xR_ = r; hp_yR_ = yR; r = yR;

        // 2. 10-Band 倍精度 IIR 処理
        for (int b = 0; b < NUM_BANDS; ++b) {
            filters_[b].process(l, r);
        }

        // 3. 全帯域ピーク検知 (低音を逃がさず 100% 確実にキャッチ)
        double instantPeak = std::max(std::abs(l), std::abs(r));
        if (instantPeak > peakEnv_) {
            peakEnv_ = instantPeak; // ピーク検知は瞬時 (アタック遅延ゼロ)
        } else {
            peakEnv_ = instantPeak + releaseCoeff_ * (peakEnv_ - instantPeak);
        }

        // 4. 先読み遅延バッファへ格納
        delayBufL_[bufWritePos_] = l;
        delayBufR_[bufWritePos_] = r;
        bufWritePos_ = (bufWritePos_ + 1) % cap;

        if (!isPrimed_) {
            if (bufWritePos_ >= la) isPrimed_ = true;
            left[i] = 0.0f;
            right[i] = 0.0f;
            continue;
        }

        // 5. 先読み完了サンプルの読み出し
        double delayedL = delayBufL_[bufReadPos_];
        double delayedR = delayBufR_[bufReadPos_];
        bufReadPos_ = (bufReadPos_ + 1) % cap;

        // 6. ブリックウォール目標ゲインの計算 (0.988 を絶対上限とし、1サンプルも clamp に衝突させない)
        double targetGain = 1.0;
        if (peakEnv_ > 0.988) {
            targetGain = 0.988 / peakEnv_;
        }

        // 先読みがあるため、ゲイン減衰はピーク到達前に滑らかに完了
        if (targetGain < gain_) {
            gain_ += (targetGain - gain_) * 0.35;
        } else {
            gain_ += (targetGain - gain_) * 0.003;
        }

        delayedL *= gain_;
        delayedR *= gain_;

        // 7. 出力 (リミッターが完璧に防ぐため clamp は作動しない)
        left[i] = static_cast<float>(std::clamp(delayedL, -1.0, 1.0));
        right[i] = static_cast<float>(std::clamp(delayedR, -1.0, 1.0));
    }
}