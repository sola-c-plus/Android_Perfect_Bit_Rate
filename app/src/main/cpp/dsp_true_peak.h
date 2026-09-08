#pragma once
#include "dsp_types.h"
#include <vector>
#include <cmath>
#include <algorithm>

class DspTruePeakLimiter {
public:
    DspTruePeakLimiter();
    ~DspTruePeakLimiter() = default;

    void configure(double sampleRate, double ceilingDb = -0.15, double releaseMs = 60.0);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    double sampleRate_ = 48000.0;
    double ceiling_ = 0.98288; // -0.15 dBFS
    double releaseCoeff_ = 0.999;

    static constexpr size_t MAX_LOOKAHEAD = 1024;
    size_t lookaheadFrames_ = 72;
    std::vector<double> delayBufL_;
    std::vector<double> delayBufR_;
    size_t writePos_ = 0;
    size_t readPos_ = 0;
    bool isPrimed_ = false;

    double peakEnv_ = 0.0;
};