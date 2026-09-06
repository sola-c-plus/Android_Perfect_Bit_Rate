#include "dsp_equalizer.h"
#include <algorithm>

constexpr double PI = 3.14159265358979323846;

// ★ Walkman 1Z 最適化 Q 値 (低音の過剰な位相回転と濁りを排除する個別チューニング)
static inline double getOptimizedQ(int bandIndex) {
    switch (bandIndex) {
        case 0: return 0.90; // 31.25Hz: タイトで沈み込む重低音
        case 1: return 1.00; // 62.5Hz:  パンチと明瞭感
        case 2: return 1.10; // 125Hz:   ベースラインの分離感
        default: return 1.15; // 中高域:  干渉を排除する最適値
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
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], getOptimizedQ(i), sampleRate_);
    }

    lookaheadFrames_ = std::min(static_cast<size_t>(sampleRate_ * 0.0030), MAX_LOOKAHEAD - 1);
    attackCoeff_ = std::exp(-1.0 / (0.0040 * sampleRate_));
    releaseCoeff_ = std::exp(-1.0 / (0.100 * sampleRate_)); // 100ms スムーズリリース

    // 15Hz DC ブロック係数
    dcCoeff_ = std::exp(-2.0 * PI * 15.0 / sampleRate_);
    // 110Hz サイドチェイン HPF 係数 (低音そのものでリミッターが暴れるのを完全防御)
    scCoeff_ = std::exp(-2.0 * PI * 110.0 / sampleRate_);

    reset();
}

void DspEqualizer::setBandGain(int band, float gainDb) {
    if (band < 0 || band >= NUM_BANDS) return;
    gainsDb_[band] = std::clamp(gainDb, -10.0f, 10.0f);
    filters_[band].update(FREQUENCIES[band], gainsDb_[band], getOptimizedQ(band), sampleRate_);
}

void DspEqualizer::setAllGains(const float* gains) {
    if (!gains) return;
    for (int i = 0; i < NUM_BANDS; ++i) {
        gainsDb_[i] = std::clamp(gains[i], -10.0f, 10.0f);
        filters_[i].update(FREQUENCIES[i], gainsDb_[i], getOptimizedQ(i), sampleRate_);
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
    dcBlock_xL_ = 0.0; dcBlock_yL_ = 0.0;
    dcBlock_xR_ = 0.0; dcBlock_yR_ = 0.0;
    scHp_x_ = 0.0; scHp_y_ = 0.0;
}

void DspEqualizer::processStereo(float* left, float* right, size_t numFrames) {
    if (!enabled_ || !left || !right || numFrames == 0) return;

    const size_t la = lookaheadFrames_;
    const size_t cap = MAX_LOOKAHEAD;

    for (size_t i = 0; i < numFrames; ++i) {
        double l = static_cast<double>(left[i]);
        double r = static_cast<double>(right[i]);

        // 1. サブソニック DC ブロック (15Hz以下の無駄なエネルギーを除去し、ヘッドルームを拡大)
        double yL = l - dcBlock_xL_ + dcCoeff_ * dcBlock_yL_;
        dcBlock_xL_ = l; dcBlock_yL_ = yL; l = yL;

        double yR = r - dcBlock_xR_ + dcCoeff_ * dcBlock_yR_;
        dcBlock_xR_ = r; dcBlock_yR_ = yR; r = yR;

        // 2. 10-Band 倍精度 IIR 処理 (直列展開)
        for (int b = 0; b < NUM_BANDS; ++b) {
            filters_[b].process(l, r);
        }

        // 3. サイドチェイン HPF (低域成分を逃がして検知し、低音が潰れるポンピングを排除)
        double monoRaw = std::max(std::abs(l), std::abs(r));
        double scY = monoRaw - scHp_x_ + scCoeff_ * scHp_y_;
        scHp_x_ = monoRaw; scHp_y_ = scY;

        // 総合ピーク評価 (低音の過大突き抜けも 25% の重みで穏やかに監視)
        double detectorSignal = std::abs(scY) * 0.75 + monoRaw * 0.25;

        if (detectorSignal > env_) {
            env_ = detectorSignal + attackCoeff_ * (env_ - detectorSignal);
        } else {
            env_ = detectorSignal + releaseCoeff_ * (env_ - detectorSignal);
        }

        // 4. ルックアヘッド遅延バッファへの書き込み
        delayBufL_[bufWritePos_] = l;
        delayBufR_[bufWritePos_] = r;
        bufWritePos_ = (bufWritePos_ + 1) % cap;

        if (!isPrimed_) {
            if (bufWritePos_ >= la) isPrimed_ = true;
            left[i] = 0.0f;
            right[i] = 0.0f;
            continue;
        }

        // 5. 先読み遅延サンプルの取り出し
        double delayedL = delayBufL_[bufReadPos_];
        double delayedR = delayBufR_[bufReadPos_];
        bufReadPos_ = (bufReadPos_ + 1) % cap;

        // 6. 目標ゲインの算出 (真の 0.999 フルスケールまで完全リニア無加工)
        double targetGain = 1.0;
        if (env_ > 0.999) {
            targetGain = 0.999 / env_;
        }

        if (targetGain < currentGain_) {
            currentGain_ += (targetGain - currentGain_) * 0.20;
        } else {
            currentGain_ += (targetGain - currentGain_) * 0.002;
        }

        delayedL *= currentGain_;
        delayedR *= currentGain_;

        // 7. 波形の頭を丸めて潰す処理は完全撤廃し、自然な音圧のまま DAC へ直結
        left[i] = static_cast<float>(std::clamp(delayedL, -1.0, 1.0));
        right[i] = static_cast<float>(std::clamp(delayedR, -1.0, 1.0));
    }
}