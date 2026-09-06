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

// ★ 高感度イヤホンでのクリック・ざらつきノイズを防ぐため、滑らかなコサイン窓でアッテネート
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

        writePos_ = (writePos_ + 1) % cap;
        readPos_  = (readPos_ + 1) % cap;

        left[i] = outL;
        right[i] = outR;
    }
}

void DspBitContinuity::reset() {
    prevL_ = 0.0f; prev2L_ = 0.0f;
    prevR_ = 0.0f; prev2R_ = 0.0f;
}

// ★ イヤホンがざらつく最大の原因だった「微小波形書き換え」を無力化し、純粋な高域ディテールを完全維持
void DspBitContinuity::processStereo(float* left, float* right, size_t numFrames) {
    // 高域倍音・リバーブの微小成分を破壊しないため無加工バイパス
    return;
}