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

        float futL = std::abs(inL);
        float futR = std::abs(inR);
        float nowL = std::abs(outL);
        float nowR = std::abs(outR);

        if (futL > nowL * 8.0f && futL > 0.15f && nowL < 0.03f) {
            float attenL = std::clamp(nowL / (futL * 0.10f + 1e-4f), 0.20f, 1.0f);
            outL *= attenL;
        }
        if (futR > nowR * 8.0f && futR > 0.15f && nowR < 0.03f) {
            float attenR = std::clamp(nowR / (futR * 0.10f + 1e-4f), 0.20f, 1.0f);
            outR *= attenR;
        }

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

        float absL = std::abs(curL);
        if (absL > 0.0001f && absL < 0.035f) {
            float d1 = curL - prevL_;
            float d2 = prevL_ - prev2L_;
            if (std::abs(d1 - d2) > 0.0015f) {
                curL = prevL_ + (d1 + d2) * 0.45f;
            }
        }
        prev2L_ = prevL_;
        prevL_ = curL;
        left[i] = curL;

        float absR = std::abs(curR);
        if (absR > 0.0001f && absR < 0.035f) {
            float d1 = curR - prevR_;
            float d2 = prevR_ - prev2R_;
            if (std::abs(d1 - d2) > 0.0015f) {
                curR = prevR_ + (d1 + d2) * 0.45f;
            }
        }
        prev2R_ = prevR_;
        prevR_ = curR;
        right[i] = curR;
    }
}