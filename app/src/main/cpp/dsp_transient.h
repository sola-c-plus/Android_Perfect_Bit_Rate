#pragma once
#include "dsp_types.h"

class DspTransientRestorer {
public:
    DspTransientRestorer();
    void configure(TransientMode mode, double sampleRate, bool useGroupDelay = false, bool useLattice = false);
    void reset();
    void processStereo(float* left, float* right, size_t numFrames);

private:
    TransientMode mode_ = TransientMode::NATURAL;
    double sampleRate_ = 48000.0;
    bool isBypass_ = false;
    bool useGroupDelay_ = false;
    bool useLattice_ = false;

    double attackGain_ = 1.05;
    double fastAlpha_ = 0.04;
    double slowAlpha_ = 0.002;

    double latK1_L_ = 0.0, latK2_L_ = 0.0;
    double latK1_R_ = 0.0, latK2_R_ = 0.0;
    double latB1_L_ = 0.0, latB2_L_ = 0.0;
    double latB1_R_ = 0.0, latB2_R_ = 0.0;

    double envFastL_ = 0.0, envSlowL_ = 0.0;
    double envFastR_ = 0.0, envSlowR_ = 0.0;
    double prevSampleL_ = 0.0, prevSampleR_ = 0.0;
};