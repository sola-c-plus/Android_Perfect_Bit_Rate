#pragma once
#include "dsp_types.h"

class DspAntiPreecho {
public:
    DspAntiPreecho();
    void configure(double sampleRate);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    double sampleRate_ = 48000.0;
    size_t lookaheadFrames_ = 168; // 約 3.5ms
    std::vector<float> ringBufL_;
    std::vector<float> ringBufR_;
    size_t writePos_ = 0;
    size_t readPos_ = 0;
    bool isBufferPrimed_ = false;
};

class DspBitContinuity {
public:
    DspBitContinuity() = default;
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    float prevL_ = 0.0f, prev2L_ = 0.0f;
    float prevR_ = 0.0f, prev2R_ = 0.0f;
};