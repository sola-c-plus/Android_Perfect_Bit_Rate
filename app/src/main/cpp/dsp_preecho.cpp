#include "dsp_preecho.h"
#include <algorithm>

DspAntiPreecho::DspAntiPreecho() {
    configure(48000.0);
}

void DspAntiPreecho::configure(double sampleRate) {
    sampleRate_ = std::max(8000.0, sampleRate);
    lookaheadFrames_ = static_cast<size_t>(sampleRate_ * 0.0035);
    ringBufL_.assign(lookaheadFrames_ * 4, 0.0f);
    ringBufR_.assign(lookaheadFrames_ * 4, 0.0f);
    reset();
}

void DspAntiPreecho::reset() {
    std::fill(ringBufL_.begin(), ringBufL_.end(), 0.0f);
    std::fill(ringBufR_.begin(), ringBufR_.end(), 0.0f);
    writePos_ = 0;
    readPos_ = 0;
    isBufferPrimed_ = false;
}

void DspAntiPreecho::processStereo(float* left, float* right, size_t numFrames) {
    if (!left || !right || numFrames == 0 || ringBufL_.empty()) return;

    const size_t cap = ringBufL_.size();
    const size_t la = lookaheadFrames_;

    for (size_t i = 0; i < numFrames; ++i) {
        float inL = left[i];
        float inR = right[i];

        ringBufL_[writePos_] = inL;
        ringBufR_[writePos_] = inR;

        if (!isBufferPrimed_) {
            if (writePos_ >= la) isBufferPrimed_ = true;
            left[i] = 0.0f;
            right[i] = 0.0f;
            writePos_ = (writePos_ + 1) % cap;
            continue;
        }

        float outL = ringBufL_[readPos_];
        float outR = ringBufR_[readPos_];

        // ★ 単一サンプルの穴あきアッテネートを撤廃し、トランスペアレントな波形連続性を維持
        left[i] = outL;
        right[i] = outR;

        writePos_ = (writePos_ + 1) % cap;
        readPos_  = (readPos_ + 1) % cap;
    }
}

void DspBitContinuity::reset() {
    prevL_ = 0.0f; prev2L_ = 0.0f;
    prevR_ = 0.0f; prev2R_ = 0.0f;
}

void DspBitContinuity::processStereo(float* left, float* right, size_t numFrames) {
    if (!left || !right || numFrames == 0) return;

    for (size_t i = 0; i < numFrames; ++i) {
        float curL = left[i];
        float curR = right[i];

        // ★ 閾値を -60dBFS 以下に引き下げ、ボーカルや楽器の高域倍音のスルーレート破壊を防止
        float absL = std::abs(curL);
        if (absL > 0.00005f && absL < 0.001f) {
            float d1 = curL - prevL_;
            float d2 = prevL_ - prev2L_;
            if (std::abs(d1 - d2) > 0.0005f) {
                curL = prevL_ + (d1 + d2) * 0.45f;
            }
        }
        prev2L_ = prevL_;
        prevL_ = curL;
        left[i] = curL;

        float absR = std::abs(curR);
        if (absR > 0.00005f && absR < 0.001f) {
            float d1 = curR - prevR_;
            float d2 = prevR_ - prev2R_;
            if (std::abs(d1 - d2) > 0.0005f) {
                curR = prevR_ + (d1 + d2) * 0.45f;
            }
        }
        prev2R_ = prevR_;
        prevR_ = curR;
        right[i] = curR;
    }
}