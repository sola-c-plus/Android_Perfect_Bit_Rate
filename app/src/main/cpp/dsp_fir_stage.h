#pragma once
#include "dsp_types.h"

class FirStage2x {
public:
    FirStage2x() = default;
    void configure(size_t numTaps, double cutoffHz, double outputRateHz, FirFilterType filterType, bool highPrecision = false);
    void reset();
    void processStereo(const float* inL, const float* inR, size_t numFrames,
                       std::vector<float, AlignedAllocator<float, 16>>& outL,
                       std::vector<float, AlignedAllocator<float, 16>>& outR);
private:
    double besselI0(double x);
    void convertToMinimumPhase(std::vector<double>& h, int totalTaps);

    size_t numTaps_ = 0;
    size_t tapsPerPhase_ = 0;
    std::vector<float, AlignedAllocator<float, 16>> poly0_;
    std::vector<float, AlignedAllocator<float, 16>> poly1_;
    // 倍精度経路 (ULTRA_HQ) 用の double 係数。highPrecision_ が false の間は空。
    std::vector<double> poly0D_;
    std::vector<double> poly1D_;
    bool highPrecision_ = false;
    std::vector<float, AlignedAllocator<float, 16>> mirrorHistL_;
    std::vector<float, AlignedAllocator<float, 16>> mirrorHistR_;
    int writePos_ = 0;
};