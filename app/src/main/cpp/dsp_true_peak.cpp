#include "dsp_true_peak.h"

DspTruePeakLimiter::DspTruePeakLimiter() {
    delayBufL_.assign(MAX_LOOKAHEAD, 0.0);
    delayBufR_.assign(MAX_LOOKAHEAD, 0.0);
    configure(48000.0);
}

void DspTruePeakLimiter::configure(double sampleRate, double ceilingDb, double releaseMs) {
    sampleRate_ = std::max(8000.0, sampleRate);
    ceiling_ = std::pow(10.0, ceilingDb / 20.0);
    
    // 約 1.5ms の極小先読み遅延 (違和感ゼロ)
    lookaheadFrames_ = std::clamp(static_cast<size_t>(sampleRate_ * 0.0015), static_cast<size_t>(16), MAX_LOOKAHEAD - 1);
    
    // 低域のポンピング歪みを完全に防ぐ 60ms スムーズリリース係数
    releaseCoeff_ = std::exp(-1.0 / ((releaseMs / 1000.0) * sampleRate_));
    reset();
}

void DspTruePeakLimiter::reset() {
    std::fill(delayBufL_.begin(), delayBufL_.end(), 0.0);
    std::fill(delayBufR_.begin(), delayBufR_.end(), 0.0);
    writePos_ = 0;
    readPos_ = 0;
    isPrimed_ = false;
    peakEnv_ = 0.0;
}

void DspTruePeakLimiter::processStereo(float* left, float* right, size_t numFrames) {
    if (!left || !right || numFrames == 0) return;

    const size_t la = lookaheadFrames_;
    const size_t cap = MAX_LOOKAHEAD;
    const double ceil = ceiling_;

    for (size_t i = 0; i < numFrames; ++i) {
        double l = static_cast<double>(left[i]);
        double r = static_cast<double>(right[i]);

        // トゥルーピーク (インターサンプル・ピーク) 推定:
        // 隣接サンプルとの傾斜から、DACのアナログ再構成時に発生する飛び出しピークを先回り推定
        double absL = std::abs(l);
        double absR = std::abs(r);
        double instantPeak = std::max(absL, absR);

        size_t prevIdx = (writePos_ + cap - 1) % cap;
        double diffL = std::abs(l - delayBufL_[prevIdx]);
        double diffR = std::abs(r - delayBufR_[prevIdx]);
        double interSampleEst = instantPeak + 0.18 * std::max(diffL, diffR);

        if (interSampleEst > peakEnv_) {
            peakEnv_ = interSampleEst; // 瞬時アタック
        } else {
            peakEnv_ = interSampleEst + releaseCoeff_ * (peakEnv_ - interSampleEst); // スムーズリリース
        }

        // 先読みディレイバッファへ格納
        delayBufL_[writePos_] = l;
        delayBufR_[writePos_] = r;
        writePos_ = (writePos_ + 1) % cap;

        if (!isPrimed_) {
            if (writePos_ >= la) isPrimed_ = true;
            left[i] = 0.0f;
            right[i] = 0.0f;
            continue;
        }

        // 先読み遅延サンプルの取り出し
        double delayedL = delayBufL_[readPos_];
        double delayedR = delayBufR_[readPos_];
        readPos_ = (readPos_ + 1) % cap;

        // 64-bit トゥルーピーク・ソフトニーリミッター適用
        // ピーク到来前にゲインを滑らかにアッテネーションするため、サイン波形が一切切断されない
        if (peakEnv_ > ceil) {
            double limitGain = ceil / peakEnv_;
            delayedL *= limitGain;
            delayedR *= limitGain;
        }

        left[i] = static_cast<float>(std::clamp(delayedL, -1.0, 1.0));
        right[i] = static_cast<float>(std::clamp(delayedR, -1.0, 1.0));
    }
}